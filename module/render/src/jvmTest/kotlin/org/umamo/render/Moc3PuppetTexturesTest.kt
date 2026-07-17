package org.umamo.render

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Contract checks for [buildPuppetTextures]: pages decode in order, and the two failure modes both
 * yield null so the loader can fail the import cleanly - an undecodable page, and any drawable
 * whose page index falls outside the page list (which would otherwise crash the renderer's direct
 * atlas indexing at first frame).  Synthetic pixels only - no corpus dependency.
 */
class Moc3PuppetTexturesTest {
	private val onePixelPng: ByteArray =
		PngCodec.write(RasterImage(width = 1, height = 1, rgba = byteArrayOf(0x10, 0x20, 0x30, -1)))

	@Test
	fun decodesPagesAndKeepsTheIndexMap() {
		val textures = buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to 0))
		assertNotNull(textures)
		assertEquals(1, textures.atlases.size, "decoded page count")
		assertEquals(1, textures.atlases[0].width, "page width")
		assertEquals(0, textures.atlasIndexByDrawableId["ArtMesh1"], "page index for the drawable")
	}

	@Test
	fun undecodablePageFailsTheBuild() {
		assertNull(buildPuppetTextures(listOf("not a png".encodeToByteArray()), mapOf("ArtMesh1" to 0)))
	}

	@Test
	fun outOfRangePageIndexFailsTheBuild() {
		assertNull(buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to 1)), "index past the page list")
		assertNull(buildPuppetTextures(listOf(onePixelPng), mapOf("ArtMesh1" to -1)), "negative index")
	}
}
