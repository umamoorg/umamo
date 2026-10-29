package org.umamo.render.puppet

import org.umamo.render.device.DeformUniforms
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.DrawTextures
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.OverlayDrawUniforms
import org.umamo.render.device.RenderPassEncoder
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.WorldToNdc
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner

/**
 * What an overlay draw needs from the frame it lands in, whichever scene that is.
 *
 * @property WorldToNdc affine The camera affine.
 * @property Int viewportWidth The pass viewport width in framebuffer pixels.
 * @property Int viewportHeight The pass viewport height in framebuffer pixels.
 * @property Float pixelScale Framebuffer pixels per display pixel, which scales the overlay's sizes.
 * @property MeshOverlayPalette palette The overlay colors.
 * @property Int screenTexWidth The screen-texture divisor's width the camera call takes.
 * @property Int screenTexHeight The screen-texture divisor's height.
 * @property Boolean drawWireframe Whether an Edit overlay's plain wireframe meshes draw in this frame.
 * @property Float wireframeOpacity The alpha scale, 0 to 1, of the overlay drawn outside an edit: an
 *   Object-mode wireframe, an Edit overlay's plain wireframe meshes, a UV scene's islands.  The cage keeps
 *   the palette.
 * @property GpuTexture? orderTexture The draw-order target the frame's order pass wrote, which the wireframe
 *   edges cull by, or null when nothing culls (a UV scene, a frame with culling off).
 * @property Map<DrawableId, Int> drawOrderOf Each drawn drawable's back-to-front index, as the order pass
 *   wrote it; a wireframe entry absent from it draws unculled.
 * @property Boolean drawIdleLattices Whether a lattice overlay's idle lattices draw in this frame (its area
 *   shows every deformer); the selected and active ones always do.
 */
internal class OverlayFrame(
	val affine: WorldToNdc,
	val viewportWidth: Int,
	val viewportHeight: Int,
	val pixelScale: Float,
	val palette: MeshOverlayPalette,
	val screenTexWidth: Int,
	val screenTexHeight: Int,
	val drawWireframe: Boolean,
	val wireframeOpacity: Float,
	val orderTexture: GpuTexture? = null,
	val drawOrderOf: Map<DrawableId, Int> = emptyMap(),
	val drawIdleLattices: Boolean = true,
)

/**
 * Records the mesh overlay's frame work: the capture of the overlay meshes' deformed positions into the
 * overlay store, and the overlay draws over the finished art - the 2D scene's from that captured store, a
 * UV scene's from the positions its area uploaded, and the 2D scene's lattice layer from the lattice points
 * the renderer read off the pose and uploaded.
 *
 * The capture mirrors the glue capture: its own pass outside any render pass, once per frame whose store
 * is stale, followed by a barrier.  The draws go domain-major - every mesh's face fills, then every mesh's
 * edges, then the active edges, then the dots - so each domain binds its pipeline once rather than once
 * per mesh, and so the actives land on top of every batch; an entry the current pose leaves unposed (a
 * hidden ancestor, a grid out of range) is skipped in both.  An Edit overlay's plain wireframe meshes (those
 * outside the edit) join the edge domain alone, and only in a frame that draws the wireframe; they sit ahead
 * of the cage in the overlay's order, so the cage's edges land on top.  An islands overlay goes island-major
 * instead (see [drawIslands]): its stacking is the point, so each island's fill covers the islands behind it.
 * A lattice overlay goes role-major (see [drawLattices]), so a stronger role lands over a weaker one.
 *
 * @param DrawPipelines pipelines The capture and overlay pipelines.
 * @param SideTargetPool sideTargets The side targets, whose capacity names the screen-space divisor.
 * @param MeshOverlayResidency overlayResidency The overlay's store and buffers.
 */
internal class MeshOverlayEncoder(
	private val pipelines: DrawPipelines,
	private val sideTargets: SideTargetPool,
	private val overlayResidency: MeshOverlayResidency,
) {
	// Reused per-draw scratch, refilled each draw rather than allocated.  Render-thread only.
	private val deformScratch = DeformUniforms()
	private val texturesScratch = DrawTextures()
	private val uniformsScratch = OverlayDrawUniforms()

	/**
	 * Records the overlay capture when the store is behind the positions, and nothing otherwise.
	 *
	 * @param FrameEncoder frame The frame being recorded.
	 */
	fun encodeCapture(frame: FrameEncoder) {
		val store = overlayResidency.store ?: return

		// An entry the current pose leaves unposed (a hidden ancestor, a grid out of range, or no pose yet) has
		// nothing to capture: its corners may be a stale earlier pose's, so the pose's own flag decides.  With
		// none posed the store stays stale for the pose that follows, rather than an empty pass clearing it.
		if (!overlayResidency.storeStale || overlayResidency.entries.none { entry -> entry.gpuDrawable.visible }) {
			return
		}

		val capture = frame.beginDeformCapturePass(pipelines.capture, store)

		for (entry in overlayResidency.entries) {
			val gpuDrawable = entry.gpuDrawable

			if (!gpuDrawable.visible) {
				continue
			}

			fillDeform(deformScratch, gpuDrawable)
			texturesScratch.atlas = null
			texturesScratch.maskCoverage = null
			texturesScratch.deltaTexture = gpuDrawable.deltaTexture
			texturesScratch.warpControlPoints = gpuDrawable.cpTexture
			capture.captureDeformedPositions(gpuDrawable.mesh, deformScratch, texturesScratch, entry.baseOffset, gpuDrawable.vertexCount)
		}

		capture.end()
		frame.barrier(store)
		overlayResidency.storeStale = false
	}

	/**
	 * Records the 2D overlay draws into the open pass over the finished art, per the frame's overlay value
	 * and palette; nothing when the frame carries no overlay or nothing of it is posed.
	 *
	 * @param RenderPassEncoder pass The open, unscissored pass on the frame's target.
	 * @param FrameInputs inputs The frame's inputs.
	 */
	fun encodeDraws(pass: RenderPassEncoder, inputs: FrameInputs) {
		val overlay = inputs.overlay ?: return
		val store = overlayResidency.store ?: return
		val entries = overlayResidency.entries.filter { resident -> resident.gpuDrawable.visible }.map { resident -> resident.entry }
		drawEntries(pass, overlay, entries, store, overlayFrameOf(inputs, drawIdleLattices = true))
	}

	/**
	 * Records the 2D viewport's lattice layer into the open pass over the finished art and the mesh overlay, from
	 * the positions its residency uploaded; nothing when the layer holds nothing.
	 *
	 * @param RenderPassEncoder pass The open, unscissored pass on the frame's target.
	 * @param DirectOverlayResidency residency The lattice layer's residency, already brought to the layer.
	 * @param FrameInputs inputs The frame's inputs.
	 * @param Boolean drawIdle Whether the idle lattices draw too (the area shows every deformer).
	 */
	fun encodeLatticeDraws(pass: RenderPassEncoder, residency: DirectOverlayResidency, inputs: FrameInputs, drawIdle: Boolean) {
		encodeDirectDraws(pass, residency, overlayFrameOf(inputs, drawIdle))
	}

	/**
	 * Records the 2D viewport's direct Edit cage into the open pass over the finished art and the mesh overlay - the
	 * cage of whatever carries its own positions, a lattice's - from the positions its residency uploaded; nothing
	 * when it holds nothing.
	 *
	 * @param RenderPassEncoder pass The open, unscissored pass on the frame's target.
	 * @param DirectOverlayResidency residency The cage's residency, already brought to its value.
	 * @param FrameInputs inputs The frame's inputs.
	 */
	fun encodeEditDirectDraws(pass: RenderPassEncoder, residency: DirectOverlayResidency, inputs: FrameInputs) {
		encodeDirectDraws(pass, residency, overlayFrameOf(inputs, drawIdleLattices = true))
	}

	/**
	 * The overlay frame a 2D frame's overlay draws land in: its camera, viewport, scale, palette, and wireframe and
	 * culling state.
	 *
	 * @param FrameInputs inputs The frame's inputs.
	 * @param Boolean drawIdleLattices Whether a lattice layer's idle lattices draw.
	 * @return OverlayFrame The frame.
	 */
	private fun overlayFrameOf(inputs: FrameInputs, drawIdleLattices: Boolean): OverlayFrame =
		OverlayFrame(
			inputs.affine,
			inputs.viewportWidth,
			inputs.viewportHeight,
			inputs.pixelScale,
			inputs.overlayPalette,
			sideTargets.capacityWidth,
			sideTargets.capacityHeight,
			inputs.drawWireframe,
			inputs.wireframeOpacity,
			inputs.drawOrderTarget?.sampledTexture,
			inputs.drawOrderOf,
			drawIdleLattices,
		)

	/**
	 * Records a direct overlay's draws into an open pass, from the positions its residency uploaded: a UV area's
	 * overlay over its surface, or the 2D viewport's lattice layer over the art.  Nothing when there is no overlay
	 * or nothing of it paired.
	 *
	 * @param RenderPassEncoder pass The open pass on the target.
	 * @param DirectOverlayResidency residency The overlay's residency, already brought to its value.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, and palette.
	 */
	fun encodeDirectDraws(pass: RenderPassEncoder, residency: DirectOverlayResidency, frame: OverlayFrame) {
		val direct = residency.applied ?: return
		val store = residency.store ?: return
		drawEntries(pass, direct.overlay, residency.residents.map { resident -> resident.entry }, store, frame)
	}

	/**
	 * Records an overlay's draws, domain-major: every entry's face fills, then its edges, then the active
	 * edges, then the dots and the active dots, each domain binding its pipeline once.  In an Edit overlay
	 * the fills, the actives, and the dots are the cage's alone; the plain wireframe meshes contribute their
	 * edges, and none when the frame draws no wireframe.  The wireframe's edges - an Object-mode overlay's
	 * whole, an Edit overlay's plain meshes - draw at the palette's alpha times the frame's wireframe
	 * opacity, and none at zero; the cage's draw at the palette's.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param MeshOverlay overlay The overlay value (kind, select mode, sizes).
	 * @param List<OverlayDrawEntry> entries The entries to draw, in store order.
	 * @param DeformedPositionStore store The store the entries' positions are in.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, palette, and wireframe flag and opacity.
	 */
	private fun drawEntries(pass: RenderPassEncoder, overlay: MeshOverlay, entries: List<OverlayDrawEntry>, store: DeformedPositionStore, frame: OverlayFrame) {
		if (entries.isEmpty()) {
			return
		}

		if (overlay.kind == MeshOverlayKind.Islands) {
			drawIslands(pass, overlay, entries, store, frame)

			return
		}

		if (overlay.kind == MeshOverlayKind.Lattice) {
			drawLattices(pass, overlay, entries, store, frame)

			return
		}

		val palette = frame.palette
		val sizes = overlay.sizes
		val editing = overlay.kind == MeshOverlayKind.Edit
		// The wireframe entries - an object wireframe's whole content, an Edit overlay's plain meshes - wait for
		// a frame that draws the wireframe and fade in it, by the one rule the renderer culls them by too.
		val wireframeHidden = !frame.drawWireframe || frame.wireframeOpacity <= 0f
		uniformsScratch.viewportWidth = frame.viewportWidth.toFloat()
		uniformsScratch.viewportHeight = frame.viewportHeight.toFloat()

		if (editing) {
			// Face fills: every face in Face mode, only the selected ones otherwise; the active face fills
			// as selected, since the active face color belongs to its dot.
			bind(pass, pipelines.overlayFaceFill, frame)
			setColors(palette.faceIdle, palette.faceSelected, palette.faceSelected, opaque = false)
			uniformsScratch.sizePx = 0f
			uniformsScratch.fillIdle = overlay.selectMode == MeshOverlaySelectMode.Face
			forEachCageEntry(entries) { entry ->
				batch(entry)
				pass.drawOverlayFaceFill(entry.buffers, store, uniformsScratch)
			}
		}

		bind(pass, pipelines.overlayEdge, frame)
		uniformsScratch.sizePx = sizes.edgeWidthPx * frame.pixelScale / 2f
		uniformsScratch.fillIdle = true
		// The colors are written per entry, since a wireframe entry's are the palette's scaled by the opacity
		// and a cage entry's the palette's as they are, and the scratch holds one set at a time.  The cull
		// order is the wireframe entry's too: its drawable's place in the frame's draw order when the frame
		// wrote one, so its edges leave out what a drawable in front covers; the cage is never culled, and a
		// wireframe entry the order does not know (its drawable not drawn this frame) draws whole.
		val orderTexture = frame.orderTexture

		for (entry in entries) {
			val faded = overlay.kind.drawsAsWireframe(entry.wireframeOnly)

			if (faded && wireframeHidden) {
				continue
			}

			setColors(idleEdgeColorOf(entry, palette), palette.edgeSelected, palette.edgeActive, opaque = false, alphaScale = if (faded) frame.wireframeOpacity else 1f)
			batch(entry)
			val cullOrder = if (faded && orderTexture != null) (entry.owner as? ElementOwner.Drawable)?.let { owner -> frame.drawOrderOf[owner.id] } ?: -1 else -1
			uniformsScratch.cullOrder = cullOrder
			pass.drawOverlayEdges(entry.buffers, store, uniformsScratch, if (cullOrder >= 0) orderTexture else null)
		}

		uniformsScratch.cullOrder = -1

		if (editing) {
			setColors(palette.edgeIdle, palette.edgeSelected, palette.edgeActive, opaque = false)
			forEachCageEntry(entries) { entry ->
				if (entry.activeEdgeA >= 0) {
					active(entry, entry.activeEdgeA, entry.activeEdgeB, -1)
					pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
				}
			}
		}

		if (editing && overlay.selectMode == MeshOverlaySelectMode.Vertex) {
			bind(pass, pipelines.overlayVertexDot, frame)
			setColors(palette.vertexIdle, palette.vertexSelected, palette.vertexActive, opaque = false)
			uniformsScratch.sizePx = sizes.vertexDotRadiusPx * frame.pixelScale
			forEachCageEntry(entries) { entry ->
				batch(entry)
				pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
			}
			forEachCageEntry(entries) { entry ->
				if (entry.activeVertex >= 0) {
					active(entry, entry.activeVertex, -1, -1)
					pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
				}
			}
		}

		if (editing && overlay.selectMode == MeshOverlaySelectMode.Face) {
			// The face colors carry the fill alpha; the dots are the click affordance and render opaque.
			bind(pass, pipelines.overlayFaceDot, frame)
			setColors(palette.faceIdle, palette.faceSelected, palette.faceActive, opaque = true)
			uniformsScratch.sizePx = sizes.faceDotRadiusPx * frame.pixelScale
			forEachCageEntry(entries) { entry ->
				batch(entry)
				pass.drawOverlayFaceDots(entry.buffers, store, uniformsScratch)
			}
			forEachCageEntry(entries) { entry ->
				if (entry.activeFaceA >= 0) {
					active(entry, entry.activeFaceA, entry.activeFaceB, entry.activeFaceC)
					pass.drawOverlayFaceDots(entry.buffers, store, uniformsScratch)
				}
			}
		}
	}

	/**
	 * Runs [draw] over an Edit overlay's cage entries - every entry but its plain wireframe meshes - in store
	 * order, reading the split off each entry rather than building a list for it.
	 *
	 * @param List<OverlayDrawEntry> entries The overlay's entries, in store order.
	 * @param Function draw What to record for one cage entry.
	 */
	private inline fun forEachCageEntry(entries: List<OverlayDrawEntry>, draw: (OverlayDrawEntry) -> Unit) {
		for (entry in entries) {
			if (!entry.wireframeOnly) {
				draw(entry)
			}
		}
	}

	/**
	 * Records an islands overlay island-major, in the entries' order, which the producer makes back to front:
	 * each island fills its every face in its fill role's color, then outlines its every edge in its edge
	 * role's color, so a nearer island's fill covers the outlines of the islands behind it, the way the
	 * islands stack for the pick.  No dots and no active primitive: an island is selected whole.  The roles
	 * resolve against the palette here, so a palette change re-colors without a new overlay, and a role
	 * change re-colors without an upload, since every island's flags are all idle.  Every fill and outline
	 * draws at its role's alpha times the frame's wireframe opacity, and none at zero: the islands are the UV
	 * scene's overlay outside an edit, as the wireframe is the 2D viewport's.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param MeshOverlay overlay The islands overlay (its sizes).
	 * @param List<OverlayDrawEntry> entries The islands, back to front.
	 * @param DeformedPositionStore store The store the islands' positions are in.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, and palette.
	 */
	private fun drawIslands(pass: RenderPassEncoder, overlay: MeshOverlay, entries: List<OverlayDrawEntry>, store: DeformedPositionStore, frame: OverlayFrame) {
		if (frame.wireframeOpacity <= 0f) {
			return
		}

		val palette = frame.palette
		val alphaScale = frame.wireframeOpacity
		val edgeHalfWidth = overlay.sizes.edgeWidthPx * frame.pixelScale / 2f
		uniformsScratch.viewportWidth = frame.viewportWidth.toFloat()
		uniformsScratch.viewportHeight = frame.viewportHeight.toFloat()
		uniformsScratch.fillIdle = true

		for (entry in entries) {
			val style = entry.islandStyle
			val fill =
				when (style?.fill ?: IslandFillRole.Idle) {
					IslandFillRole.Idle -> palette.faceIdle
					IslandFillRole.Selected -> palette.faceSelected
				}
			val edge =
				when (style?.edge ?: IslandEdgeRole.Idle) {
					IslandEdgeRole.Idle -> palette.edgeIdle
					IslandEdgeRole.Selected -> palette.edgeSelected
					IslandEdgeRole.Active -> palette.edgeActive
					IslandEdgeRole.Warning -> palette.warning
					IslandEdgeRole.Pinned -> palette.pinnedPlacement
				}
			bind(pass, pipelines.overlayFaceFill, frame)
			setColors(fill, fill, fill, opaque = false, alphaScale = alphaScale)
			uniformsScratch.sizePx = 0f
			batch(entry)
			pass.drawOverlayFaceFill(entry.buffers, store, uniformsScratch)
			bind(pass, pipelines.overlayEdge, frame)
			setColors(edge, edge, edge, opaque = false, alphaScale = alphaScale)
			uniformsScratch.sizePx = edgeHalfWidth
			batch(entry)
			uniformsScratch.cullOrder = -1
			pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
		}
	}

	/**
	 * Records a lattice overlay role-major: the idle lattices (when the frame draws them), then the selected, then
	 * the active, so a lattice in a stronger role lands over a weaker one wherever they cross.  Within a role every
	 * lattice's grid lines draw, then every lattice's control-point dots, all in the role's color at its palette
	 * alpha: no fills, no triangles, and no per-element selection, since a lattice is picked whole here.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param MeshOverlay overlay The lattice overlay (its sizes).
	 * @param List<OverlayDrawEntry> entries The lattices, in store order.
	 * @param DeformedPositionStore store The store the lattices' positions are in.
	 * @param OverlayFrame frame The pass's camera, viewport, scale, palette, and idle-lattice flag.
	 */
	private fun drawLattices(pass: RenderPassEncoder, overlay: MeshOverlay, entries: List<OverlayDrawEntry>, store: DeformedPositionStore, frame: OverlayFrame) {
		val palette = frame.palette
		uniformsScratch.viewportWidth = frame.viewportWidth.toFloat()
		uniformsScratch.viewportHeight = frame.viewportHeight.toFloat()
		uniformsScratch.fillIdle = true
		uniformsScratch.cullOrder = -1

		for (role in LatticeRole.entries) {
			if (role == LatticeRole.Idle && !frame.drawIdleLattices) {
				continue
			}

			if (entries.none { entry -> (entry.latticeRole ?: LatticeRole.Idle) == role }) {
				continue
			}

			val roleColor =
				when (role) {
					LatticeRole.Idle -> palette.latticeIdle
					LatticeRole.Selected -> palette.latticeSelected
					LatticeRole.Active -> palette.latticeActive
				}
			bind(pass, pipelines.overlayEdge, frame)
			uniformsScratch.sizePx = overlay.sizes.edgeWidthPx * frame.pixelScale / 2f

			for (entry in entries) {
				if ((entry.latticeRole ?: LatticeRole.Idle) == role) {
					val color = if (entry.pending) palette.latticePending else roleColor
					setColors(color, color, color, opaque = false)
					batch(entry)
					pass.drawOverlayEdges(entry.buffers, store, uniformsScratch)
				}
			}

			bind(pass, pipelines.overlayVertexDot, frame)
			uniformsScratch.sizePx = overlay.sizes.vertexDotRadiusPx * frame.pixelScale

			for (entry in entries) {
				if ((entry.latticeRole ?: LatticeRole.Idle) == role) {
					val color = if (entry.pending) palette.latticePending else roleColor
					setColors(color, color, color, opaque = false)
					batch(entry)
					pass.drawOverlayVertexDots(entry.buffers, store, uniformsScratch)
				}
			}
		}
	}

	/**
	 * Binds one overlay domain's pipeline and the frame's camera.
	 *
	 * @param RenderPassEncoder pass The open pass.
	 * @param RenderPipeline pipeline The domain's pipeline.
	 * @param OverlayFrame frame The frame's camera and divisor.
	 */
	private fun bind(pass: RenderPassEncoder, pipeline: RenderPipeline, frame: OverlayFrame) {
		pass.setPipeline(pipeline)
		pass.setCamera(frame.affine, frame.screenTexWidth, frame.screenTexHeight)
	}

	/**
	 * Points the scratch uniforms at one mesh's batch: its store offset, no active primitive.
	 *
	 * @param OverlayDrawEntry entry The mesh.
	 */
	private fun batch(entry: OverlayDrawEntry) {
		uniformsScratch.baseOffset = entry.baseOffset
		uniformsScratch.activeDraw = false
		uniformsScratch.activeIndexA = -1
		uniformsScratch.activeIndexB = -1
		uniformsScratch.activeIndexC = -1
	}

	/**
	 * Points the scratch uniforms at one mesh's active primitive.
	 *
	 * @param OverlayDrawEntry entry The mesh.
	 * @param Int indexA The primitive's first local index.
	 * @param Int indexB Its second, or -1.
	 * @param Int indexC Its third, or -1.
	 */
	private fun active(entry: OverlayDrawEntry, indexA: Int, indexB: Int, indexC: Int) {
		uniformsScratch.baseOffset = entry.baseOffset
		uniformsScratch.activeDraw = true
		uniformsScratch.activeIndexA = indexA
		uniformsScratch.activeIndexB = indexB
		uniformsScratch.activeIndexC = indexC
	}

	/**
	 * The color an Edit overlay entry's unselected edges draw in: an unkeyed Pose-mode lattice's pending color, so the
	 * cage shows what a key would capture, and every other entry's idle edge color.
	 *
	 * @param OverlayDrawEntry entry The entry.
	 * @param MeshOverlayPalette palette The palette.
	 * @return OverlayColor The color.
	 */
	private fun idleEdgeColorOf(entry: OverlayDrawEntry, palette: MeshOverlayPalette): OverlayColor = if (entry.pending) palette.latticePending else palette.edgeIdle

	/**
	 * Writes one domain's three colors into the scratch uniforms.
	 *
	 * @param OverlayColor idle The idle color.
	 * @param OverlayColor selected The selected color.
	 * @param OverlayColor active The active color.
	 * @param Boolean opaque True to force every alpha to 1 (the face dots).
	 * @param Float alphaScale What each color's alpha is multiplied by (the wireframe's opacity); 1 writes it as is.
	 */
	private fun setColors(idle: OverlayColor, selected: OverlayColor, active: OverlayColor, opaque: Boolean, alphaScale: Float = 1f) {
		writeColor(uniformsScratch.idleColor, idle, opaque, alphaScale)
		writeColor(uniformsScratch.selectedColor, selected, opaque, alphaScale)
		writeColor(uniformsScratch.activeColor, active, opaque, alphaScale)
	}

	/**
	 * Writes one color's components into a four-float slot.
	 *
	 * @param FloatArray slot The slot.
	 * @param OverlayColor color The color.
	 * @param Boolean opaque True to write alpha 1 instead of the color's.
	 * @param Float alphaScale What the color's alpha is multiplied by when it is written.
	 */
	private fun writeColor(slot: FloatArray, color: OverlayColor, opaque: Boolean, alphaScale: Float) {
		slot[0] = color.red
		slot[1] = color.green
		slot[2] = color.blue
		slot[3] = if (opaque) 1f else color.alpha * alphaScale
	}
}