package org.umamo.edit.transform

import org.umamo.edit.BrushMode
import org.umamo.edit.BrushSettings
import org.umamo.edit.ProportionalFalloff
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the deform brush's arithmetic: a stroke's travel carries the points the brush covered and leaves
 * the others where they were, the strength scales that travel, the falloff shapes how fast the influence
 * fades, and the same stamps re-run at another strength.
 *
 * The two modes weigh the travel differently.  Drag GRABS what the brush covered when the stroke began and
 * carries it from there on with the weight it was grabbed with, so a point the brush only flew past keeps
 * its place; Flow smears every stretch over the points it went over and lets them go as the brush moves on.
 */
class BrushPointsTest {
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
	 * The offset a point at (pointX, pointZ) takes from a stroke of [radiusWorld] that walks the brush from
	 * [fromX] to [toX].
	 *
	 * @param Float pointX The point's x.
	 * @param Float fromX Where the brush starts.
	 * @param Float toX Where the brush ends.
	 * @param Float radiusWorld The brush's radius.
	 * @param Float strength The strength.
	 * @param ProportionalFalloff falloff The falloff curve.
	 * @param BrushMode mode How the brush moves the points it covers.
	 * @param Float pointZ The point's z (up).
	 * @return Float The point's x offset.
	 */
	private fun offsetOf(
		pointX: Float,
		fromX: Float,
		toX: Float,
		radiusWorld: Float,
		strength: Float,
		falloff: ProportionalFalloff = ProportionalFalloff.Smooth,
		mode: BrushMode = BrushMode.Drag,
		pointZ: Float = 0f,
	): Float {
		val settings = BrushSettings(falloff, radiusWorld, strength, mode)
		val offsets = BrushMerge(floatArrayOf(pointX, pointZ)).update(stroke(fromX, toX, radiusWorld), settings)

		return offsets[0]
	}

	/** A point the brush was over travels with it. */
	@Test
	fun aPointTheBrushIsOverTravelsWithIt() {
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
		val offsets = BrushMerge(floatArrayOf(0f, 0f)).update(still, BrushSettings(ProportionalFalloff.Smooth, 50f, 1f, BrushMode.Drag))

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
		val walked = stroke(0f, 10f, 50f)
		val lattice = floatArrayOf(0f, 0f)
		val full = BrushMerge(lattice).update(walked, BrushSettings(ProportionalFalloff.Smooth, 50f, 1f, BrushMode.Drag))
		val half = BrushMerge(lattice).update(walked, BrushSettings(ProportionalFalloff.Smooth, 50f, 0.5f, BrushMode.Drag))

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

	/** In drag mode, a point the brush catches under its centre travels the brush's whole remaining travel. */
	@Test
	fun inDragModeAPointCaughtUnderTheCentreFollowsTheCursor() {
		val dragged = offsetOf(pointX = 0f, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f, mode = BrushMode.Drag)

		assertEquals(10f, dragged, tolerance, "the point the brush was over travels the cursor's travel")
	}

	/** In drag mode, a point the brush never reaches stays exactly where it was. */
	@Test
	fun inDragModeAPointTheBrushNeverReachesStaysPut() {
		val dragged = offsetOf(pointX = 100f, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f, mode = BrushMode.Drag)

		assertEquals(0f, dragged, tolerance)
	}

	/**
	 * In drag mode, a point caught at the brush's edge keeps the weight it was caught with, so it travels
	 * the rest of the stroke in part.  The brush runs from 0 to 100 with a radius of 50, and the point sits
	 * 48 above the path's start, so the first stretch catches it with a fiftieth of its weight.
	 */
	@Test
	fun inDragModeAPointCaughtWithAPartialWeightTravelsInPart() {
		val dragged =
			offsetOf(
				pointX = 0f,
				fromX = 0f,
				toX = 100f,
				radiusWorld = 50f,
				strength = 1f,
				falloff = ProportionalFalloff.Linear,
				mode = BrushMode.Drag,
				pointZ = 48f,
			)

		assertEquals(4f, dragged, tolerance, "the point keeps the weight it was caught with")
	}

	/**
	 * In flow mode, the same point is smeared by the one stretch that reached it and released as the brush
	 * goes on, where the drag mode carries it for the rest of the stroke.
	 */
	@Test
	fun inFlowModeTheSamePointIsSmearedByTheStretchesThatReachedIt() {
		val flowed =
			offsetOf(
				pointX = 0f,
				fromX = 0f,
				toX = 100f,
				radiusWorld = 50f,
				strength = 1f,
				falloff = ProportionalFalloff.Linear,
				mode = BrushMode.Flow,
				pointZ = 48f,
			)

		assertEquals(1f, flowed, tolerance, "the first stretch carried it, and the later ones let it go")
	}

	/**
	 * In flow mode, a point under the path accumulates the motion of the stretches that went over it: the
	 * stretch it lies on carries it whole, and the stretches beside it carry it in part.
	 */
	@Test
	fun inFlowModeAPointUnderThePathAccumulatesTheStretchesMotion() {
		val flowed =
			offsetOf(
				pointX = 50f,
				fromX = 0f,
				toX = 100f,
				radiusWorld = 50f,
				strength = 1f,
				falloff = ProportionalFalloff.Linear,
				mode = BrushMode.Flow,
			)

		assertEquals(50f, flowed, tolerance, "the stretch over the point carries it whole, the two beside it in part")
	}

	/** In flow mode, a point the brush never reaches stays exactly where it was. */
	@Test
	fun inFlowModeAPointTheBrushNeverReachesStaysPut() {
		val flowed = offsetOf(pointX = 100f, fromX = 0f, toX = 10f, radiusWorld = 50f, strength = 1f, mode = BrushMode.Flow)

		assertEquals(0f, flowed, tolerance)
	}

	/**
	 * In drag mode, a point the brush only flew past on the way is NOT grabbed: the grab is taken from the
	 * circle the brush had when the stroke began, so a fast drag over a mesh leaves everything it passed
	 * over where it was.
	 */
	@Test
	fun inDragModeAPointTheBrushOnlyPassesOverIsNotGrabbed() {
		val dragged = offsetOf(pointX = 40f, fromX = 0f, toX = 100f, radiusWorld = 20f, strength = 1f, mode = BrushMode.Drag)

		assertEquals(0f, dragged, tolerance, "the point the brush flew past keeps its place")
	}
}