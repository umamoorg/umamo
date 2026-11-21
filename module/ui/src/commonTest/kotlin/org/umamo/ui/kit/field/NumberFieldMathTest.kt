package org.umamo.ui.kit.field

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.shape.ZeroCornerSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.StackAxis
import org.umamo.ui.kit.StackPosition
import org.umamo.ui.kit.stackPositionOf
import org.umamo.ui.kit.stackedShape
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/** The drag, in pixels, of one notch in these cases. */
private const val NOTCH_PX = 6f

/** The drag, in pixels, that crosses a whole range in these cases. */
private const val TRAVEL_PX = 200f

/**
 * Pins the pure numeric-field math and the stacked-corner helper: the min-max fill fraction (bounded vs
 * unbounded), display / storage rounding, fixed-decimal formatting, the drag-scrub value mapping, and the
 * stack-position corner shaping the vertical numeric-field stack and horizontal ButtonGroup share.
 */
class NumberFieldMathTest {
	/** A typed entry parses to its finite number, and NaN or an infinity is refused like any other non-number. */
	@Test
	fun aTypedEntryParsesOnlyToAFiniteNumber() {
		assertEquals(12.5f, parseTypedFloat(" 12.5 "))
		assertEquals(-3f, parseTypedFloat("-3"))
		assertNull(parseTypedFloat("NaN"))
		assertNull(parseTypedFloat("Infinity"))
		assertNull(parseTypedFloat("-Infinity"))
		assertNull(parseTypedFloat("twelve"))
		assertNull(parseTypedFloat(""))
	}

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
		// One step per notch; rightward increases.
		val perPixel = scrubValuePerPixel(-100f..100f, step = 1f, notchPx = NOTCH_PX, travelPx = null)
		val tenNotches = NOTCH_PX * 10f
		assertEquals(10f, scrubValue(0f, tenNotches, perPixel, -100f..100f), 1e-4f)
		assertEquals(-10f, scrubValue(0f, -tenNotches, perPixel, -100f..100f), 1e-4f)
		// Clamped to the range.
		assertEquals(5f, scrubValue(0f, tenNotches * 100f, perPixel, 0f..5f))
	}

	@Test
	fun scrubValueBringsAValueInByTheFieldsOwnRule() {
		val perPixel = scrubValuePerPixel(0f..8f, step = 1f, notchPx = NOTCH_PX, travelPx = null)
		val tenNotches = NOTCH_PX * 10f
		val wrap = { raw: Float -> 0f + (raw - 0f).mod(8f) }

		// Ten steps up from 3 on a 0..8 loop comes round to 5.
		assertEquals(5f, scrubValue(3f, tenNotches, perPixel, 0f..8f, wrap), 1e-4f)
		// Inside the range the rule changes nothing.
		assertEquals(4f, scrubValue(3f, NOTCH_PX, perPixel, 0f..8f, wrap), 1e-4f)
	}

	/** With a travel, a narrow range and a wide one cross end to end over the same drag, whatever their step. */
	@Test
	fun aTravelCrossesEveryRangeOverTheSameDrag() {
		val unit = 0f..1f
		val wide = -30f..30f
		val unitPerPixel = scrubValuePerPixel(unit, step = 1f, notchPx = NOTCH_PX, travelPx = TRAVEL_PX)
		val widePerPixel = scrubValuePerPixel(wide, step = 1f, notchPx = NOTCH_PX, travelPx = TRAVEL_PX)

		assertEquals(1f, scrubValue(0f, TRAVEL_PX, unitPerPixel, unit), 1e-4f)
		assertEquals(30f, scrubValue(-30f, TRAVEL_PX, widePerPixel, wide), 1e-4f)
		// Half the drag is half of each range.
		assertEquals(0.5f, scrubValue(0f, TRAVEL_PX / 2f, unitPerPixel, unit), 1e-4f)
		assertEquals(0f, scrubValue(-30f, TRAVEL_PX / 2f, widePerPixel, wide), 1e-4f)
	}

	/** A range with no finite span has nothing to cross, so a travel falls back to the notch. */
	@Test
	fun aRangeWithNoFiniteSpanScrubsByTheNotch() {
		val byNotch = 2f / NOTCH_PX

		assertEquals(byNotch, scrubValuePerPixel(0f..Float.POSITIVE_INFINITY, step = 2f, notchPx = NOTCH_PX, travelPx = TRAVEL_PX))
		assertEquals(
			byNotch,
			scrubValuePerPixel(Float.NEGATIVE_INFINITY..Float.POSITIVE_INFINITY, step = 2f, notchPx = NOTCH_PX, travelPx = TRAVEL_PX),
		)
		assertEquals(byNotch, scrubValuePerPixel(4f..4f, step = 2f, notchPx = NOTCH_PX, travelPx = TRAVEL_PX))
		// The empty range still holds the value at its one point.
		assertEquals(4f, scrubValue(4f, TRAVEL_PX, byNotch, 4f..4f))
	}

	/** Under a travel, a wrapping field's travel is one period: past an end it comes round from the other. */
	@Test
	fun aTravelPastAnEndWrapsByTheFieldsRule() {
		val unit = 0f..1f
		val wrap = { raw: Float -> raw.mod(1f) }
		val perPixel = scrubValuePerPixel(unit, step = 1f, notchPx = NOTCH_PX, travelPx = TRAVEL_PX)

		assertEquals(0.2f, scrubValue(0.8f, TRAVEL_PX * 0.4f, perPixel, unit, wrap), 1e-4f)
		assertEquals(0.8f, scrubValue(0.8f, TRAVEL_PX, perPixel, unit, wrap), 1e-4f, "one whole travel is one whole period")
	}

	/** The notch and the travel are measured in dp, so the same physical drag lands on the same value at any density. */
	@Test
	fun theSameDpDragLandsTheSameAtTwoDensities() {
		val range = -10f..10f
		val travelDp = 200f
		val dragDp = 50f

		for (density in listOf(1f, 2f)) {
			val withTravel = scrubValuePerPixel(range, step = 1f, notchPx = NOTCH_PX * density, travelPx = travelDp * density)
			assertEquals(-5f, scrubValue(-10f, dragDp * density, withTravel, range), 1e-4f, "with a travel at density $density")

			val byNotch = scrubValuePerPixel(range, step = 1f, notchPx = NOTCH_PX * density, travelPx = null)
			assertEquals(-5f, scrubValue(-10f, NOTCH_PX * 5f * density, byNotch, range), 1e-4f, "by the notch at density $density")
		}
	}

	/** A span's step is a tenth of it rounded down to 1, 2, or 5 of a decade, and never finer than the field keeps. */
	@Test
	fun theStepForASpanIsATenthRoundedDownToOneTwoOrFive() {
		assertEquals(0.1f, numberFieldStepForSpan(1f, decimals = 2))
		assertEquals(1f, numberFieldStepForSpan(10f, decimals = 2))
		assertEquals(2f, numberFieldStepForSpan(20f, decimals = 2))
		assertEquals(5f, numberFieldStepForSpan(60f, decimals = 2))
		assertEquals(20f, numberFieldStepForSpan(360f, decimals = 2))
		assertEquals(1000f, numberFieldStepForSpan(10_000f, decimals = 2))
		// A span too narrow for a tenth to show at two places steps by the last place.
		assertEquals(0.01f, numberFieldStepForSpan(0.05f, decimals = 2))
		assertEquals(0.01f, numberFieldStepForSpan(0f, decimals = 2))
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
		val perPixel = scrubValuePerPixel(range, step = 1f, notchPx = NOTCH_PX, travelPx = null)
		// Six pixels per step, so 60px of total travel is ten steps whatever route the pointer took.
		assertEquals(110f, scrubValue(base, 60f, perPixel, range), 1e-4f)
		assertEquals(120f, scrubValue(base, 120f, perPixel, range), 1e-4f)
		// Returning the pointer to where it started returns the value to where it started.
		assertEquals(base, scrubValue(base, 0f, perPixel, range), 1e-4f)

		// Feeding each result back in as the next base is what the bug did: the same 120px of travel,
		// reported in two frames, lands somewhere else entirely.
		val compounded = scrubValue(scrubValue(base, 60f, perPixel, range), 120f, perPixel, range)
		assertEquals(130f, compounded, 1e-4f, "a drifting base overshoots - which is why the base is latched")
	}
}