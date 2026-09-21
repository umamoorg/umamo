package org.umamo.ui.workspace

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins the tracker's observable kind to its last-touched surface.  The status bar reads the kind and the
 * command handlers read the surface, so the two disagreeing would suggest shortcuts for one space while
 * a press acts in another.
 */
class HoveredSurfaceTrackerTest {
	/** Nothing is observed before the pointer touches a surface. */
	@Test
	fun nothingIsObservedBeforeAnySurfaceIsTouched() {
		assertNull(HoveredSurfaceTracker().observedKind)
	}

	/** The observed kind follows every stamp, including a move between two areas of different kinds. */
	@Test
	fun theObservedKindFollowsTheLastTouchedSurface() {
		val tracker = HoveredSurfaceTracker()

		tracker.lastTouched = HoveredSurface("viewport-1", SpaceKind.Viewport2D)
		assertEquals(SpaceKind.Viewport2D, tracker.observedKind)

		tracker.lastTouched = HoveredSurface("sheet-1", SpaceKind.KeyformSheet)
		assertEquals(SpaceKind.KeyformSheet, tracker.observedKind)
	}

	/** An area that is closed or joined away takes its kind with it. */
	@Test
	fun releasingTheTouchedAreaClearsTheObservedKind() {
		val tracker = HoveredSurfaceTracker()
		tracker.lastTouched = HoveredSurface("uv-1", SpaceKind.UvEditor)

		tracker.releaseArea("uv-1")

		assertNull(tracker.lastTouched)
		assertNull(tracker.observedKind)
	}

	/** A dying area that does not hold the stamp leaves the survivor's kind alone. */
	@Test
	fun releasingAnotherAreaLeavesTheObservedKindAlone() {
		val tracker = HoveredSurfaceTracker()
		tracker.lastTouched = HoveredSurface("uv-1", SpaceKind.UvEditor)

		tracker.releaseArea("outliner-1")

		assertEquals(SpaceKind.UvEditor, tracker.observedKind)
	}
}