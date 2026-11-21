package org.umamo.ui.kit.field

import androidx.compose.ui.geometry.Offset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins one wrapping gesture with the cursor warp injected: a crossing warps the cursor to where the control reads it, a
 * fresh event carries on from the landing, a stale one holds at it, a landing that never holds is taken back out and
 * warps no more, and with no warp the value wraps under the pointer all the same.
 *
 * The slider's band starts at 10 and runs 200, with an 8 detent, so the pointer crosses past 218 and a lap is 208.  The
 * control sits at (1000, 500) on screen.
 */
class ControlWrapTrackerTest {
	private val requests = mutableListOf<Offset>()

	/** Lands every request exactly, as an ideal platform does, and records it. */
	private val exactWarp: (Float, Float) -> Offset? = { screenX, screenY -> Offset(screenX, screenY).also { request -> requests.add(request) } }

	@Test
	fun aCrossingWarpsTheCursorToWhereTheControlReadsIt() {
		val tracker = ControlWrapTracker(exactWarp)
		assertEquals(Offset(100f, 9f), slide(tracker, Offset(100f, 9f), 0L))
		assertTrue(requests.isEmpty(), "nothing crosses inside the band")

		assertEquals(Offset(17f, 9f), slide(tracker, Offset(225f, 9f), 16L), "7 past the detent reads 7 past min")
		assertEquals(listOf(ORIGIN + Offset(17f, 9f)), requests, "and the cursor goes there, at its own height")

		assertEquals(Offset(20f, 9f), slide(tracker, Offset(20f, 9f), 32L), "the next event carries on from the landing")
		assertEquals(1, requests.size)
	}

	@Test
	fun aStaleEventHoldsAtTheLanding() {
		val tracker = ControlWrapTracker(exactWarp)
		slide(tracker, Offset(100f, 9f), 0L)
		slide(tracker, Offset(225f, 9f), 16L)

		assertEquals(Offset(17f, 9f), slide(tracker, Offset(226f, 9f), 18L), "an event sent before the warp reads at the landing")
		assertEquals(Offset(21f, 9f), slide(tracker, Offset(21f, 9f), 30L), "and the real one is followed")
	}

	@Test
	fun aLandingThatNeverHoldsIsTakenBackOutAndTheGestureWarpsNoMore() {
		val tracker = ControlWrapTracker(exactWarp)
		slide(tracker, Offset(100f, 9f), 0L)
		slide(tracker, Offset(225f, 9f), 16L)

		assertEquals(Offset(17f, 9f), slide(tracker, Offset(230f, 9f), 50L), "inside the hold time it waits at the landing")
		assertEquals(
			Offset(32f, 9f),
			slide(tracker, Offset(240f, 9f), 16L + WARP_LANDING_HOLD_MILLIS + 1L),
			"past it, the control reads the pointer where it really is, one lap round",
		)
		assertEquals(Offset(42f, 9f), slide(tracker, Offset(250f, 9f), 140L))

		assertEquals(Offset(14f, 9f), slide(tracker, Offset(430f, 9f), 160L), "a second crossing still wraps")
		assertEquals(1, requests.size, "but no longer moves the cursor")
	}

	@Test
	fun withNoWarpTheValueStillWrapsUnderThePointer() {
		val tracker = ControlWrapTracker { _, _ -> null }
		slide(tracker, Offset(100f, 9f), 0L)

		assertEquals(Offset(17f, 9f), slide(tracker, Offset(225f, 9f), 16L))
		assertEquals(Offset(27f, 9f), slide(tracker, Offset(235f, 9f), 32L), "the cursor keeps going, and the value with it")
	}

	@Test
	fun beforeLayoutACrossingWrapsAndWarpsNothing() {
		val tracker = ControlWrapTracker(exactWarp)
		slide(tracker, Offset(100f, 9f), 0L, origin = null)

		assertEquals(Offset(17f, 9f), slide(tracker, Offset(225f, 9f), 16L, origin = null))
		assertTrue(requests.isEmpty())
	}

	@Test
	fun aPadWithOneWrappingAxisWrapsItAndLeavesTheOther() {
		val tracker = ControlWrapTracker(exactWarp)
		val horizontal = ControlWrapAxis(bandStart = 0f, travel = PAD_WIDTH, wraps = true)
		val vertical = ControlWrapAxis(bandStart = 0f, travel = PAD_HEIGHT, wraps = false)
		tracker.controlPointOf(Offset(120f, 80f), 0L, horizontal, vertical, DETENT) { ORIGIN }

		val control = tracker.controlPointOf(Offset(255f, 200f), 16L, horizontal, vertical, DETENT) { ORIGIN }

		assertEquals(Offset(7f, 200f), control, "x comes in at its left edge; y reads past the bottom, where the pad clamps it")
		assertEquals(listOf(ORIGIN + Offset(7f, 200f)), requests, "the cursor moves along x alone")
	}

	@Test
	fun aCornerCrossingWarpsBothAxes() {
		val tracker = ControlWrapTracker(exactWarp)
		val horizontal = ControlWrapAxis(bandStart = 0f, travel = PAD_WIDTH, wraps = true)
		val vertical = ControlWrapAxis(bandStart = 0f, travel = PAD_HEIGHT, wraps = true)
		tracker.controlPointOf(Offset(120f, 80f), 0L, horizontal, vertical, DETENT) { ORIGIN }

		val control = tracker.controlPointOf(Offset(255f, 175f), 16L, horizontal, vertical, DETENT) { ORIGIN }

		assertEquals(Offset(7f, 7f), control)
		assertEquals(listOf(ORIGIN + Offset(7f, 7f)), requests)
	}

	@Test
	fun aControlThatDoesNotWrapReadsThePointerAndNeverWarps() {
		val tracker = ControlWrapTracker(exactWarp)
		val axis = ControlWrapAxis(bandStart = BAND_START, travel = TRAVEL, wraps = false)

		val control = tracker.controlPointOf(Offset(500f, 9f), 0L, axis, ControlWrapAxis.CLAMPED, DETENT) { ORIGIN }

		assertEquals(Offset(500f, 9f), control, "the slider clamps a point past its end itself")
		assertNull(requests.firstOrNull())
	}

	/**
	 * One event of a gesture on the test slider.
	 *
	 * @param ControlWrapTracker tracker The gesture.
	 * @param Offset position The pointer.
	 * @param Long timeMillis The event's time.
	 * @param Offset? origin The control's place on screen, or null before layout.
	 * @return Offset The point the control reads.
	 */
	private fun slide(tracker: ControlWrapTracker, position: Offset, timeMillis: Long, origin: Offset? = ORIGIN): Offset =
		tracker.controlPointOf(position, timeMillis, ControlWrapAxis(BAND_START, TRAVEL, wraps = true), ControlWrapAxis.CLAMPED, DETENT) { origin }

	private companion object {
		const val BAND_START = 10f
		const val TRAVEL = 200f
		const val DETENT = 8f
		const val PAD_WIDTH = 240f
		const val PAD_HEIGHT = 160f
		val ORIGIN = Offset(1000f, 500f)
	}
}