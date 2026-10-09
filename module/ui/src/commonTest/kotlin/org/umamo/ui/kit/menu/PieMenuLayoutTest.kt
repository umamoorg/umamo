package org.umamo.ui.kit.menu

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * The pie's chip anchoring and center clamp: each chip touches the ring with its inner edge (Blender's
 * pie layout rule), so a full eight-entry ring of long labels overlaps neither itself nor the title,
 * and the center moves only as far as the measured pie needs.
 */
class PieMenuLayoutTest {
	@Test
	fun eachChipTouchesTheRingWithItsInnerEdge() {
		val west = chipRect(slotIndex = 0, chipWidth = 200)
		val east = chipRect(slotIndex = 1, chipWidth = 200)
		val south = chipRect(slotIndex = 2, chipWidth = 200)
		val north = chipRect(slotIndex = 3, chipWidth = 200)
		val northWest = chipRect(slotIndex = 4, chipWidth = 200)
		val southEast = chipRect(slotIndex = 7, chipWidth = 200)
		val diagonalReach = RING_RADIUS * 0.70710677f

		assertEquals(-RING_RADIUS, west.right, TOLERANCE, "West ends at its ring point")
		assertEquals(0f, west.center.y, TOLERANCE, "and centers on it vertically")
		assertEquals(RING_RADIUS, east.left, TOLERANCE, "East starts at its ring point")
		assertEquals(-RING_RADIUS, north.bottom, TOLERANCE, "North sits above its ring point")
		assertEquals(0f, north.center.x, TOLERANCE, "and centers on it horizontally")
		assertEquals(RING_RADIUS, south.top, TOLERANCE, "South sits below its ring point")
		assertEquals(-diagonalReach, northWest.right, TOLERANCE, "a left diagonal ends at its ring point")
		assertEquals(-diagonalReach, northWest.center.y, TOLERANCE, "and centers on it vertically")
		assertEquals(diagonalReach, southEast.left, TOLERANCE, "a right diagonal starts at its ring point")
	}

	@Test
	fun aFullRingOfLongLabelsOverlapsNeitherItselfNorTheTitle() {
		// Chip widths in the range the snap pie's English labels measure, the widest on a diagonal.
		val chipWidths = intArrayOf(200, 180, 170, 160, 170, 190, 280, 180)
		val chipRects = chipWidths.mapIndexed { slotIndex, chipWidth -> chipRect(slotIndex, chipWidth) }
		for (firstSlot in chipRects.indices) {
			for (secondSlot in firstSlot + 1 until chipRects.size) {
				assertFalse(
					chipRects[firstSlot].overlaps(chipRects[secondSlot]),
					"slots $firstSlot and $secondSlot overlap: ${chipRects[firstSlot]} / ${chipRects[secondSlot]}",
				)
			}
		}
		for ((slotIndex, chipRect) in chipRects.withIndex()) {
			assertFalse(chipRect.overlaps(TITLE_AREA), "slot $slotIndex covers the title")
		}
	}

	@Test
	fun theCenterMovesOnlyAsFarAsTheMeasuredPieNeeds() {
		assertEquals(150f, clampPieCenterAxis(requested = 10f, extentMin = -150f, extentMax = 150f, bounds = 1000f), "a narrow pie clears the edge by its own reach")
		assertEquals(320f, clampPieCenterAxis(requested = 10f, extentMin = -320f, extentMax = 250f, bounds = 1000f), "a wider pie by its wider reach")
		assertEquals(500f, clampPieCenterAxis(requested = 500f, extentMin = -320f, extentMax = 250f, bounds = 1000f), "a pie that fits stays put")
		assertEquals(750f, clampPieCenterAxis(requested = 990f, extentMin = -320f, extentMax = 250f, bounds = 1000f), "the far edge clamps by the far reach")
		// 570 wide in 400: the extent -320..250 centers, so the center lands at 200 - (-320 + 250) / 2.
		assertEquals(235f, clampPieCenterAxis(requested = 10f, extentMin = -320f, extentMax = 250f, bounds = 400f), "a pie larger than the overlay centers its extent")
	}

	/**
	 * The rectangle one chip occupies around a pie centered on the origin.
	 *
	 * @param Int slotIndex The chip's slot.
	 * @param Int chipWidth The chip's width in pixels.
	 * @return Rect The chip's bounds relative to the pie center.
	 */
	private fun chipRect(slotIndex: Int, chipWidth: Int): Rect =
		Rect(
			offset = pieChipOffset(slotIndex, chipWidth, CHIP_HEIGHT, RING_RADIUS),
			size = Size(chipWidth.toFloat(), CHIP_HEIGHT.toFloat()),
		)

	private companion object {
		/** The pie's ring radius at density 1. */
		const val RING_RADIUS = 96f

		/** A chip's height at density 1: a 16 px icon row plus its 5 px vertical padding each side. */
		const val CHIP_HEIGHT = 26

		/** The tolerance for float placement comparisons. */
		const val TOLERANCE = 0.01f

		/** A generous title box at the center, wide enough for a long localized pie name. */
		val TITLE_AREA = Rect(left = -90f, top = -10f, right = 90f, bottom = 10f)
	}
}