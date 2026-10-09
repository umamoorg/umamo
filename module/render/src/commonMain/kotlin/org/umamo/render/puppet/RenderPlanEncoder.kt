package org.umamo.render.puppet

import org.umamo.render.device.CompositeUniforms
import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FragmentUniforms
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.LoadAction
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPassSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.WorldToNdc
import org.umamo.render.eval.PartRenderState
import org.umamo.render.eval.RenderPlanComposite
import org.umamo.render.eval.RenderPlanDrawable
import org.umamo.render.eval.RenderPlanNode
import org.umamo.render.glsl.SELECTION_TINT_STRENGTH
import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId

/**
 * Records a pose's render plan as passes: the glue capture, the drawables, the mask coverage, and the
 * layer composites.
 *
 * Passes never nest - a frame is a flat sequence of them - so a draw that needs a side target (mask
 * coverage, a composite layer) ends the pass it is in, renders the side target, and resumes.  This class
 * owns that fragmentation and nothing else: what is drawn and in what order is decided before it
 * ([resolvePose], [planCompositeAcceleration]), and the frame it records into is begun and ended by its
 * caller.
 *
 * It holds the per-draw uniform scratch.  One instance of each struct is refilled per draw rather than
 * allocated, and the glue capture and the draws share the same deform and texture instances.
 *
 * Render thread only.  Constructing it touches no device; every other call must run with the device's
 * context current.
 *
 * @property RenderDevice      device      The backend to record through.
 * @property DrawPipelines     pipelines   The capture, composite, and art-mesh draw pipelines.
 * @property SideTargetPool    sideTargets The mask coverage, destination snapshot, and layer targets.
 * @property DrawableResidency residency   The resident drawables and the glue store they weld through.
 */
internal class RenderPlanEncoder(
	private val device: RenderDevice,
	private val pipelines: DrawPipelines,
	private val sideTargets: SideTargetPool,
	private val residency: DrawableResidency,
) {
	// Reused per-draw uniform + texture scratch, refilled each draw rather than allocated. Render-thread only.
	private val deformScratch = DeformUniforms()
	private val fragmentScratch = FragmentUniforms()
	private val texturesScratch = DrawTextures()
	private val compositeScratch = CompositeUniforms()

	/**
	 * Records pass 1 when the glue store is behind the pose, and nothing otherwise.
	 *
	 * @param FrameEncoder frame The frame being recorded.
	 */
	fun encodeGlueCapture(frame: FrameEncoder) {
		// Pass 1: capture every glue mesh's deformed positions into the shared store. Only when the pose
		// changed - a static pose leaves the store (and pass 2's reads of it) unchanged.
		val activeStore = residency.positionStore
		if (activeStore != null && residency.glueStoreStale) {
			val capture = frame.beginDeformCapturePass(pipelines.capture, activeStore)
			for (gpuDrawable in residency.glueDeformList) {
				if (gpuDrawable.corners == null) {
					continue
				}
				fillDeform(deformScratch, gpuDrawable)
				texturesScratch.atlas = null
				texturesScratch.maskCoverage = null
				texturesScratch.deltaTexture = gpuDrawable.deltaTexture
				texturesScratch.warpControlPoints = gpuDrawable.cpTexture
				capture.captureDeformedPositions(
					gpuDrawable.mesh,
					deformScratch,
					texturesScratch,
					gpuDrawable.glueBaseOffset,
					gpuDrawable.vertexCount,
				)
			}
			capture.end()
			frame.barrier(activeStore)
			residency.glueStoreStale = false
		}
	}

	/**
	 * Records a pose's whole render plan into [target], starting in the open pass the caller began on it.
	 *
	 * @param FrameEncoder         frame     The frame being recorded.
	 * @param FrameInputs          inputs    The frame's inputs.
	 * @param List<RenderPlanNode> plan      The pose's resolved render plan, back-to-front.
	 * @param RenderTarget         target    The surface the frame draws into.
	 * @param RenderPassEncoder    startPass The open pass on [target].
	 * @return RenderPassEncoder The open pass on [target] after the plan (may differ from [startPass]).
	 */
	fun encodePlan(
		frame: FrameEncoder,
		inputs: FrameInputs,
		plan: List<RenderPlanNode>,
		target: RenderTarget,
		startPass: RenderPassEncoder,
	): RenderPassEncoder = renderPlanNodes(frame, plan, target, 0, inputs, startPass, scissor = null, orderTarget = inputs.drawOrderTarget, orderOpacity = 1f)

	/**
	 * Walks a render-plan span into [target]: plain drawables draw directly (with the mask-coverage
	 * pass fragmentation), a drawable whose blend is not fixed-function-expressible composites as an
	 * implicit singleton composite, and a [RenderPlanComposite] node renders its subtree into a
	 * pooled layer target and composites it back as one layer.  Passes never nest - the frame stays
	 * a flat pass sequence, and the CURRENT pass travels through as the return value.
	 *
	 * Two per-pose accelerations short-circuit the composite machinery (both pixel-preserving; see
	 * [planCompositeAcceleration]): a pose-identity Normal/Over composite over an all-Normal/Over
	 * subtree draws inline into the current pass, and a non-Out composite whose layer would land
	 * empty on screen (bounds off-viewport, or pose-blended opacity 0) is skipped outright.  The
	 * remaining composites confine their layer work to the subtree's bounds via [scissor] rects.
	 *
	 * A plan with no composite nodes and no extended-blend drawables takes exactly the old flat
	 * path: zero extra passes, zero resolves - the regression guard.
	 *
	 * @param FrameEncoder         frame     The frame being recorded.
	 * @param List<RenderPlanNode> nodes     The span to draw, back-to-front.
	 * @param RenderTarget         target    The surface this span draws into.
	 * @param Int                  depth     The composite nesting depth (0 = the real target).
	 * @param FrameInputs          inputs    The frame's inputs.
	 * @param RenderPassEncoder    startPass The open pass on [target].
	 * @param ScissorRect?         scissor   [target]'s own pass scissor (a composite layer's
	 *   bounds rect when this span IS an isolated subtree), re-applied whenever the span resumes a
	 *   pass on [target]; null at the top level.
	 * @param RenderTarget?        orderTarget The draw-order target every pass the span opens on [target]
	 *   carries, re-attached whenever the span resumes: the frame's at the top level and inside a composite
	 *   that covers, null inside one that tints (and in a frame that culls nothing), so nothing under a
	 *   tinting composite writes the order at any depth.
	 * @param Float                orderOpacity The product of the enclosing composites' opacities, by which a
	 *   draw's alpha is scaled before the draw-order threshold: a layer draws its children at full opacity and
	 *   fades them at the composite, but what covers a wire is what the viewer sees.
	 * @return RenderPassEncoder The open pass on [target] after the span (may differ from [startPass]).
	 */
	private fun renderPlanNodes(
		frame: FrameEncoder,
		nodes: List<RenderPlanNode>,
		target: RenderTarget,
		depth: Int,
		inputs: FrameInputs,
		startPass: RenderPassEncoder,
		scissor: ScissorRect?,
		orderTarget: RenderTarget?,
		orderOpacity: Float,
	): RenderPassEncoder {
		var pass = startPass
		for (node in nodes) {
			when (node) {
				is RenderPlanDrawable -> {
					val gpuDrawable = residency.residents[node.id] ?: continue
					if (gpuDrawable.isExtendedBlend) {
						// A 5.3 extended blend: not fixed-function-expressible, so draw the drawable
						// alone into a layer target and composite it in-shader.
						// An empty (fully faded) non-Out layer composites to the unchanged destination, so
						// skip it outright - independent of the bounds-scissor toggle (Out erases the
						// destination where the layer is empty, so it must keep the real composite path).
						if (gpuDrawable.alphaBlendMode != AlphaBlendMode.Out && gpuDrawable.opacity == 0f) {
							continue
						}
						var layerRect: ScissorRect? = null
						if (inputs.boundsScissorEnabled && gpuDrawable.alphaBlendMode != AlphaBlendMode.Out) {
							val bounds = inputs.acceleration.extendedDrawableBounds[gpuDrawable.id]
							if (bounds != null) {
								layerRect = scissorRectOf(bounds, inputs.affine, inputs.viewportWidth, inputs.viewportHeight, inputs.pixelScale) ?: continue
							}
						}
						pass.end()
						pass = compositeDrawable(frame, gpuDrawable, target, depth, inputs, layerRect, scissor, orderTarget)
						continue
					}
					var maskCoverage: GpuTexture? = null
					if (gpuDrawable.maskIds.isNotEmpty()) {
						// Mask by pass fragmentation: end the pass, render coverage into the mask target,
						// resume preserving what is drawn so far. Correct on every backend and non-nesting.
						pass.end()
						renderMaskCoverage(frame, gpuDrawable.maskIds, inputs)
						pass = frame.beginRenderPass(passSpec(target, LoadAction.Load, inputs.viewportWidth, inputs.viewportHeight, scissor = scissor, drawOrder = orderTarget))
						maskCoverage = sideTargets.maskTarget?.sampledTexture
					}
					val isActive = inputs.activeId != null && gpuDrawable.id == inputs.activeId
					val highlight = if (isActive || gpuDrawable.id in inputs.selectedIds) SELECTION_TINT_STRENGTH else 0f
					// Only a Normal-blend drawable covers what is behind it; an Additive or Multiply one tints it, so
					// it writes no order (zero) while keeping its place in the order.  A span without the order
					// target writes none either.
					val drawOrder = if (orderTarget != null && gpuDrawable.blendMode == BlendMode.Normal) inputs.drawOrderOf[gpuDrawable.id] ?: 0 else 0
					drawDrawable(
						pass,
						gpuDrawable,
						inputs,
						gpuDrawable.blendMode,
						gpuDrawable.opacity,
						highlight,
						isActive,
						maskCoverage != null,
						maskCoverage,
						drawOrder,
						orderOpacity,
					)
				}

				is RenderPlanComposite -> {
					if (node.partId in inputs.acceleration.flattenable) {
						// Identity source-over of an all-Normal/Over subtree: Over is associative,
						// so the subtree draws inline - no layer, no snapshot, no composite draw.
						pass = renderPlanNodes(frame, node.children, target, depth, inputs, pass, scissor, orderTarget, orderOpacity)
						continue
					}
					// A fully faded non-Out layer composites to the unchanged destination, so skip it
					// outright - independent of the bounds-scissor toggle (see the extended-blend branch).
					val poseOpacity = inputs.compositeStates[node.partId]?.opacity ?: node.composite.opacity
					if (node.composite.alphaBlendMode != AlphaBlendMode.Out && poseOpacity == 0f) {
						continue
					}
					var layerRect: ScissorRect? = null
					if (inputs.boundsScissorEnabled && node.composite.alphaBlendMode != AlphaBlendMode.Out) {
						val bounds = inputs.acceleration.compositeBounds[node.partId]
						if (bounds != null) {
							layerRect = scissorRectOf(bounds, inputs.affine, inputs.viewportWidth, inputs.viewportHeight, inputs.pixelScale) ?: continue
						}
					}
					pass.end()
					pass = compositeGroup(frame, node, target, depth, inputs, layerRect, scissor, orderTarget, orderOpacity * poseOpacity)
				}
			}
		}
		return pass
	}

	/**
	 * Renders an isolated part's subtree into the depth's pooled layer target, then composites it
	 * back into [target] as one layer with the part's blend modes, pose-blended channels, and
	 * optional clip mask.  Returns the fresh open pass on [target].
	 *
	 * @param FrameEncoder        frame         The frame being recorded.
	 * @param RenderPlanComposite node          The isolated part's plan node (subtree + composite settings).
	 * @param RenderTarget        target        The surface the layer composites back into.
	 * @param Int                 depth         The composite nesting depth (its layer target's pool slot).
	 * @param FrameInputs         inputs        The frame's inputs.
	 * @param ScissorRect?        layerRect     The subtree's bounds - confines the layer clear + subtree
	 *   draw, null for a full-viewport layer (Out alpha or uncomputable bounds).
	 * @param ScissorRect?        parentScissor [target]'s own scissor (the enclosing layer's rect), null at
	 *   the top level; the composite-back is confined to it intersected with [layerRect].
	 * @param RenderTarget?       parentOrder   The draw-order target the enclosing span's passes carry, which
	 *   the subtree inherits when this composite covers and the pass after the composite carries again.
	 * @param Float               orderOpacity  The opacity product the subtree's draws scale their alpha by
	 *   before the draw-order threshold, this composite's own included.
	 * @return RenderPassEncoder The fresh open pass on [target] after the composite.
	 */
	private fun compositeGroup(
		frame: FrameEncoder,
		node: RenderPlanComposite,
		target: RenderTarget,
		depth: Int,
		inputs: FrameInputs,
		layerRect: ScissorRect?,
		parentScissor: ScissorRect?,
		parentOrder: RenderTarget?,
		orderOpacity: Float,
	): RenderPassEncoder {
		val layerTarget = sideTargets.acquireLayer(depth)
		// The layer's draws write the frame's draw order too: a composite's children cover as they are seen,
		// while a composite that blends other than Normal over tints, so nothing beneath it writes any, nested
		// composites included.  The order is written as the children draw, before the composite applies its clip
		// mask, so a masked composite records its children's whole extent as covering; and an Out composite or
		// drawable erases the art behind it without clearing the order that art wrote.  Either way a wire behind
		// is culled where the art in front of it is not shown - an accepted approximation, since putting it right
		// means masking and erasing the order target alongside the color.
		val covers = node.composite.blendMode == BlendMode.Normal && node.composite.alphaBlendMode == AlphaBlendMode.Over
		val layerOrder = if (covers) parentOrder else null
		var subtreePass =
			frame.beginRenderPass(passSpec(layerTarget, LoadAction.Clear, inputs.viewportWidth, inputs.viewportHeight, clearAlpha = 0f, scissor = layerRect, drawOrder = layerOrder))
		subtreePass = renderPlanNodes(frame, node.children, layerTarget, depth + 1, inputs, subtreePass, layerRect, layerOrder, orderOpacity)
		subtreePass.end()
		val masked = node.composite.maskedBy.isNotEmpty()
		if (masked) {
			renderMaskCoverage(frame, node.composite.maskedBy, inputs)
		}
		// The pose-blended channels; a part missing from the map (never the case for an isolated
		// group, but harmless) falls back to its static channels.
		val state = inputs.compositeStates[node.partId]
		compositeScratch.colorMode = packedColorModeOf(node.composite.blendMode)
		compositeScratch.alphaMode = packedAlphaModeOf(node.composite.alphaBlendMode)
		compositeScratch.opacity = state?.opacity ?: node.composite.opacity
		val multiply = state?.multiplyColor ?: node.composite.multiplyColor
		val screen = state?.screenColor ?: node.composite.screenColor
		compositeScratch.multiplyRed = multiply.red
		compositeScratch.multiplyGreen = multiply.green
		compositeScratch.multiplyBlue = multiply.blue
		compositeScratch.screenRed = screen.red
		compositeScratch.screenGreen = screen.green
		compositeScratch.screenBlue = screen.blue
		compositeScratch.useMask = masked
		compositeScratch.invertMask = masked && node.composite.invertMask
		return encodeComposite(frame, layerTarget, target, inputs, intersectScissor(layerRect, parentScissor), parentScissor, parentOrder)
	}

	/**
	 * Draws one extended-blend drawable as an implicit singleton composite: the drawable alone (with
	 * its own opacity and clip mask) into the depth's layer target, composited back with its color
	 * and alpha modes at identity channels.  Returns the fresh open pass on [target].
	 *
	 * @param FrameEncoder frame         The frame being recorded.
	 * @param GpuDrawable  gpuDrawable   The extended-blend drawable to composite.
	 * @param RenderTarget target        The surface the layer composites back into.
	 * @param Int          depth         The composite nesting depth (its layer target's pool slot).
	 * @param FrameInputs  inputs        The frame's inputs.
	 * @param ScissorRect? layerRect     The drawable's bounds - confines the layer clear + draw, null
	 *   for a full-viewport layer (Out alpha or uncomputable bounds).
	 * @param ScissorRect? parentScissor [target]'s own scissor, null at the top level.
	 * @param RenderTarget? parentOrder  The draw-order target the enclosing span's passes carry, which the
	 *   pass after the composite carries again; the drawable's own layer pass writes none.
	 * @return RenderPassEncoder The fresh open pass on [target] after the composite.
	 */
	private fun compositeDrawable(
		frame: FrameEncoder,
		gpuDrawable: GpuDrawable,
		target: RenderTarget,
		depth: Int,
		inputs: FrameInputs,
		layerRect: ScissorRect?,
		parentScissor: ScissorRect?,
		parentOrder: RenderTarget?,
	): RenderPassEncoder {
		var maskCoverage: GpuTexture? = null
		if (gpuDrawable.maskIds.isNotEmpty()) {
			renderMaskCoverage(frame, gpuDrawable.maskIds, inputs)
			maskCoverage = sideTargets.maskTarget?.sampledTexture
		}
		val layerTarget = sideTargets.acquireLayer(depth)
		val layerPass =
			frame.beginRenderPass(passSpec(layerTarget, LoadAction.Clear, inputs.viewportWidth, inputs.viewportHeight, clearAlpha = 0f, scissor = layerRect))
		val isActive = inputs.activeId != null && gpuDrawable.id == inputs.activeId
		val highlight = if (isActive || gpuDrawable.id in inputs.selectedIds) SELECTION_TINT_STRENGTH else 0f
		// Normal blend onto the cleared transparent layer just writes the premultiplied pixels; the
		// drawable's real blend happens in the composite below.
		drawDrawable(
			layerPass,
			gpuDrawable,
			inputs,
			BlendMode.Normal,
			gpuDrawable.opacity,
			highlight,
			isActive,
			maskCoverage != null,
			maskCoverage,
		)
		layerPass.end()
		compositeScratch.colorMode = packedColorModeOf(gpuDrawable.blendMode)
		compositeScratch.alphaMode = packedAlphaModeOf(gpuDrawable.alphaBlendMode)
		compositeScratch.opacity = 1f
		compositeScratch.multiplyRed = 1f
		compositeScratch.multiplyGreen = 1f
		compositeScratch.multiplyBlue = 1f
		compositeScratch.screenRed = 0f
		compositeScratch.screenGreen = 0f
		compositeScratch.screenBlue = 0f
		compositeScratch.useMask = false
		compositeScratch.invertMask = false
		return encodeComposite(frame, layerTarget, target, inputs, intersectScissor(layerRect, parentScissor), parentScissor, parentOrder)
	}

	/**
	 * Snapshots [target], begins a fresh Load pass on it, and issues the composite draw from
	 * [layerTarget] against the snapshot using the already-filled [compositeScratch].
	 *
	 * The composite reads and writes the same fragments, so [compositeScissor] bounds BOTH the
	 * destination snapshot copy and the composite draw - the whole target when null.
	 *
	 * The composite draw runs in its OWN pass under [compositeScissor], which is then ended; the
	 * returned continuation pass is scissored to [continuationScissor] (the enclosing span's own
	 * scissor, null at the top level) so the drawables the caller draws AFTER this composite are NOT
	 * clipped to the composite's bounds - reusing the composite's own scissor for the continuation
	 * pass would incorrectly shrink everything drawn behind it in the same span.
	 *
	 * @param FrameEncoder frame               The frame being recorded.
	 * @param RenderTarget layerTarget         The pooled layer holding the subtree/drawable to composite.
	 * @param RenderTarget target              The surface the layer composites back into.
	 * @param FrameInputs  inputs              The frame's inputs.
	 * @param ScissorRect? compositeScissor    The rect the snapshot + composite are confined to, or null.
	 * @param ScissorRect? continuationScissor The scissor the returned pass carries, or null.
	 * @param RenderTarget? continuationOrder The draw-order target the returned pass carries: the enclosing
	 *   span's, so a span under a tinting composite stays without one past a nested composite.
	 * @return RenderPassEncoder A fresh open pass on [target] under [continuationScissor].
	 */
	private fun encodeComposite(
		frame: FrameEncoder,
		layerTarget: RenderTarget,
		target: RenderTarget,
		inputs: FrameInputs,
		compositeScissor: ScissorRect?,
		continuationScissor: ScissorRect?,
		continuationOrder: RenderTarget?,
	): RenderPassEncoder {
		// Snapshot the destination so the composite shader can sample it (a pass cannot sample its
		// own target); the copy is ordered between the passes around it, and confined to the same
		// rect the composite draw reads and writes.  Used-region resolve: [target] can be the main
		// draw target (the surface's capacity) or a pooled layer (the side-target capacity), and the
		// snapshot has its own capacity - equal USED extents anchored at the origin is the invariant,
		// and the scissor rect flips against the used height.
		val snapshot = sideTargets.snapshotTarget ?: error("composite targets not allocated")
		device.resolveUsed(target, inputs.viewportWidth, inputs.viewportHeight, snapshot, inputs.viewportWidth, inputs.viewportHeight, compositeScissor)
		val compositePass = frame.beginRenderPass(passSpec(target, LoadAction.Load, inputs.viewportWidth, inputs.viewportHeight, scissor = compositeScissor))
		compositePass.setPipeline(pipelines.composite)
		compositePass.setCamera(inputs.affine, sideTargets.capacityWidth, sideTargets.capacityHeight)
		texturesScratch.atlas = null
		texturesScratch.deltaTexture = null
		texturesScratch.warpControlPoints = null
		texturesScratch.maskCoverage = if (compositeScratch.useMask) sideTargets.maskTarget?.sampledTexture else null
		texturesScratch.compositeLayer = layerTarget.sampledTexture
		texturesScratch.destinationSnapshot = snapshot.sampledTexture
		compositePass.drawComposite(compositeScratch, texturesScratch)
		texturesScratch.compositeLayer = null
		texturesScratch.destinationSnapshot = null
		compositePass.end()
		// A fresh pass under the ENCLOSING span's scissor - the composite's own scissor must not leak
		// onto whatever the caller draws next into this target.
		return frame.beginRenderPass(passSpec(target, LoadAction.Load, inputs.viewportWidth, inputs.viewportHeight, scissor = continuationScissor, drawOrder = continuationOrder))
	}

	/**
	 * Renders the mask sources' coverage into the shared mask target, as its own cleared pass at the
	 * render's USED viewport (the same origin-anchored projection as the main pass, so fragment (x, y)
	 * there reads coverage texel (x, y) here).  The Clear load fills the whole capacity texture
	 * (a clear ignores the viewport) - harmless, and cheap next to the realloc it replaces.
	 *
	 * @param FrameEncoder     frame   The frame being recorded.
	 * @param List<DrawableId> maskIds The mask source drawables.
	 * @param FrameInputs      inputs  The frame's inputs.
	 */
	private fun renderMaskCoverage(
		frame: FrameEncoder,
		maskIds: List<DrawableId>,
		inputs: FrameInputs,
	) {
		val target = sideTargets.maskTarget ?: return
		val coverage = frame.beginRenderPass(passSpec(target, LoadAction.Clear, inputs.viewportWidth, inputs.viewportHeight, clearAlpha = 0f))
		for (maskId in maskIds) {
			val mask = residency.residents[maskId] ?: continue
			if (!mask.visible || mask.indexCount == 0) {
				continue
			}
			// Coverage is the mask's shape at full intensity, unmasked, and ALWAYS Normal blend regardless
			// of the mask drawable's own blend mode: an Additive or Multiply source would leave the cleared
			// coverage target's alpha at 0, so the masked drawable would sample zero coverage and vanish.
			drawDrawable(
				coverage,
				mask,
				inputs,
				blendMode = BlendMode.Normal,
				opacity = 1f,
				highlight = 0f,
				isActive = false,
				masked = false,
				maskCoverage = null,
			)
		}
		coverage.end()
	}

	/**
	 * Binds a drawable's pipeline + per-pose state and issues its draw into [pass].  The camera's
	 * screen-space divisor is always the side-target capacity: a masked draw samples the mask
	 * coverage texture at that allocation, and for an unmasked (or coverage-pass) draw the divisor
	 * is simply unused.
	 *
	 * @param RenderPassEncoder pass         The open pass to draw into.
	 * @param GpuDrawable       gpuDrawable  The resident drawable to draw.
	 * @param FrameInputs       inputs       The frame's inputs.
	 * @param BlendMode         blendMode    The blend to draw with - the drawable's own for the main pass,
	 *   forced [BlendMode.Normal] for a mask-coverage draw (see [renderMaskCoverage]).
	 * @param Float             opacity      The opacity to draw at.
	 * @param Float             highlight    How far to tint toward the highlight color (0 = untinted).
	 * @param Boolean           isActive     Whether the tint is the active drawable's rather than the
	 *   selection's.
	 * @param Boolean           masked       Whether the draw samples [maskCoverage].
	 * @param GpuTexture?       maskCoverage The clip mask's coverage, or null when unmasked.
	 * @param Int               drawOrder    The back-to-front index the draw's covering fragments write into the
	 *   pass's draw-order target, or 0 for none.
	 * @param Float             orderOpacity The enclosing composites' opacity product the alpha is scaled by
	 *   before the order threshold.
	 */
	private fun drawDrawable(
		pass: RenderPassEncoder,
		gpuDrawable: GpuDrawable,
		inputs: FrameInputs,
		blendMode: BlendMode,
		opacity: Float,
		highlight: Float,
		isActive: Boolean,
		masked: Boolean,
		maskCoverage: GpuTexture?,
		drawOrder: Int = 0,
		orderOpacity: Float = 1f,
	) {
		pass.setPipeline(pipelines.drawPipelineFor(gpuDrawable.isGlueMesh, blendMode, gpuDrawable.culling))
		pass.setCamera(inputs.affine, sideTargets.capacityWidth, sideTargets.capacityHeight)
		fillFragment(fragmentScratch, gpuDrawable, opacity, highlight, if (isActive) inputs.activeHighlightColor else inputs.highlightColor, masked)
		fragmentScratch.drawOrder = drawOrder
		fragmentScratch.orderOpacity = orderOpacity
		// Reused per draw rather than allocating a bundle per drawable per frame, matching the deform /
		// fragment scratch. A glue draw does not deform, so it needs no delta / control-point textures.
		texturesScratch.atlas = gpuDrawable.activeTexture()
		texturesScratch.maskCoverage = maskCoverage
		if (gpuDrawable.isGlueMesh) {
			texturesScratch.deltaTexture = null
			texturesScratch.warpControlPoints = null
			pass.drawGlueMesh(gpuDrawable.mesh, residency.positionStore!!, gpuDrawable.glueBaseOffset, residency.glueIntensities, fragmentScratch, texturesScratch)
		} else {
			texturesScratch.deltaTexture = gpuDrawable.deltaTexture
			texturesScratch.warpControlPoints = gpuDrawable.cpTexture
			fillDeform(deformScratch, gpuDrawable)
			pass.drawPuppetMesh(gpuDrawable.mesh, deformScratch, fragmentScratch, texturesScratch)
		}
	}
}

/**
 * What one frame draws with: the view it projects through, the selection it tints, the pose's
 * composite state, and the mesh overlay drawn over the art.
 *
 * Read ONCE, when the frame begins, and carried through every pass of it - so a frame is drawn from one
 * consistent set of values whatever a setter does while it is being recorded.
 *
 * @property WorldToNdc                   affine               The camera affine.
 * @property Int                          viewportWidth        The viewport width in pixels.
 * @property Int                          viewportHeight       The viewport height in pixels.
 * @property Float                        pixelScale           Framebuffer pixels per on-screen pixel.
 * @property Set<DrawableId>              selectedIds          The drawables tinted as selected.
 * @property DrawableId?                  activeId             The drawable tinted as active, or null.
 * @property FloatArray                   highlightColor       The color selected drawables tint toward.
 * @property FloatArray                   activeHighlightColor The color the active drawable tints toward.
 * @property Boolean                      boundsScissorEnabled Whether composite layer work is confined to
 *   the bounds [acceleration] carries.
 * @property Map<PartId, PartRenderState> compositeStates      The pose-blended composite channels per
 *   isolated part.
 * @property CompositeAcceleration        acceleration         The pose's composite acceleration state.
 * @property MeshOverlay?                 overlay              The mesh overlay drawn over the art, or null
 *   for none (a capture).
 * @property MeshOverlayPalette           overlayPalette       The colors the overlay draws with.
 * @property Boolean                      drawWireframe        Whether the overlay's plain wireframe meshes
 *   (those outside the edit) draw this frame; the cage draws regardless.
 * @property Float                        wireframeOpacity     The alpha scale the wireframe meshes draw at,
 *   0 to 1; the cage keeps the palette.
 * @property RenderTarget?                drawOrderTarget      The draw-order target the frame's art passes write
 *   as their second draw buffer and the wireframe edges cull by, or null when the frame culls nothing.
 * @property Map<DrawableId, Int>         drawOrderOf          Each drawn drawable's back-to-front index, 1 the
 *   backmost, which its covering fragments write; empty when the frame culls nothing.
 */
internal class FrameInputs(
	val affine: WorldToNdc,
	val viewportWidth: Int,
	val viewportHeight: Int,
	val pixelScale: Float,
	val selectedIds: Set<DrawableId>,
	val activeId: DrawableId?,
	val highlightColor: FloatArray,
	val activeHighlightColor: FloatArray,
	val boundsScissorEnabled: Boolean,
	val compositeStates: Map<PartId, PartRenderState>,
	val acceleration: CompositeAcceleration,
	val overlay: MeshOverlay?,
	val overlayPalette: MeshOverlayPalette,
	val drawWireframe: Boolean,
	val wireframeOpacity: Float,
	val drawOrderTarget: RenderTarget? = null,
	val drawOrderOf: Map<DrawableId, Int> = emptyMap(),
)

/**
 * A render-pass spec for [target] at [load], with an optional clear color and pass scissor.
 *
 * @param RenderTarget target         The surface the pass writes.
 * @param LoadAction   load           What the pass does with the target's existing contents.
 * @param Int          viewportWidth  The viewport width in pixels.
 * @param Int          viewportHeight The viewport height in pixels.
 * @param Float        clearAlpha     Clear alpha; read only when [load] clears.
 * @param ScissorRect? scissor        The rectangle every write of the pass is confined to, or null.
 * @param Float        clearRed       Clear red; read only when [load] clears.
 * @param Float        clearGreen     Clear green; read only when [load] clears.
 * @param Float        clearBlue      Clear blue; read only when [load] clears.
 * @param RenderTarget? drawOrder     The draw-order target the pass's art draws write, or null.
 * @param Boolean      clearDrawOrder Whether the pass clears that target to nothing first.
 * @return RenderPassSpec The spec.
 */
internal fun passSpec(
	target: RenderTarget,
	load: LoadAction,
	viewportWidth: Int,
	viewportHeight: Int,
	clearAlpha: Float = 0f,
	scissor: ScissorRect? = null,
	clearRed: Float = 0f,
	clearGreen: Float = 0f,
	clearBlue: Float = 0f,
	drawOrder: RenderTarget? = null,
	clearDrawOrder: Boolean = false,
) = RenderPassSpec(
	colorTarget = target,
	loadAction = load,
	viewportWidth = viewportWidth,
	viewportHeight = viewportHeight,
	clearRed = clearRed,
	clearGreen = clearGreen,
	clearBlue = clearBlue,
	clearAlpha = clearAlpha,
	scissor = scissor,
	drawOrderTarget = drawOrder,
	clearDrawOrder = clearDrawOrder,
)