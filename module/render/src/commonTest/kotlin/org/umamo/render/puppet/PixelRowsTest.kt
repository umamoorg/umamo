package org.umamo.render.puppet

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins [flipRowsVertically] - the bottom-up-read-back to top-first conversion.  Tiny, but it is the one
 * place the framebuffer Y convention is decided, and getting it backwards silently mirrors every dump.
 */
class PixelRowsTest {
	/** One opaque RGBA pixel, so a row's identity is readable at the call site. */
	private fun pixel(red: Int, green: Int, blue: Int): ByteArray = byteArrayOf(red.toByte(), green.toByte(), blue.toByte(), 255.toByte())

	private val redRow = pixel(255, 0, 0)
	private val greenRow = pixel(0, 255, 0)

	@Test
	fun flipRowsVerticallyReversesRowOrder() {
		val flipped = flipRowsVertically(redRow + greenRow, width = 1, height = 2)
		assertContentEquals(greenRow + redRow, flipped, "the bottom (green) row must come first after the flip")
	}

	@Test
	fun flipRowsVerticallyKeepsChannelOrderWithinAPixel() {
		// Row-wise, not per-pixel: RGBA must survive untouched, only the rows move.
		val singleRow = pixel(1, 2, 3) + pixel(4, 5, 6)
		assertContentEquals(singleRow, flipRowsVertically(singleRow, width = 2, height = 1), "a single row is unchanged, channels included")
	}

	@Test
	fun flipRowsVerticallyIsItsOwnInverse() {
		val original = redRow + greenRow
		assertContentEquals(original, flipRowsVertically(flipRowsVertically(original, 1, 2), 1, 2), "flipping twice returns the original")
	}

	@Test
	fun flipRowsVerticallyMovesWholeRowsNotPixels() {
		// Rows are [A B] then [C D]; the flip must yield [C D] then [A B], NOT a reversal of the whole
		// buffer (which would give [D C] [B A]) - the classic way to get this wrong.
		val topRow = pixel(10, 10, 10) + pixel(20, 20, 20)
		val bottomRow = pixel(30, 30, 30) + pixel(40, 40, 40)
		val flipped = flipRowsVertically(topRow + bottomRow, width = 2, height = 2)
		assertContentEquals(bottomRow + topRow, flipped, "whole rows swap, pixel order within each row intact")
		assertEquals(30, flipped[0], "the bottom row's first pixel leads after the flip")
		assertEquals(40, flipped[4], "and its second pixel stays second within that row")
	}
}
