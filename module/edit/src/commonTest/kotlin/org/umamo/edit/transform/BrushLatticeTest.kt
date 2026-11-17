package org.umamo.edit.transform

import org.umamo.edit.ProportionalFalloff
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the deform brush's arithmetic: a stroke's travel carries the points the brush passed over and
 * leaves the others where they were, the strength scales that travel, the falloff shapes how fast the
 * influence fades, and the same stamps re-run at another strength.
 */
class BrushLatticeTest {
	// How close a weight may sit to the value a test expects.
	private val tolerance = 1e-5f

	/**
	 * A stroke that walks the brush from [fromX] to [toX] along the x axis, one stamp at a time.
	 *
	 * @param Float fromX Where the brush starts.
	 * @param Float toX Where the brush ends.
	 * @param Float radiusWorld The brush's radius.
	 * @return BrushStroke The stroke.
	 */
	private fun stroke(fromX: Float, toX: Float, radiusWorld: Float): BrushStroke =
		BrushStroke.starting(fromX, 0f, radiusWorld).stampedTo(toX, 0f, radiusWorld)

	/**
	 * The offset a point at (pointX, 0) takes from a stroke of [radiusWorld] that walks the brush from
	 * [fromX] to [toX].
	 *
	 * @param Float pointX The point's x.
	 * @param Float fromX Where the brush starts.
	 * @param Float toX Where the brush ends.
	 * @param Float radiusWorld The brush's radius.
	 * @param Float strength The strength.
	 * @param ProportionalFalloff falloff The falloff curve.
	 * @return Float The point's x offset.
	 */
	private fun offsetOf(
		pointX: Float,
		fromX: Float,
		toX: Float,
		radiusWorld: Float,
		strength: Float,
		falloff: ProportionalFalloff = ProportionalFalloff.Smooth,
	): Float = brushLatticeOffsets(stroke(fromX, toX, radiusWorld).stamps, floatArrayOf(pointX, 0f), strength, falloff)[0]

	/** A point the brush passed over travels with it. */
	@Test
	fun aPointTheBrushPassesOverTravelsWithIt() {
		assertEquals(10f, offsetOf(pointX = 0f, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f), tolerance)
	}

	/** A point outside the brush's reach stays exactly where it was. */
	@Test
	fun aPointOutsideTheReachStaysPut() {
		assertEquals(0f, offsetOf(pointX = 100f, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f), tolerance)
	}

	/** A brush that never travels moves nothing, however long it sits there. */
	@Test
	fun aBrushThatNeverTravelsMovesNothing() {
		val still = BrushStroke.starting(0f, 0f, 50f)
		val offsets = brushLatticeOffsets(still.stamps, floatArrayOf(0f, 0f), 1f, ProportionalFalloff.Smooth)

		assertEquals(0f, offsets[0], tolerance)
	}

	/** The strength scales the travel, so the same stamps land a different distance away. */
	@Test
	fun theStrengthScalesTheTravel() {
		assertEquals(5f, offsetOf(pointX = 0f, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 0.5f), tolerance)
	}

	/** The same stamps re-run at another strength land over the same stroke, further or shorter. */
	@Test
	fun theSameStampsReRunAtAnotherStrength() {
		val stamps = stroke(0f, 10f, 50f).stamps
		val lattice = floatArrayOf(0f, 0f)
		val full = brushLatticeOffsets(stamps, lattice, 1f, ProportionalFalloff.Smooth)
		val half = brushLatticeOffsets(stamps, lattice, 0.5f, ProportionalFalloff.Smooth)

		assertEquals(full[0] * 0.5f, half[0], tolerance)
	}

	/** The falloff shapes how fast the influence fades toward the edge of the brush. */
	@Test
	fun theFalloffShapesHowFastTheInfluenceFades() {
		val near = 0.75f * 50f

		val smooth = offsetOf(pointX = near, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f, falloff = ProportionalFalloff.Smooth)
		val linear = offsetOf(pointX = near, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f, falloff = ProportionalFalloff.Linear)
		val constant = offsetOf(pointX = near, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f, falloff = ProportionalFalloff.Constant)

		assertTrue(smooth < linear, "the smooth curve fades faster than the linear one")
		assertTrue(linear < constant, "the constant curve does not fade at all")
	}

	/**
	 * A fast drag is sampled so the brush never skips a stretch: the stamps are at most half a radius
	 * apart, and the last one is where the brush ended.
	 */
	@Test
	fun aFastDragLeavesNoGap() {
		val walked = BrushStroke.starting(0f, 0f, 20f).stampedTo(100f, 0f, 20f)
		val gaps =
			walked.stamps.zipWithNext { earlier, later ->
				abs(later.centreX - earlier.centreX)
			}

		assertTrue(gaps.isNotEmpty(), "the drag laid stamps")
		assertTrue(gaps.all { gap -> gap <= 10f + tolerance }, "no stamp is more than half a radius from the next")
		assertEquals(100f, walked.stamps.last().centreX, tolerance, "the last stamp is where the brush ended")
	}
}