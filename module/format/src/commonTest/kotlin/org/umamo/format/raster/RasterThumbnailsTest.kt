package org.umamo.format.raster

import org.umamo.format.art.LayerBounds
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * The thumbnail fit every CMO3 icon takes: the longer edge fills the square, the aspect holds, the
 * art centers on the shorter axis, and the averaging runs in premultiplied space so an alpha edge
 * keeps its color.
 */
class RasterThumbnailsTest {
	private fun opaque(width: Int, height: Int, red: Int, green: Int, blue: Int): RasterImage =
		RasterImage(
			width,
			height,
			ByteArray(width * height * 4) { index ->
				when (index % 4) {
					0 -> red.toByte()
					1 -> green.toByte()
					2 -> blue.toByte()
					else -> 0xFF.toByte()
				}
			},
		)

	private fun pixel(image: RasterImage, x: Int, y: Int): List<Int> {
		val offset = (y * image.width + x) * 4
		return (0 until 4).map { channel -> image.rgba[offset + channel].toInt() and 0xFF }
	}

	@Test
	fun aWideRasterFillsTheWidthAndCentersVertically() {
		val fitted = opaque(4, 2, 200, 100, 50).fittedInto(8)
		assertEquals(8 to 8, fitted.width to fitted.height)
		for (y in 0 until 8) {
			for (x in 0 until 8) {
				val expected = if (y in 2..5) listOf(200, 100, 50, 255) else listOf(0, 0, 0, 0)
				assertEquals(expected, pixel(fitted, x, y), "pixel ($x, $y)")
			}
		}
	}

	@Test
	fun aTallRasterFillsTheHeightAndCentersHorizontally() {
		val fitted = opaque(1, 3, 10, 20, 30).fittedInto(6)
		for (y in 0 until 6) {
			for (x in 0 until 6) {
				val expected = if (x in 2..3) listOf(10, 20, 30, 255) else listOf(0, 0, 0, 0)
				assertEquals(expected, pixel(fitted, x, y), "pixel ($x, $y)")
			}
		}
	}

	@Test
	fun averagingRunsInPremultipliedSpace() {
		// One opaque red pixel and three transparent black ones average to quarter-alpha RED, not a
		// quarter-alpha dark red: the transparent pixels contribute coverage, never color.
		val rgba = ByteArray(2 * 2 * 4)
		rgba[0] = 0xFF.toByte()
		rgba[3] = 0xFF.toByte()
		val fitted = RasterImage(2, 2, rgba).fittedInto(1)
		assertEquals(listOf(255, 0, 0, 64), pixel(fitted, 0, 0))
	}

	@Test
	fun aSmallRasterScalesUp() {
		val fitted = opaque(1, 1, 5, 6, 7).fittedInto(4)
		for (y in 0 until 4) {
			for (x in 0 until 4) {
				assertEquals(listOf(5, 6, 7, 255), pixel(fitted, x, y), "pixel ($x, $y)")
			}
		}
	}

	@Test
	fun anEmptyRasterYieldsTheBlankSquare() {
		val fitted = RasterImage(0, 0, ByteArray(0)).fittedInto(3)
		assertEquals(3 to 3, fitted.width to fitted.height)
		assertTrue(fitted.rgba.all { byte -> byte == 0.toByte() })
	}

	@Test
	fun croppingCoversAndCutsTheRect() {
		val source = opaque(3, 2, 1, 2, 3)
		assertSame(source, source.cropped(LayerBounds(0, 0, 3, 2)), "full bounds return the raster itself")
		val corner = source.cropped(LayerBounds(2, 1, 1, 1))
		assertEquals(1 to 1, corner.width to corner.height)
		assertContentEquals(byteArrayOf(1, 2, 3, 0xFF.toByte()), corner.rgba)
	}
}