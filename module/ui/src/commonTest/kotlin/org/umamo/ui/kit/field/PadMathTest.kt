package org.umamo.ui.kit.field

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the pad's axis math: a pointer maps over the full extent and holds at the ends, the vertical axis runs up, each
 * axis's Ctrl latch reaches the same pixel distance whatever the pad's size, and a pip's shape shows the kinds of key
 * that meet at it.
 */
class PadMathTest {
	/** How close two values must be, past float rounding. */
	private val tolerance = 1e-5f

	/** The axis range the tests use: a span of 60, centered on 0. */
	private val angle = -30f..30f

	/** The ends of the extent are the ends of the range, and the middle is the middle. */
	@Test
	fun anAxisMapsOverTheFullExtent() {
		assertEquals(-30f, padValueAt(0f, 300f, angle, invert = false), tolerance)
		assertEquals(0f, padValueAt(150f, 300f, angle, invert = false), tolerance)
		assertEquals(30f, padValueAt(300f, 300f, angle, invert = false), tolerance)
		assertEquals(15f, padValueAt(225f, 300f, angle, invert = false), tolerance)
	}

	/** A pointer past either edge holds at that end. */
	@Test
	fun pastAnEdgeTheAxisHoldsAtItsEnd() {
		assertEquals(-30f, padValueAt(-40f, 300f, angle, invert = false), tolerance)
		assertEquals(30f, padValueAt(340f, 300f, angle, invert = false), tolerance)
	}

	/** Inverted, the top of the pad is the maximum. */
	@Test
	fun anInvertedAxisRunsUp() {
		assertEquals(30f, padValueAt(0f, 200f, angle, invert = true), tolerance, "the top edge is the maximum")
		assertEquals(-30f, padValueAt(200f, 200f, angle, invert = true), tolerance)
		assertEquals(15f, padValueAt(50f, 200f, angle, invert = true), tolerance)
	}

	/** A pad with no size yet cannot divide by zero. */
	@Test
	fun aZeroExtentStillGivesAValue() {
		assertEquals(-30f, padValueAt(0f, 0f, angle, invert = false), tolerance)
		assertEquals(30f, padValueAt(5f, 0f, angle, invert = false), tolerance)
	}

	/** A value draws where a pointer would set it, and one outside the range draws at the end it is past. */
	@Test
	fun aValueDrawsWhereThePointerSetsIt() {
		assertEquals(0f, padFractionOf(-30f, angle), tolerance)
		assertEquals(0.5f, padFractionOf(0f, angle), tolerance)
		assertEquals(1f, padFractionOf(30f, angle), tolerance)
		assertEquals(0.75f, padFractionOf(15f, angle), tolerance)
		assertEquals(1f, padFractionOf(45f, angle), tolerance, "past max draws at max")
		assertEquals(0f, padFractionOf(-45f, angle), tolerance)
	}

	/** An empty range draws at its one end. */
	@Test
	fun anEmptyRangeDrawsAtItsStart() {
		assertEquals(0f, padFractionOf(2f, 2f..2f), tolerance)
	}

	/** Within reach a value latches onto the key, and out of reach it moves freely. */
	@Test
	fun anAxisLatchesOnlyWithinReach() {
		// 60 units over 300 pixels is 0.2 a pixel, so a 6 pixel reach is 1.2 units.
		assertEquals(15f, padAxisSnapped(16f, listOf(-15f, 15f), 6f, 300f, angle))
		assertEquals(15f, padAxisSnapped(14f, listOf(-15f, 15f), 6f, 300f, angle))
		assertEquals(16.5f, padAxisSnapped(16.5f, listOf(-15f, 15f), 6f, 300f, angle))
	}

	/** The reach is a distance on screen, so a pad twice the size reaches half as far in value. */
	@Test
	fun theReachIsTheSamePixelsAtAnySize() {
		assertEquals(15f, padAxisSnapped(16f, listOf(15f), 6f, 300f, angle), "1 unit is 5 pixels on a 300 pixel pad")
		assertEquals(16f, padAxisSnapped(16f, listOf(15f), 6f, 600f, angle), "and 10 pixels on a 600 pixel one")
		assertEquals(15f, padAxisSnapped(15.5f, listOf(15f), 6f, 600f, angle))
	}

	/** A key outside the range never latches, and an axis with no keys is left alone. */
	@Test
	fun aKeyOutsideTheRangeOrNoKeysLeaveTheValue() {
		assertEquals(29.5f, padAxisSnapped(29.5f, listOf(30.5f), 6f, 300f, angle))
		assertEquals(29.5f, padAxisSnapped(29.5f, emptyList(), 6f, 300f, angle))
	}

	/** A key line sits at each value once, however many marks share it. */
	@Test
	fun aKeyLineSitsAtEachValueOnce() {
		val marks = listOf(SliderKeyMark(-15f, SliderKeyShape.Circle), SliderKeyMark(15f, SliderKeyShape.Circle), SliderKeyMark(15f, SliderKeyShape.Square))

		assertEquals(listOf(-15f, 15f), padKeyLineValues(marks))
	}

	/** There is a pip at every crossing of an X key with a Y key, and none when an axis has no keys. */
	@Test
	fun aPipSitsAtEveryCrossing() {
		val xMarks = listOf(circle(-15f), circle(15f))
		val yMarks = listOf(circle(0f), circle(10f))

		assertEquals(
			listOf(KeyPoint(-15f, 0f), KeyPoint(-15f, 10f), KeyPoint(15f, 0f), KeyPoint(15f, 10f)),
			padKeyPips(xMarks, yMarks).map { pip -> KeyPoint(pip.x, pip.y) },
		)
		assertEquals(emptyList(), padKeyPips(xMarks, emptyList()), "an axis with no keys crosses nothing")
		assertEquals(emptyList(), padKeyPips(emptyList(), yMarks))
	}

	/** Grid keys crossing make a circle, blend keys crossing a square, and the two kinds crossing the mixed pip. */
	@Test
	fun aPipShowsTheKindsThatMeetAtIt() {
		assertEquals(PadKeyPipShape.Circle, onlyPip(listOf(circle(1f)), listOf(circle(2f))).shape)
		assertEquals(PadKeyPipShape.Square, onlyPip(listOf(square(1f)), listOf(square(2f))).shape)
		assertEquals(PadKeyPipShape.SquareOverCircle, onlyPip(listOf(circle(1f)), listOf(square(2f))).shape)
		assertEquals(PadKeyPipShape.SquareOverCircle, onlyPip(listOf(square(1f)), listOf(circle(2f))).shape, "whichever axis is the blend one")
	}

	/** A value keyed both ways makes one pip at each of its crossings, and every one of them is the mixed pip. */
	@Test
	fun aValueKeyedBothWaysMakesTheMixedPip() {
		val xMarks = listOf(circle(5f), square(5f))

		assertEquals(PadKeyPipShape.SquareOverCircle, onlyPip(xMarks, listOf(circle(2f))).shape)
		assertEquals(PadKeyPipShape.SquareOverCircle, onlyPip(xMarks, listOf(square(2f))).shape)
	}

	/** A pair of key values, so a list of pips compares by place alone. */
	private data class KeyPoint(
		val x: Float,
		val y: Float,
	)

	/**
	 * A grid key's mark.
	 *
	 * @param Float value The key value.
	 * @return SliderKeyMark The mark.
	 */
	private fun circle(value: Float) = SliderKeyMark(value, SliderKeyShape.Circle)

	/**
	 * A blend key's mark.
	 *
	 * @param Float value The key value.
	 * @return SliderKeyMark The mark.
	 */
	private fun square(value: Float) = SliderKeyMark(value, SliderKeyShape.Square)

	/**
	 * The one pip two axes make, asserting there is exactly one.
	 *
	 * @param List xMarks The X axis's marks.
	 * @param List yMarks The Y axis's marks.
	 * @return PadKeyPip The pip.
	 */
	private fun onlyPip(xMarks: List<SliderKeyMark>, yMarks: List<SliderKeyMark>): PadKeyPip = padKeyPips(xMarks, yMarks).single()
}