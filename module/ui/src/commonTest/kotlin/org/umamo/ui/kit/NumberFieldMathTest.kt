package org.umamo.ui.kit

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pure numeric-field math and the stacked-corner helper: the min-max fill fraction (bounded vs
 * unbounded), display / storage rounding, fixed-decimal formatting, the drag-scrub value mapping, and the
 * stack-position corner shaping the vertical numeric-field stack and horizontal ButtonGroup share.
 */
class NumberFieldMathTest {
	@Test
	fun fillFractionIsThePositionInABoundedRangeElseNull() {
		assertEquals(0.5f, numberFieldFillFraction(5f, 0f, 10f))
		assertEquals(0f, numberFieldFillFraction(-3f, 0f, 10f))
		assertEquals(1f, numberFieldFillFraction(99f, 0f, 10f))
		// Unbounded (infinite endpoint) or a degenerate range draws no fill.
		assertNull(numberFieldFillFraction(5f, 0f, Float.POSITIVE_INFINITY))
		assertNull(numberFieldFillFraction(5f, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY))
		assertNull(numberFieldFillFraction(5f, 10f, 10f))
	}

	@Test
	fun roundToDecimalsRoundsAndCaps() {
		assertEquals(2f, roundToDecimals(1.6f, 0))
		assertEquals(1.5f, roundToDecimals(1.49f, 1))
		assertEquals(1.23f, roundToDecimals(1.2345f, 2))
		// Beyond the Float storage cap the extra places are dropped (no more than NUMBER_FIELD_STORAGE_DECIMALS).
		val capped = roundToDecimals(1.123456789f, 12)
		assertEquals(roundToDecimals(1.123456789f, NUMBER_FIELD_STORAGE_DECIMALS), capped)
	}

	@Test
	fun formatDecimalsPadsToFixedWidth() {
		assertEquals("1920", formatDecimals(1920f, 0))
		assertEquals("0.50", formatDecimals(0.5f, 2))
		assertEquals("1.20", formatDecimals(1.2f, 2))
		assertEquals("-3.5", formatDecimals(-3.5f, 1))
		assertEquals("0.000", formatDecimals(0f, 3))
	}

	@Test
	fun scrubValueMapsDeltaToStepsAndClamps() {
		// One step per NUMBER_FIELD_SCRUB_PIXELS_PER_STEP pixels; rightward increases.
		val tenNotches = NUMBER_FIELD_SCRUB_PIXELS_PER_STEP * 10f
		assertEquals(10f, scrubValue(0f, tenNotches, 1f, -100f..100f))
		assertEquals(-10f, scrubValue(0f, -tenNotches, 1f, -100f..100f))
		// Clamped to the range.
		assertEquals(5f, scrubValue(0f, tenNotches * 100f, 1f, 0f..5f))
	}

	@Test
	fun stackPositionResolvesFromIndexAndCount() {
		assertEquals(StackPosition.Single, stackPositionOf(0, 1))
		assertEquals(StackPosition.First, stackPositionOf(0, 3))
		assertEquals(StackPosition.Middle, stackPositionOf(1, 3))
		assertEquals(StackPosition.Last, stackPositionOf(2, 3))
	}

	@Test
	fun stackedShapeSquaresTheSharedEdges() {
		val group = RoundedCornerShape(4.dp)

		// A standalone control keeps the full rounding; a middle control is fully square.
		assertSame(group, stackedShape(group, StackPosition.Single, StackAxis.Vertical))
		assertSame(RectangleShape, stackedShape(group, StackPosition.Middle, StackAxis.Vertical))

		// A vertical run squares the bottom of the first item and the top of the last.
		assertEquals(
			group.copy(bottomStart = ZeroCornerSize, bottomEnd = ZeroCornerSize),
			stackedShape(group, StackPosition.First, StackAxis.Vertical),
		)
		assertEquals(
			group.copy(topStart = ZeroCornerSize, topEnd = ZeroCornerSize),
			stackedShape(group, StackPosition.Last, StackAxis.Vertical),
		)

		// A horizontal run squares the trailing edge of the first item instead.
		assertEquals(
			group.copy(topEnd = ZeroCornerSize, bottomEnd = ZeroCornerSize),
			stackedShape(group, StackPosition.First, StackAxis.Horizontal),
		)

		// The First and Last shapes genuinely differ from the standalone rounding.
		assertTrue(stackedShape(group, StackPosition.First, StackAxis.Vertical) != group)
	}

	/**
	 * A scrub maps the TOTAL pointer delta onto a FIXED base, so the caller must latch the value the drag
	 * started from rather than re-reading the live one.
	 *
	 * This is the contract `NumberFieldCore` has to honour, and honouring it stopped being automatic once
	 * fields gained a live preview: the property rows feed each previewed value straight back in, so the
	 * live value moves during the drag.  Passing that moving value as the base compounds the movement -
	 * the scrub accelerates, and reversing direction spends its first stretch unwinding the drift.
	 */
	@Test
	fun scrubbingIsLinearInTheTotalDeltaFromAFixedBase() {
		val range = 0f..1000f
		val base = 100f
		// Six pixels per step, so 60px of total travel is ten steps whatever route the pointer took.
		assertEquals(110f, scrubValue(base, 60f, step = 1f, range = range), 1e-4f)
		assertEquals(120f, scrubValue(base, 120f, step = 1f, range = range), 1e-4f)
		// Returning the pointer to where it started returns the value to where it started.
		assertEquals(base, scrubValue(base, 0f, step = 1f, range = range), 1e-4f)

		// Feeding each result back in as the next base is what the bug did: the same 120px of travel,
		// reported in two frames, lands somewhere else entirely.
		val compounded = scrubValue(scrubValue(base, 60f, step = 1f, range = range), 120f, step = 1f, range = range)
		assertEquals(130f, compounded, 1e-4f, "a drifting base overshoots - which is why the base is latched")
	}
}