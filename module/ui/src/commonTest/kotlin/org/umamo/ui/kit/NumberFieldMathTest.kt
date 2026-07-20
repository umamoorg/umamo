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
}
