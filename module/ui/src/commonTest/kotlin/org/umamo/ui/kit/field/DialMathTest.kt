package org.umamo.ui.kit.field

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the dial's geometry and lap counting: the seam at 12 o'clock with values running clockwise, the dead zone at
 * the center, a seam crossing read the short way round, and the lap-counted value the dial hands its owner.
 */
class DialMathTest {
	/** How close two fractions or values must be, past float rounding. */
	private val tolerance = 1e-5f

	/** The four compass points sit a quarter lap apart, clockwise from the seam at 12 o'clock. */
	@Test
	fun theCompassPointsRunClockwiseFromTwelve() {
		assertEquals(0f, dialFractionAt(50f, 10f, 50f, 50f, 4f)!!, tolerance, "12 o'clock is the seam")
		assertEquals(0.25f, dialFractionAt(90f, 50f, 50f, 50f, 4f)!!, tolerance, "3 o'clock, with y growing downward")
		assertEquals(0.5f, dialFractionAt(50f, 90f, 50f, 50f, 4f)!!, tolerance)
		assertEquals(0.75f, dialFractionAt(10f, 50f, 50f, 50f, 4f)!!, tolerance)
	}

	/** Just either side of the seam reads as almost no lap and almost a whole one, never 1 itself. */
	@Test
	fun eitherSideOfTheSeamStaysInOneLap() {
		val pastSeam = dialFractionAt(50.5f, 10f, 50f, 50f, 4f)!!
		val beforeSeam = dialFractionAt(49.5f, 10f, 50f, 50f, 4f)!!

		assertEquals(0.002f, pastSeam, 0.001f)
		assertEquals(0.998f, beforeSeam, 0.001f)
		assertTrue(beforeSeam < 1f, "a fraction is always under a whole lap")
	}

	/** Inside the dead zone the angle is noise, so there is no fraction to read; on its edge too. */
	@Test
	fun theDeadZoneReadsNothing() {
		assertNull(dialFractionAt(50f, 50f, 50f, 50f, 4f))
		assertNull(dialFractionAt(52f, 51f, 50f, 50f, 4f))
		assertNull(dialFractionAt(54f, 50f, 50f, 50f, 4f))
	}

	/** A fall from near a whole lap to near none is a lap forward; the rise the other way is a lap back. */
	@Test
	fun crossingTheSeamCountsALap() {
		assertEquals(1, dialLapsAfter(0, 0.95f, 0.05f))
		assertEquals(-1, dialLapsAfter(0, 0.05f, 0.95f))
		assertEquals(2, dialLapsAfter(1, 0.9f, 0.1f))
		assertEquals(0, dialLapsAfter(1, 0.1f, 0.9f))
	}

	/** A step under half a lap is the short way round and crosses nothing, however long it is. */
	@Test
	fun aStepUnderHalfALapCrossesNothing() {
		assertEquals(0, dialLapsAfter(0, 0.1f, 0.55f))
		assertEquals(0, dialLapsAfter(0, 0.55f, 0.1f))
		assertEquals(3, dialLapsAfter(3, 0.4f, 0.6f))
	}

	/** On the first lap the value is inside the range; every lap out moves it one span past an end. */
	@Test
	fun theValueCountsItsLaps() {
		val range = -10f..10f

		assertEquals(-10f, dialValue(0f, 0, range), tolerance)
		assertEquals(-5f, dialValue(0.25f, 0, range), tolerance)
		assertEquals(15f, dialValue(0.25f, 1, range), tolerance, "a lap forward is one span past max")
		assertEquals(-25f, dialValue(0.25f, -1, range), tolerance, "a lap back is one span below min")
	}

	/** Exactly max fills the whole ring and min none, so the two read apart at the angle they share. */
	@Test
	fun theFillTellsMinFromMax() {
		val range = 0f..2f

		assertEquals(0f, dialFillFraction(0f, range))
		assertEquals(0.5f, dialFillFraction(1f, range))
		assertEquals(1f, dialFillFraction(2f, range))
		assertEquals(1f, dialFillFraction(5f, range), "a value past the range draws at its end")
		assertEquals(0f, dialFillFraction(1f, 1f..1f), "an empty range fills nothing")
	}
}