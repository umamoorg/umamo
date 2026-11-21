package org.umamo.ui.kit.field

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the wrap a slider or pad axis keeps: no shift while the pointer stays within the detent of the band, a whole lap
 * of travel plus detent once it is pushed past an end by more, any number of laps in one step, and the same push back.
 *
 * The band here starts at 10 and runs 200, with an 8 detent: the pointer crosses past 218 or below 2, and a lap is 208.
 */
class ControlWrapMathTest {
	@Test
	fun insideTheBandOrTheDetentNothingShifts() {
		for (virtual in listOf(10f, 120f, 210f, 214f, 218f, 6f, 2f)) {
			assertEquals(0f, shiftAt(0f, virtual), "at $virtual")
		}
	}

	@Test
	fun pastMaxByMoreThanTheDetentTheShiftIsOneLap() {
		assertEquals(LAP, shiftAt(0f, 219f))
		assertEquals(11f, 219f - LAP, "so the pointer reads a hair past min, as far past the detent as it was")
	}

	@Test
	fun belowMinByMoreThanTheDetentTheShiftIsOneLapBack() {
		assertEquals(-LAP, shiftAt(0f, 1f))
	}

	@Test
	fun aJumpOfSeveralLapsLandsInOneStep() {
		val virtual = 218f + 2f * LAP + 5f
		val shift = shiftAt(0f, virtual)
		assertEquals(3f * LAP, shift)
		assertEquals(15f, virtual - shift, "the pointer reads inside the band")
	}

	@Test
	fun jitterAtMaxNeverShifts() {
		var shift = 0f

		for (virtual in listOf(210f, 217f, 211f, 218f, 209f)) {
			shift = shiftAt(shift, virtual)
		}

		assertEquals(0f, shift)
	}

	@Test
	fun crossingBackTakesTheSamePush() {
		val crossed = shiftAt(0f, 219f)
		assertEquals(crossed, shiftAt(crossed, crossed + 2f), "at the low end of the detent the pointer holds at min")
		assertEquals(0f, shiftAt(crossed, crossed + 1f), "past it, the pointer is back on the first lap")
	}

	@Test
	fun anEmptyLapNeverShifts() {
		assertEquals(0f, controlWrapShiftAfter(0f, 500f, bandStart = 0f, travel = 0f, detent = 0f))
	}

	@Test
	fun anAxisWhoseMaximumIsAtTheTopComesInAtTheTopWhenDraggedPastTheBottom() {
		val height = 160f
		val pastTheBottom = height + DETENT + 5f
		val shift = controlWrapShiftAfter(0f, pastTheBottom, bandStart = 0f, travel = height, detent = DETENT)
		val value = padValueAt(pastTheBottom - shift, height, -30f..30f, invert = true)
		assertEquals(-30f + 60f * (1f - 5f / height), value, 1e-4f, "below min it comes in just under max")
	}

	@Test
	fun theReachIsHalfTheSmallestWrappingLap() {
		val wide = ControlWrapAxis(bandStart = 0f, travel = 240f, wraps = true)
		val tall = ControlWrapAxis(bandStart = 0f, travel = 160f, wraps = true)
		val still = ControlWrapAxis(bandStart = 0f, travel = 100f, wraps = false)
		assertEquals((160f + DETENT) / 2f, controlWrapReach(wide, tall, DETENT))
		assertEquals((240f + DETENT) / 2f, controlWrapReach(wide, still, DETENT), "an axis that does not wrap never warps")
		assertEquals(Float.POSITIVE_INFINITY, controlWrapReach(still, ControlWrapAxis.CLAMPED, DETENT))
	}

	/**
	 * The shift after the pointer reaches [virtual] on the test band.
	 *
	 * @param Float shift The shift before.
	 * @param Float virtual The pointer's virtual coordinate.
	 * @return Float The shift after.
	 */
	private fun shiftAt(shift: Float, virtual: Float): Float = controlWrapShiftAfter(shift, virtual, BAND_START, TRAVEL, DETENT)

	private companion object {
		const val BAND_START = 10f
		const val TRAVEL = 200f
		const val DETENT = 8f
		const val LAP = TRAVEL + DETENT
	}
}