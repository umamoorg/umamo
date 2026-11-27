package org.umamo.render.puppet

import org.umamo.render.device.RecordedTexture
import org.umamo.render.device.RecordingRenderDevice
import org.umamo.render.device.TextureWrap
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame

/**
 * The art upload fits the device: art within the texture limit uploads as it is, and art past it uploads
 * scaled down, aspect kept, so it still draws rather than failing to upload.
 */
class ArtTexturesTest {
	@Test
	fun artWithinTheLimitUploadsAsItIs() {
		val pixels = ByteArray(4 * 4 * 4)
		val texture = assertIs<RecordedTexture>(RecordingRenderDevice(maxTextureEdge = 4).createArtTexture(4, 4, pixels, TextureWrap.ClampToTransparentBorder))

		assertEquals(4 to 4, texture.width to texture.height)
		assertSame(pixels, texture.pixels, "the art's own pixels, not a copy")
		assertEquals(TextureWrap.ClampToTransparentBorder, texture.wrap)
	}

	@Test
	fun artPastTheLimitUploadsScaledToFit() {
		val texture = assertIs<RecordedTexture>(RecordingRenderDevice(maxTextureEdge = 4).createArtTexture(16, 8, ByteArray(16 * 8 * 4) { 0xFF.toByte() }, TextureWrap.ClampToTransparentBorder))

		assertEquals(4 to 2, texture.width to texture.height)
		assertEquals(4 * 2 * 4, texture.pixels?.size)
		assertEquals(TextureWrap.ClampToTransparentBorder, texture.wrap)
	}
}