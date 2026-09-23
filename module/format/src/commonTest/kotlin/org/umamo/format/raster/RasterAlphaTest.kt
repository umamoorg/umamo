package org.umamo.format.raster

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The read-back conversions: premultiplied framebuffer pixels to the straight alpha a PNG stores, and
 * the forced-opaque alpha of a render over a solid background.
 */
class RasterAlphaTest {
	/**
	 * Builds a one-row raster from RGBA quadruples.
	 *
	 * @param IntArray channels Four bytes per pixel, 0..255.
	 * @return RasterImage The raster.
	 */
	private fun row(vararg channels: Int): RasterImage = RasterImage(channels.size / 4, 1, ByteArray(channels.size) { index -> channels[index].toByte() })

	/**
	 * One pixel's channels as ints.
	 *
	 * @param RasterImage image       The raster.
	 * @param Int         pixelIndex  Which pixel of the row.
	 * @return List<Int> The four channels.
	 */
	private fun pixel(image: RasterImage, pixelIndex: Int): List<Int> = (0 until 4).map { channel -> image.rgba[pixelIndex * 4 + channel].toInt() and 0xFF }

	@Test
	fun opaquePixelsKeepTheirColor() {
		val straight = row(200, 100, 50, 255).premultipliedToStraight()
		assertEquals(listOf(200, 100, 50, 255), pixel(straight, 0))
	}

	@Test
	fun transparentPixelsBecomeTransparentBlack() {
		// Color with no coverage (an additive draw over nothing) has no straight form.
		val straight = row(40, 30, 20, 0).premultipliedToStraight()
		assertEquals(listOf(0, 0, 0, 0), pixel(straight, 0))
	}

	@Test
	fun partialAlphaDividesBackOutRoundedToNearest() {
		// 64 / 128 * 255 = 127.5 rounds to 128; 32 / 128 * 255 = 63.75 rounds to 64; 1 / 128 * 255 = 1.99 rounds to 2.
		val straight = row(64, 32, 1, 128).premultipliedToStraight()
		assertEquals(listOf(128, 64, 2, 128), pixel(straight, 0))
	}

	@Test
	fun colorPastItsAlphaClampsAtFullIntensity() {
		val straight = row(200, 10, 0, 100).premultipliedToStraight()
		assertEquals(listOf(255, 26, 0, 100), pixel(straight, 0))
	}

	@Test
	fun theConversionLeavesTheSourceUntouched() {
		val source = row(64, 32, 16, 128)
		source.premultipliedToStraight()
		assertEquals(listOf(64, 32, 16, 128), pixel(source, 0))
	}

	@Test
	fun opaqueAlphaSetsEveryAlphaAndKeepsColor() {
		val opaque = row(10, 20, 30, 0, 40, 50, 60, 128).withOpaqueAlpha()
		assertEquals(listOf(10, 20, 30, 255), pixel(opaque, 0))
		assertEquals(listOf(40, 50, 60, 255), pixel(opaque, 1))
	}
}