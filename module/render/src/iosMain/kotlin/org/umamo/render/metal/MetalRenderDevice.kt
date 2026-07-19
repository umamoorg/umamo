package org.umamo.render.metal

import org.umamo.format.raster.RasterImage
import org.umamo.render.device.DeformCapturePipeline
import org.umamo.render.device.DeformedPositionStore
import org.umamo.render.device.FrameEncoder
import org.umamo.render.device.GpuMesh
import org.umamo.render.device.GpuTexture
import org.umamo.render.device.MeshSpec
import org.umamo.render.device.ReadbackTicket
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderPipeline
import org.umamo.render.device.RenderPipelineSpec
import org.umamo.render.device.RenderTarget
import org.umamo.render.device.RenderTargetSpec
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat

/**
 * The iPadOS Metal [RenderDevice] - a STUB marking the port's entry point; nothing is implemented.
 *
 * Implementing this ONE interface is the whole port: `PuppetRenderer` and everything it stands on
 * (the deform eval, pose resolution, glue planning, model diff) are backend-neutral commonMain and
 * come along for free.  Kotlin/Native reaches Metal directly via `platform.Metal`; a Swift-side
 * implementation bridged through this interface works too.
 *
 * What is genuinely different from the GL device, decided up front so the port does not rediscover it:
 *  - Shaders are MSL, keyed by [RenderPipelineSpec.purpose] - the GLSL in `org.umamo.render.glsl` is a
 *    GL-family asset and none of it is reused.  The fidelity-critical piece is `deformWorld`
 *    (DeformGlsl.kt): ~200 lines whose `warpExtrap` lattice extrapolation silently breaks rig fidelity
 *    if mistranslated.  Build the MSL equivalent of `GpuDeformValidationTest` (the GPU-vs-CPU oracle;
 *    the CPU side is commonMain and already available here) BEFORE trusting the translation - and the
 *    glue equivalent of `GpuGlueValidationTest` alongside it.
 *  - [createDeformCapturePipeline] / the capture pass: Metal has no transform feedback - a compute
 *    kernel reads base positions + the delta texture and writes the store, which is naturally a
 *    `device float2*` buffer here rather than any texture.
 *  - [FrameEncoder.barrier] is a GENUINE no-op: Metal orders encoder-to-encoder resource dependencies
 *    within a command buffer itself.  Correct, not a stub - do not add a wait.
 *  - Blend is baked into the MTLRenderPipelineState per [RenderPipelineSpec] (the API is already shaped
 *    for that); `LoadAction`/`StoreAction` map 1:1 onto MTLRenderPassDescriptor and are real wins on a
 *    tile-based GPU (the grid pass is DontCare - the tile is never fetched).
 *  - [readPixels] returns TOP row first per the API contract.  Metal's origin is already top-left, so
 *    unlike the GL device there is NO row flip here.
 *  - Two conventions inside the shaders are CONTENT, not backend, and must be kept verbatim in MSL:
 *    `deformWorld`'s final Y negation (the CMO3 Y-down → Umamo Y-up format convention) and the atlas
 *    page quad's V-flip (Y-up display vs top-first atlas rows).  "Correcting" either for Metal's
 *    texture origin inverts the rig or the page.
 *  - The mask pass and the main pass must share one screen-space convention (the fragment position the
 *    coverage lookup divides by viewport size) - self-consistent per backend, silently wrong if only
 *    one pass is flipped.  Worst on masked warp meshes, which draw twice.
 *  - The uniform structs ([org.umamo.render.device.DeformUniforms] etc.) are MUTABLE AND REUSED per
 *    draw: marshal every field before returning (`setVertexBytes` copies, so that is natural) and
 *    never retain the instance.
 *
 * iPadOS Metal デバイスのスタブ。ポートの入口。この 1 インターフェースの実装がポートの全て。未実装。
 */
class MetalRenderDevice : RenderDevice {
	override fun createTexture(width: Int, height: Int, format: TextureFormat, filter: TextureFilter, pixels: ByteArray?): GpuTexture =
		TODO("Metal port: MTLTexture (RGBA8Unorm / RG32Float), top-first upload needs no flip")

	override fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture =
		TODO("Metal port: RG32Float MTLTexture")

	override fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray): Unit =
		TODO("Metal port: replaceRegion in place (keep the once-per-pose contract)")

	override fun createMesh(spec: MeshSpec): GpuMesh =
		TODO("Metal port: MTLBuffers; vertex layout lives in the pipeline descriptor, not the mesh")

	override fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray): Unit = TODO("Metal port")

	override fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray): Unit = TODO("Metal port")

	override fun createRenderTarget(spec: RenderTargetSpec): RenderTarget =
		TODO("Metal port: MTLTexture with usage renderTarget (+ shaderRead when spec.sampled)")

	override fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore =
		TODO("Metal port: a device float2* MTLBuffer - no texture indirection needed here")

	override fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline =
		TODO("Metal port: MTLRenderPipelineState from the MSL library, keyed by spec.purpose, blend baked per spec.blend")

	override fun createDeformCapturePipeline(): DeformCapturePipeline =
		TODO("Metal port: a compute pipeline - Metal has no transform feedback; see the class docblock")

	override fun destroyTexture(texture: GpuTexture): Unit = TODO("Metal port")

	override fun destroyMesh(mesh: GpuMesh): Unit = TODO("Metal port")

	override fun destroyRenderTarget(target: RenderTarget): Unit = TODO("Metal port")

	override fun beginFrame(): FrameEncoder =
		TODO("Metal port: open a command buffer; passes are encoders; barrier() is a genuine no-op; endFrame commits")

	override fun resolve(source: RenderTarget, destination: RenderTarget, region: ScissorRect?): Unit =
		TODO(
			"Metal port: region == null is the scaled full-surface downscale (a full-screen textured draw or " +
				"MPSImageBilinearScale - NOT a blit encoder, which can neither filter nor scale); region != null is a " +
				"same-size sub-rect copy (the composite snapshot), for which a plain MTLBlitCommandEncoder copy is exact.",
		)

	override fun beginReadback(target: RenderTarget): ReadbackTicket =
		TODO("Metal port: blit target into a shared-storage staging buffer, addCompletedHandler flags the ticket done")

	override fun pollReadback(ticket: ReadbackTicket): RasterImage? =
		TODO("Metal port: drain the completed flag ON THE POLL, so results arrive on the render thread whatever thread the handler ran on; top-first already, no row flip")

	override fun cancelReadback(ticket: ReadbackTicket): Unit = TODO("Metal port")

	override fun readPixels(target: RenderTarget): RasterImage =
		TODO("Metal port: getBytes - already top-first, NO row flip (unlike the GL device)")

	override fun describeBackend(): String = TODO("Metal port: MTLDevice.name")
}
