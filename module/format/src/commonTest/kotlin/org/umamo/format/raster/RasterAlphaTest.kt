package org.umamo.format.raster

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame

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

	/**
	 * Converts a one-row raster in place and returns it, so a case reads as one expression.
	 *
	 * @param IntArray channels Four premultiplied bytes per pixel, 0..255.
	 * @return RasterImage The same raster, now straight alpha.
	 */
	private fun unpremultiplied(vararg channels: Int): RasterImage = row(*channels).also { image -> image.unpremultiplyInPlace() }

	/** A fully opaque pixel keeps its color: premultiplied and straight are the same there. */
	@Test
	fun opaquePixelsKeepTheirColor() {
		assertEquals(listOf(200, 100, 50, 255), pixel(unpremultiplied(200, 100, 50, 255), 0))
	}

	/** A pixel with no coverage becomes transparent black, whatever color was left in it. */
	@Test
	fun transparentPixelsBecomeTransparentBlack() {
		// Color with no coverage (an additive draw over nothing) has no straight form.
		assertEquals(listOf(0, 0, 0, 0), pixel(unpremultiplied(40, 30, 20, 0), 0))
	}

	/** Partial coverage divides the color back out by the alpha, rounded to nearest. */
	@Test
	fun partialAlphaDividesBackOutRoundedToNearest() {
		// 64 / 128 * 255 = 127.5 rounds to 128; 32 / 128 * 255 = 63.75 rounds to 64; 1 / 128 * 255 = 1.99 rounds to 2.
		assertEquals(listOf(128, 64, 2, 128), pixel(unpremultiplied(64, 32, 1, 128), 0))
	}

	/** A color brighter than its alpha allows clamps at full intensity instead of wrapping. */
	@Test
	fun colorPastItsAlphaClampsAtFullIntensity() {
		assertEquals(listOf(255, 26, 0, 100), pixel(unpremultiplied(200, 10, 0, 100), 0))
	}

	/** The conversion writes into the raster's own buffer rather than a copy. */
	@Test
	fun theConversionWritesIntoTheSameBuffer() {
		val image = row(64, 32, 16, 128)
		val buffer = image.rgba
		image.unpremultiplyInPlace()
		assertSame(buffer, image.rgba)
		assertEquals(listOf(128, 64, 32, 128), pixel(image, 0))
	}

	/** Forcing opaque alpha sets every alpha to 255 and leaves every color alone. */
	@Test
	fun opaqueAlphaSetsEveryAlphaAndKeepsColor() {
		val opaque = row(10, 20, 30, 0, 40, 50, 60, 128)
		opaque.setOpaqueAlphaInPlace()
		assertEquals(listOf(10, 20, 30, 255), pixel(opaque, 0))
		assertEquals(listOf(40, 50, 60, 255), pixel(opaque, 1))
	}
}