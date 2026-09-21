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

	/**
	 * Switching the touched area to another space re-stamps it at once.  A header-dropdown switch sends
	 * no pointer event over the leaf, so waiting for one would leave dispatch, the status bar, and the
	 * palette all reading the space the area used to host.
	 */
	@Test
	fun switchingTheTouchedAreasSpaceRestampsItsKind() {
		val tracker = HoveredSurfaceTracker()
		tracker.lastTouched = HoveredSurface("area-1", SpaceKind.Viewport2D)

		tracker.restampKind("area-1", SpaceKind.Outliner)

		assertEquals(HoveredSurface("area-1", SpaceKind.Outliner), tracker.lastTouched)
		assertEquals(SpaceKind.Outliner, tracker.observedKind)
	}

	/**
	 * The strip-host claim follows the re-stamp the way it follows a pointer event: taken when the new
	 * kind hosts a strip, and left released when it does not.
	 */
	@Test
	fun theStripHostClaimFollowsTheRestampedKind() {
		val tracker = HoveredSurfaceTracker()
		tracker.lastTouched = HoveredSurface("area-1", SpaceKind.Outliner)

		tracker.restampKind("area-1", SpaceKind.UvEditor)
		assertEquals(HoveredSurface("area-1", SpaceKind.UvEditor), tracker.lastTouchedStripHost)

		// The leaf releases the outgoing kind's claim before it re-stamps, as AreaLeaf's effect does.
		tracker.releaseStripHost("area-1")
		tracker.restampKind("area-1", SpaceKind.Outliner)
		assertNull(tracker.lastTouchedStripHost, "a panel hosts no strip, so nothing claims the slot back")
	}

	/** A space switch says nothing about where the pointer is: a stamp naming another area is left alone. */
	@Test
	fun switchingAnotherAreasSpaceLeavesTheStampAlone() {
		val tracker = HoveredSurfaceTracker()
		val touched = HoveredSurface("viewport-1", SpaceKind.Viewport2D)
		tracker.lastTouched = touched
		tracker.lastTouchedStripHost = touched

		tracker.restampKind("outliner-1", SpaceKind.UvEditor)

		assertEquals(touched, tracker.lastTouched)
		assertEquals(touched, tracker.lastTouchedStripHost)
		assertEquals(SpaceKind.Viewport2D, tracker.observedKind)
	}

	/** Before the pointer has touched anything there is no stamp to move, so a switch leaves none behind. */
	@Test
	fun switchingASpaceBeforeAnyTouchStampsNothing() {
		val tracker = HoveredSurfaceTracker()

		tracker.restampKind("area-1", SpaceKind.UvEditor)

		assertNull(tracker.lastTouched)
		assertNull(tracker.lastTouchedStripHost)
	}
}