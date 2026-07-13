package org.umamo.ui.viewport

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The cursor-wrap geometry that keeps a modal G / S / R gesture alive at the viewport edge: a pointer that
 * reaches an edge teleports to the opposite side, and an interior pointer is left alone.  Pure math, so it
 * is tested without Compose input; the anchor-compensation and Robot call are exercised interactively.
 */
class CursorWrapTest {
	private val size = IntSize(400, 300)
	private val margin = 8f
	private val inset = 32f

	@Test
	fun interiorPointerDoesNotWrap() {
		assertNull(computeCursorWrap(Offset(200f, 150f), size, margin, inset))
	}

	@Test
	fun leftEdgeWrapsToTheRightInterior() {
		assertEquals(Offset(400f - inset, 150f), computeCursorWrap(Offset(4f, 150f), size, margin, inset))
	}

	@Test
	fun rightEdgeWrapsToTheLeftInterior() {
		assertEquals(Offset(inset, 150f), computeCursorWrap(Offset(398f, 150f), size, margin, inset))
	}

	@Test
	fun topEdgeWrapsToTheBottomInterior() {
		assertEquals(Offset(200f, 300f - inset), computeCursorWrap(Offset(200f, 5f), size, margin, inset))
	}

	@Test
	fun bottomEdgeWrapsToTheTopInterior() {
		assertEquals(Offset(200f, inset), computeCursorWrap(Offset(200f, 297f), size, margin, inset))
	}

	@Test
	fun cornerWrapsBothAxes() {
		assertEquals(Offset(400f - inset, 300f - inset), computeCursorWrap(Offset(2f, 2f), size, margin, inset))
	}

	@Test
	fun outOfBoundsStillWrapsAndClampsTheOtherAxis() {
		// A move that already left the area on x still wraps; the untouched y stays clamped in bounds.
		assertEquals(Offset(400f - inset, 150f), computeCursorWrap(Offset(-10f, 150f), size, margin, inset))
	}

	@Test
	fun tooSmallAnAxisDoesNotWrap() {
		// Width 30 <= inset + margin (40): wrapping would land back in the trigger zone, so it is skipped.
		val sliver = IntSize(30, 300)
		assertNull(computeCursorWrap(Offset(2f, 150f), sliver, margin, inset))
	}

	@Test
	fun wrapTargetLandsClearOfTheTriggerZone() {
		// The teleport point must not itself be within margin of an edge, or it would re-wrap forever.
		val wrapped = computeCursorWrap(Offset(1f, 1f), size, margin, inset)!!
		assertTrue(wrapped.x > margin && wrapped.x < size.width - margin)
		assertTrue(wrapped.y > margin && wrapped.y < size.height - margin)
	}
}
