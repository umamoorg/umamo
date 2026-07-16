package org.umamo.render.gles

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
import org.umamo.render.device.TextureFilter
import org.umamo.render.device.TextureFormat

/**
 * The Android GLES 3.0 [RenderDevice] - a STUB marking the port's entry point; nothing is implemented.
 *
 * The port is a near-transliteration of the desktop `GlRenderDevice` (jvmMain, `org.umamo.render.gl`):
 * the calls are the same, the shared GLSL comes from `org.umamo.render.glsl` with
 * `GlslDialect.Es300`, and `PuppetRenderer` needs no changes at all.  The mechanical difference is
 * binding style - LWJGL statics returning values vs `android.opengl.GLES30`'s out-parameter form
 * (`glGenTextures(1, ids, 0)` instead of `glGenTextures()`).
 *
 * The ONE real divergence is [createDeformedPositionStore].  The desktop device backs the glue store
 * with a texture buffer object, and TBOs are GLES 3.2 - the baseline here is 3.0.  The 3.0-clean
 * route (TODO.md Claude Note § Android GLES renderer backend, option (b)):
 *  - Store: a regular RG32F 2D texture, indexed `(index % width, index / width)` via texelFetch.
 *  - Capture: transform feedback IS core in ES 3.0 but can only target a buffer, so pass 1 becomes
 *    TF into a buffer, then buffer → texture via a GL_PIXEL_UNPACK_BUFFER bind + glTexSubImage2D
 *    (both core 3.0).  Do NOT reach for the point-scatter alternative here: rendering INTO an RG32F
 *    target needs EXT_color_buffer_float, which is not core until 3.2 either.
 *  - Shader: `glueVertexShader(Es300)` still emits `samplerBuffer` and must gain the 2D-texelFetch
 *    variant as part of this port - its `@warning` says so.
 *
 * Everything else - pipelines keyed by `PipelinePurpose`, the pass encoders, `barrier` as glFinish,
 * `readPixels` flipping GL's bottom-up read to the API's top-first contract - carries over as is.
 * Validate against the same oracles the desktop device passes: `GpuDeformValidationTest` and
 * `GpuGlueValidationTest` are the correctness bar, run on-device or on an emulator.
 *
 * Android GLES 3.0 デバイスのスタブ。デスクトップ GL デバイスの移植入口。未実装。
 */
class GlesRenderDevice : RenderDevice {
	override fun createTexture(width: Int, height: Int, format: TextureFormat, filter: TextureFilter, pixels: ByteArray?): GpuTexture =
		TODO("GLES port: transliterate GlRenderDevice.createTexture (GLES30.glGenTextures out-param form)")

	override fun createFloatTexture(width: Int, height: Int, filter: TextureFilter, texels: FloatArray): GpuTexture =
		TODO("GLES port: transliterate GlRenderDevice.createFloatTexture")

	override fun updateFloatTexture(texture: GpuTexture, width: Int, height: Int, texels: FloatArray): Unit =
		TODO("GLES port: transliterate GlRenderDevice.updateFloatTexture (keep the once-per-pose contract)")

	override fun createMesh(spec: MeshSpec): GpuMesh = TODO("GLES port: transliterate GlRenderDevice.createMesh")

	override fun updateMeshPositions(mesh: GpuMesh, restPositions: FloatArray): Unit =
		TODO("GLES port: transliterate GlRenderDevice.updateMeshPositions")

	override fun updateMeshUvs(mesh: GpuMesh, uvs: FloatArray): Unit = TODO("GLES port: transliterate GlRenderDevice.updateMeshUvs")

	override fun createRenderTarget(spec: RenderTargetSpec): RenderTarget =
		TODO("GLES port: transliterate GlRenderDevice.createRenderTarget")

	override fun createDeformedPositionStore(vertexCapacity: Int): DeformedPositionStore =
		TODO("GLES port: THE divergence - RG32F 2D texture store + TF-to-buffer + PBO copy; see the class docblock")

	override fun createRenderPipeline(spec: RenderPipelineSpec): RenderPipeline =
		TODO("GLES port: link from org.umamo.render.glsl with GlslDialect.Es300")

	override fun createDeformCapturePipeline(): DeformCapturePipeline =
		TODO("GLES port: TF program (core in ES 3.0), capture into the store's staging buffer")

	override fun destroyTexture(texture: GpuTexture): Unit = TODO("GLES port")

	override fun destroyMesh(mesh: GpuMesh): Unit = TODO("GLES port")

	override fun destroyRenderTarget(target: RenderTarget): Unit = TODO("GLES port")

	override fun beginFrame(): FrameEncoder = TODO("GLES port: transliterate GlFrameEncoder")

	override fun resolve(source: RenderTarget, destination: RenderTarget): Unit =
		TODO("GLES port: glBlitFramebuffer with GL_LINEAR - core in ES 3.0, same as desktop")

	override fun beginReadback(target: RenderTarget): ReadbackTicket =
		TODO("GLES port: PBO + fence, same as desktop - GL_PIXEL_PACK_BUFFER and fence sync are core in ES 3.0")

	override fun pollReadback(ticket: ReadbackTicket): RasterImage? =
		TODO("GLES port: clientWaitSync(0) + glMapBufferRange (ES has no glMapBuffer; use the Range form)")

	override fun cancelReadback(ticket: ReadbackTicket): Unit = TODO("GLES port")

	override fun readPixels(target: RenderTarget): RasterImage =
		TODO("GLES port: glReadPixels + flipRowsVertically - GLES reads bottom-up like GL; the API contract is top-first")

	override fun describeBackend(): String = TODO("GLES port: GLES20.glGetString(GL_RENDERER / GL_VERSION / GL_VENDOR)")
}
