package org.umamo.render.puppet

import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.PipelineBlend
import org.umamo.render.device.PipelinePurpose
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.runtime.model.BlendMode

/**
 * The renderer's pipelines: the fixed-purpose ones, created together by [create], and the art-mesh draw
 * pipelines, created the first time a blend and cull state is drawn and reused every frame after.
 *
 * Render thread only.  Constructing it touches no device; [create] must run with the device's context
 * current.  Nothing here is ever freed: the device seam exposes no way to free a pipeline, so they live
 * as long as the context does.
 *
 * @property RenderDevice device The backend the pipelines live on.
 */
internal class DrawPipelines(
	private val device: RenderDevice,
) {
	// Draw pipelines by blend x cull.  Cull is index [0]=double-sided / [1]=back-face-culled so the
	// per-draw lookup allocates no key.
	private val puppetPipelines = Array(2) { HashMap<BlendMode, RenderPipeline>() }
	private val gluePipelines = Array(2) { HashMap<BlendMode, RenderPipeline>() }
	private var atlasPagePipeline: RenderPipeline? = null
	private var gridPipeline: RenderPipeline? = null
	private var axisPipeline: RenderPipeline? = null
	private var compositePipeline: RenderPipeline? = null
	private var capturePipeline: DeformCapturePipeline? = null

	// The mesh overlay's four pipelines are created on the first overlay frame, not in create(), so a
	// renderer that never shows an overlay (a capture, a UV area) links nothing for it.
	private var overlayFaceFillPipeline: RenderPipeline? = null
	private var overlayEdgePipeline: RenderPipeline? = null
	private var overlayVertexDotPipeline: RenderPipeline? = null
	private var overlayFaceDotPipeline: RenderPipeline? = null

	/** The pipeline that captures glue meshes' deformed positions.  Read only after [create]. */
	val capture: DeformCapturePipeline get() = capturePipeline!!

	/** The pipeline that draws a flat underlay image.  Read only after [create]. */
	val atlasPage: RenderPipeline get() = atlasPagePipeline!!

	/** The pipeline that fills the grid backdrop.  Read only after [create]. */
	val grid: RenderPipeline get() = gridPipeline!!

	/** The pipeline that draws a world-origin axis line.  Read only after [create]. */
	val axis: RenderPipeline get() = axisPipeline!!

	/** The pipeline that composites a rendered layer over its destination.  Read only after [create]. */
	val composite: RenderPipeline get() = compositePipeline!!

	/** The overlay's face-fill pipeline, created on first use. */
	val overlayFaceFill: RenderPipeline
		get() = overlayFaceFillPipeline ?: overlayPipeline(PipelinePurpose.OverlayFaceFill).also { created -> overlayFaceFillPipeline = created }

	/** The overlay's edge pipeline, created on first use. */
	val overlayEdge: RenderPipeline
		get() = overlayEdgePipeline ?: overlayPipeline(PipelinePurpose.OverlayEdge).also { created -> overlayEdgePipeline = created }

	/** The overlay's vertex-dot pipeline, created on first use. */
	val overlayVertexDot: RenderPipeline
		get() = overlayVertexDotPipeline ?: overlayPipeline(PipelinePurpose.OverlayVertexDot).also { created -> overlayVertexDotPipeline = created }

	/** The overlay's face-dot pipeline, created on first use. */
	val overlayFaceDot: RenderPipeline
		get() = overlayFaceDotPipeline ?: overlayPipeline(PipelinePurpose.OverlayFaceDot).also { created -> overlayFaceDotPipeline = created }

	/** Creates the fixed-purpose pipelines.  Must run with the device's context current. */
	fun create() {
		capturePipeline = device.createDeformCapturePipeline()
		atlasPagePipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.AtlasPageDraw, PipelineBlend.Normal))
		gridPipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.GridBackdrop, PipelineBlend.Opaque))
		axisPipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.WorldAxisLine, PipelineBlend.Opaque))
		// Blending disabled: the composite shader computes the whole blend from layer + snapshot.
		compositePipeline = device.createRenderPipeline(RenderPipelineSpec(PipelinePurpose.Composite, PipelineBlend.Opaque))
	}

	/**
	 * The cached draw pipeline for a glue vs non-glue mesh at a blend mode and cull state, created on
	 * first use.
	 *
	 * @param Boolean   isGlueMesh    Whether the mesh draws through the glue pipeline.
	 * @param BlendMode blendMode     The blend to draw with.
	 * @param Boolean   cullBackFaces Whether back faces are culled.
	 * @return RenderPipeline The pipeline.
	 */
	fun drawPipelineFor(isGlueMesh: Boolean, blendMode: BlendMode, cullBackFaces: Boolean): RenderPipeline {
		val cache = (if (isGlueMesh) gluePipelines else puppetPipelines)[if (cullBackFaces) 1 else 0]
		return cache.getOrPut(blendMode) {
			val purpose = if (isGlueMesh) PipelinePurpose.PuppetGlueDraw else PipelinePurpose.PuppetDeformDraw
			device.createRenderPipeline(RenderPipelineSpec(purpose, blendOf(blendMode), cullBackFaces))
		}
	}

	/**
	 * One overlay pipeline: every overlay domain blends Normal over the art, premultiplied in-shader,
	 * and culls nothing.
	 *
	 * @param PipelinePurpose purpose The overlay domain.
	 * @return RenderPipeline The pipeline.
	 */
	private fun overlayPipeline(purpose: PipelinePurpose): RenderPipeline = device.createRenderPipeline(RenderPipelineSpec(purpose, PipelineBlend.Normal))

	/**
	 * The fixed-function blend a drawable's blend mode draws with.
	 *
	 * @param BlendMode mode The drawable's blend mode.
	 * @return PipelineBlend The pipeline blend.
	 */
	private fun blendOf(mode: BlendMode): PipelineBlend =
		when (mode) {
			BlendMode.Normal -> PipelineBlend.Normal
			BlendMode.AdditivePremultiplied -> PipelineBlend.Additive
			BlendMode.MultiplyPremultiplied -> PipelineBlend.Multiply
			// Scene draws never reach here with a 5.3 mode (renderPlanNodes routes extended blends
			// through the destination-sampling composite pass); these branches are a safe fallback
			// approximating with the legacy fixed-function analog where one exists - the same
			// approximation the MOC3 constant-flags 2-bit field encodes for old runtimes.
			BlendMode.Additive, BlendMode.AdditiveGlow -> PipelineBlend.Additive
			BlendMode.Multiply -> PipelineBlend.Multiply
			BlendMode.Darken,
			BlendMode.ColorBurn,
			BlendMode.LinearBurn,
			BlendMode.Lighten,
			BlendMode.Screen,
			BlendMode.ColorDodge,
			BlendMode.Overlay,
			BlendMode.SoftLight,
			BlendMode.HardLight,
			BlendMode.LinearLight,
			BlendMode.Hue,
			BlendMode.Color,
			-> PipelineBlend.Normal
		}
}