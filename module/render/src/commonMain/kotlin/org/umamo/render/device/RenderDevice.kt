package org.umamo.render.device

import org.umamo.format.raster.RasterImage

/**
 * The GPU backend seam: one implementation per graphics API.
 *
 * Everything above it - the puppet renderer, the deform eval, the pose resolution, the model diff - is
 * backend-neutral.  Desktop OpenGL 3.3 core is implemented; Android GLES 3.0 is a near-transliteration of
 * it (the two differ in binding style, not semantics); iPadOS Metal is a later port.
 *
 * Shaped for the GL family first, deliberately.  Where avoiding a GL-ism costs nothing it is avoided -
 * passes name their target, pipelines are immutable, uniforms are structs - because those read better
 * regardless.  Where pre-solving Metal would cost real complexity for a backend that does not exist yet,
 * it is not done; that bill is paid if and when Metal asks for it.
 *
 * A device is created by the host, which owns whatever context or queue the backend needs, and is used
 * from ONE thread only - the render thread.
 *
 * GPU バックエンドの継ぎ目。GL / GLES / Metal ごとに実装する。
 */
internal interface RenderDevice {
	// --- Resources. Everything the device hands out, the device frees. ---

	/**
	 * Uploads an image as a sampleable texture.
	 *
	 * @param Int           width  Width in pixels.
	 * @param Int           height Height in pixels.
	 * @param TextureFormat format The pixel format.
	 * @param TextureFilter filter How texels are filtered between sample points.
	 * @param ByteArray?    pixels RGBA8888, top row first; null allocates without uploading.
	 * @return GpuTexture The texture.
	 */
	fun createTexture(width: Int, height: Int, format: TextureFormat, filter: TextureFilter, pixels: ByteArray?): GpuTexture

	/**
	 * Uploads float texels (RG pairs, row-major from the top) as a sampleable texture.
	 *
	 * @param Int           width  Width in texels.
	 * @param Int           height Height in texels.
	 * @param TextureFilter filter How texels are filtered.
	 * @param FloatArray    texels The texels.
	 * @return GpuTexture The texture.
	 */
	fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture

	/**
	 * Re-specifies an existing float texture's contents in place, keeping the same handle.
	 *
	 * The renderer calls this once per POSE CHANGE, never in the draw loop, and a backend must not
	 * re-create the resource here.  Warp control points are pose-dependent but frame-invariant, and
	 * re-specifying that texture 60x/sec churned the d3d12/Mesa driver into progressively corrupting the
	 * points it sampled - the "facial features warp over time" bug, worst on masked warp meshes, which
	 * draw twice.  The frequency is the fix; keep it.
	 *
	 * @param GpuTexture texture The texture to overwrite.
	 * @param Int        width   Width in texels.
	 * @param Int        height  Height in texels.
	 * @param FloatArray texels  The new texels.
	 */
	fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray)

	/**
	 * Uploads a mesh's static residency.
	 *
	 * @param MeshSpec spec The mesh's rest geometry and, for a glue mesh, its weld attributes.
	 * @return GpuMesh The resident mesh.
	 */
	fun createMesh(spec: MeshSpec): GpuMesh

	/**
	 * Re-uploads a resident mesh's rest positions in place; the topology is unchanged.
	 *
	 * @param GpuMesh    mesh          The resident mesh.
	 * @param FloatArray restPositions The new positions, same length as at upload.
	 */
	fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray)

	/**
	 * Re-uploads a resident mesh's UVs in place; the topology is unchanged.
	 *
	 * @param GpuMesh    mesh The resident mesh.
	 * @param FloatArray uvs  The new UVs, same length as at upload.
	 */
	fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray)

	/**
	 * Allocates a render target.
	 *
	 * @param RenderTargetSpec spec What to allocate.
	 * @return RenderTarget The target.
	 */
	fun createRenderTarget(spec: RenderTargetSpec): RenderTarget

	/**
	 * Allocates the shared pass-1 deformed-position store.
	 *
	 * @param Int vertexCapacity The total glue vertex count across every glue mesh.
	 * @return DeformedPositionStore The store.
	 */
	fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore

	/**
	 * Returns the draw pipeline for [spec], creating it on first ask.  Pipelines are immutable and reused.
	 *
	 * @param RenderPipelineSpec spec Which draw, and how it blends.
	 * @return RenderPipeline The pipeline.
	 */
	fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline

	/**
	 * Returns the pipeline that evaluates the deform per vertex and writes positions without drawing.
	 *
	 * @return DeformCapturePipeline The pipeline.
	 */
	fun createDeformCapturePipeline(): DeformCapturePipeline

	/** Frees [texture]. */
	fun destroyTexture(texture: GpuTexture)

	/** Frees [mesh] and every buffer it owns. */
	fun destroyMesh(mesh: GpuMesh)

	/** Frees [target]. */
	fun destroyRenderTarget(target: RenderTarget)

	// --- Frame recording ---

	/**
	 * Begins recording a frame's work.  End it with [FrameEncoder.endFrame].
	 *
	 * @return FrameEncoder The encoder.
	 */
	fun beginFrame(): FrameEncoder

	// --- Read-back ---

	/**
	 * Reads [target] back to the host, synchronously.
	 *
	 * Always RGBA8888 with the TOP row first, whatever the backend's framebuffer origin: the GL device
	 * flips its bottom-up read, and a top-left-origin backend would not.  Fixing the convention here is
	 * what stops every caller having to ask which way up a backend is.
	 *
	 * Synchronous, so it stalls the pipeline.  Fine for verification and thumbnails; the per-frame path
	 * uses an asynchronous read-back instead.
	 *
	 * @param RenderTarget target The target to read.
	 * @return RasterImage The pixels, top row first.
	 */
	fun readPixels(target: RenderTarget): RasterImage

	/**
	 * The backend's device / driver / version as one line - a startup diagnostic confirming which backend
	 * actually drives the viewport (a hardware driver vs a software rasterizer, say).
	 *
	 * @return String The description.
	 */
	fun describeBackend(): String
}

/**
 * One frame's recorded work.  Passes run in the order they are begun.
 *
 * A pass that reads what an earlier pass wrote must be separated from it by [barrier].
 */
internal interface FrameEncoder {
	/**
	 * Begins a render pass.  End it before beginning another - passes do not nest.
	 *
	 * @param RenderPassSpec spec The target, what to do with its existing contents, and the viewport.
	 * @return RenderPassEncoder The pass encoder.
	 */
	fun beginRenderPass(spec: RenderPassSpec): RenderPassEncoder

	/**
	 * Begins the deform-capture pass (glue pass 1).
	 *
	 * @param DeformCapturePipeline pipeline The capture pipeline.
	 * @param DeformedPositionStore store    Where the deformed positions land.
	 * @return DeformCapturePassEncoder The pass encoder.
	 */
	fun beginDeformCapturePass(pipeline: DeformCapturePipeline, store: DeformedPositionStore): DeformCapturePassEncoder

	/**
	 * Declares that work after this point reads [store] as written by work before it, so the backend
	 * orders the write against the read.
	 *
	 * A declared DEPENDENCY, not an instruction to stall - which is what lets each backend meet it its own
	 * way.  The GL device issues `glFinish`: the WSL d3d12/Mesa stack does not reliably order transform
	 * feedback's writes before a later texture read, so pass 2 sampled a half-written store and produced
	 * garbage welds while a parameter moved.  `glMemoryBarrier` cannot help - feedback writes are
	 * coherent-pipeline writes, outside its scope - so the full sync is the honest fix.  A Metal backend
	 * would implement this as a genuine no-op, since it tracks the dependency itself within a command
	 * buffer; that is correct, not a stub.
	 *
	 * The renderer calls it only on pose-change frames, so a static pose pays nothing.
	 *
	 * @param DeformedPositionStore store The store written before and read after.
	 */
	fun barrier(store: DeformedPositionStore)

	/** Submits the frame. */
	fun endFrame()
}

/**
 * What a render pass does with its target's existing contents on entry.
 *
 * GL needs none of this and ignores it; it is stated because a tile-based GPU cannot infer it, and
 * because it is genuinely free information the renderer already has.  The grid is an opaque full-screen
 * fill that both clears and paints, so its pass is [DontCare] - and on a tile-based GPU that means the
 * tile is never fetched at all.
 */
internal enum class LoadAction {
	/** Discard and fill with the clear colour. */
	Clear,

	/** Preserve: the pass reads what was already there. */
	Load,

	/** The pass overwrites every pixel, so the previous contents need not be fetched. */
	DontCare,
}

/** Whether a pass's results are kept.  [DontCare] lets a tile-based GPU skip the write-back. */
internal enum class StoreAction {
	/** Keep the results. */
	Store,

	/** Discard them. */
	DontCare,
}

/**
 * One render pass.
 *
 * Every field is explicit because a pass that discovered its own target could not exist on a backend with
 * no bound-framebuffer concept - and because, even on GL, discovering it from ambient state hid a real
 * coupling between the renderer and whoever bound the framebuffer first.
 *
 * @property RenderTarget colorTarget    The surface this pass writes.
 * @property LoadAction   loadAction     What to do with its existing contents on entry.
 * @property StoreAction  storeAction    Whether to keep the results.
 * @property Float        clearRed       Clear red;   read only when [loadAction] is [LoadAction.Clear].
 * @property Float        clearGreen     Clear green; read only when [loadAction] is [LoadAction.Clear].
 * @property Float        clearBlue      Clear blue;  read only when [loadAction] is [LoadAction.Clear].
 * @property Float        clearAlpha     Clear alpha; read only when [loadAction] is [LoadAction.Clear].
 * @property Int          viewportWidth  The viewport width in pixels.
 * @property Int          viewportHeight The viewport height in pixels.
 */
internal data class RenderPassSpec(
	val colorTarget: RenderTarget,
	val loadAction: LoadAction,
	val viewportWidth: Int,
	val viewportHeight: Int,
	val storeAction: StoreAction = StoreAction.Store,
	val clearRed: Float = 0f,
	val clearGreen: Float = 0f,
	val clearBlue: Float = 0f,
	val clearAlpha: Float = 0f,
)

/** Records draws into one render pass. */
internal interface RenderPassEncoder {
	/**
	 * Binds the pipeline subsequent draws use.  Blend and shader stages are fixed by it.
	 *
	 * @param RenderPipeline pipeline The pipeline.
	 */
	fun setPipeline(pipeline: RenderPipeline)

	/**
	 * Sets the pass-wide world→NDC affine and viewport size.
	 *
	 * @param WorldToNdc worldToNdc     The camera affine.
	 * @param Int        viewportWidth  The viewport width, for the mask's screen-space lookup.
	 * @param Int        viewportHeight The viewport height.
	 */
	fun setCamera(worldToNdc: WorldToNdc, viewportWidth: Int, viewportHeight: Int)

	/**
	 * Draws one deforming art mesh.
	 *
	 * @param GpuMesh          mesh      The mesh.
	 * @param DeformUniforms   deform    Its per-pose deform inputs (marshal, do not retain).
	 * @param FragmentUniforms fragment  Its appearance (marshal, do not retain).
	 * @param DrawTextures     textures  The textures it samples.
	 */
	fun drawPuppetMesh(mesh: GpuMesh, deform: DeformUniforms, fragment: FragmentUniforms, textures: DrawTextures)

	/**
	 * Draws one glue art mesh, welding against pass-1's positions.
	 *
	 * @param GpuMesh                mesh             The mesh.
	 * @param DeformedPositionStore  store            The shared pass-1 positions.
	 * @param Int                    baseVertexOffset This mesh's first vertex index in the store.
	 * @param FloatArray             glueIntensities  Per-glue weld intensity, by glue index.
	 * @param FragmentUniforms       fragment         Its appearance (marshal, do not retain).
	 * @param DrawTextures           textures         The textures it samples.
	 */
	fun drawGlueMesh(
		mesh: GpuMesh,
		store: DeformedPositionStore,
		baseVertexOffset: Int,
		glueIntensities: FloatArray,
		fragment: FragmentUniforms,
		textures: DrawTextures,
	)

	/**
	 * Draws the atlas-page underlay quad.
	 *
	 * @param GpuTexture       atlas      The page.
	 * @param Float            pageWidth  The page width in texels.
	 * @param Float            pageHeight The page height in texels.
	 * @param FragmentUniforms fragment   Its appearance.
	 */
	fun drawAtlasPage(atlas: GpuTexture, pageWidth: Float, pageHeight: Float, fragment: FragmentUniforms)

	/**
	 * Fills the target with the grid backdrop.
	 *
	 * @param GridUniforms uniforms The grid's inputs.
	 */
	fun drawGrid(uniforms: GridUniforms)

	/**
	 * Draws one world-origin axis line.
	 *
	 * @param AxisLineUniforms uniforms The line's inputs.
	 */
	fun drawAxisLine(uniforms: AxisLineUniforms)

	/** Ends the pass. */
	fun end()
}

/** Records the pass-1 deform capture. */
internal interface DeformCapturePassEncoder {
	/**
	 * Evaluates the deform for every vertex of [mesh] and writes each result into the store at
	 * [destinationVertexOffset] plus the vertex's own index.  Draws nothing.
	 *
	 * The name describes the EFFECT, not the mechanism, which is what lets the mechanism change without
	 * touching the renderer: the GL family runs it as transform feedback today and may become a
	 * point-scatter render once the store is a 2D texture (a texture cannot be a feedback target); Metal
	 * has no transform feedback at all and would use a compute kernel.  None of that reaches here.
	 *
	 * @param GpuMesh         mesh                   The mesh to deform.
	 * @param DeformUniforms  deform                 Its per-pose deform inputs (marshal, do not retain).
	 * @param DrawTextures    textures               Its delta and warp control-point textures.
	 * @param Int             destinationVertexOffset This mesh's base index in the store.
	 * @param Int             vertexCount            How many vertices to capture.
	 */
	fun captureDeformedPositions(
		mesh: GpuMesh,
		deform: DeformUniforms,
		textures: DrawTextures,
		destinationVertexOffset: Int,
		vertexCount: Int,
	)

	/** Ends the pass. */
	fun end()
}
