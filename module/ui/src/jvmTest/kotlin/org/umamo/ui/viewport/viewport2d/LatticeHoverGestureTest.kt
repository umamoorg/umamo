package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ParameterChange
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.toggleSelectable
import org.umamo.edit.transform.beginObjectOperator
import org.umamo.runtime.model.DeformerId
import org.umamo.ui.viewport.gizmo.HIT_RADIUS_PX
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.pressIn
import org.umamo.ui.viewport.gizmo.releaseIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the 2D viewport's lattice hover through the overlays themselves: the free pointer publishes the lattice a
 * click there would take, for its own area alone, only when it changes, passing a locked warp over; a press, a
 * gesture, another area's gesture, and leaving the mode clear it; and Pose mode's overlay hovers the idle lattices
 * the same way.  The stub answers the lattice pick from each area's stack, whatever the point.
 */
@OptIn(ExperimentalTestApi::class)
class LatticeHoverGestureTest {
	private val pointA = rigScreenOf(10f, -10f)
	private val pointB = rigScreenOf(12f, -10f)

	/**
	 * The hovers the overlays published since [from], as the area and the warp.
	 *
	 * @param GizmoOverlayFixture fixture The fixture.
	 * @param Int from The index to read from.
	 * @return List<Pair<String, DeformerId?>> The pushes.
	 */
	private fun hoversSince(fixture: GizmoOverlayFixture, from: Int): List<Pair<String, DeformerId?>> = fixture.service.pushedLatticeHovers.drop(from)

	@Test
	fun theFreePointerHoversTheLatticeAClickWouldTakeInItsAreaAlone() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)

			moveIn(LEFT_AREA, listOf(pointA))

			assertEquals(listOf<Pair<String, DeformerId?>>(LEFT_AREA to LATTICE_RIG_HEAD), fixture.service.pushedLatticeHovers, "the best lattice, in the pointer's area")
			assertEquals(HIT_RADIUS_PX, fixture.service.latticePickRequests.last().second.first, "at the click's radii")

			moveIn(LEFT_AREA, listOf(pointB, pointA))
			assertEquals(1, fixture.service.pushedLatticeHovers.size, "moving along the same lattice publishes nothing")

			val before = fixture.service.pushedLatticeHovers.size
			fixture.service.latticeStackByArea[LEFT_AREA] = emptyList()
			moveIn(LEFT_AREA, listOf(pointB))
			assertEquals(listOf<Pair<String, DeformerId?>>(LEFT_AREA to null), hoversSince(fixture, before), "off every lattice, the hover clears")
			assertTrue(fixture.service.pushedLatticeHovers.none { (areaId, _) -> areaId == RIGHT_AREA }, "the other area never hovers")
		}

	@Test
	fun aLockedWarpIsPassedOverAsTheClickPassesIt() =
		runComposeUiTest {
			val session = latticeRigSession()
			session.toggleSelectable(SelectionTarget.Deformer(LATTICE_RIG_HEAD))
			val fixture = mountGizmoOverlays(session)
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD, LATTICE_RIG_BODY)

			moveIn(LEFT_AREA, listOf(pointA))

			assertEquals(listOf<Pair<String, DeformerId?>>(LEFT_AREA to LATTICE_RIG_BODY), fixture.service.pushedLatticeHovers)
		}

	@Test
	fun aPressClearsTheHoverAndTheReleaseResolvesItAgain() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD)
			moveIn(LEFT_AREA, listOf(pointA))
			val before = fixture.service.pushedLatticeHovers.size

			pressIn(LEFT_AREA, pointA)
			assertEquals(listOf<Pair<String, DeformerId?>>(LEFT_AREA to null), hoversSince(fixture, before), "a press is a click or a drag, not a look")

			releaseIn(LEFT_AREA)
			assertEquals(LEFT_AREA to LATTICE_RIG_HEAD, fixture.service.pushedLatticeHovers.last(), "the release looks again")
		}

	@Test
	fun aGestureHereOrElsewhereClearsTheHover() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD)
			moveIn(LEFT_AREA, listOf(pointA))

			fixture.session.beginObjectOperator(MeshOperatorKind.Grab, RIGHT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(pointB))

			assertEquals(LEFT_AREA to null, fixture.service.pushedLatticeHovers.last(), "another area's gesture leaves this one inert")
		}

	@Test
	fun aGrabInTheAreaClearsTheHover() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD)
			moveIn(LEFT_AREA, listOf(pointA))

			fixture.session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(pointB))

			assertEquals(LEFT_AREA to null, fixture.service.pushedLatticeHovers.last(), "the transform owns the pointer")
		}

	@Test
	fun leavingObjectModeClearsTheHover() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY))))
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_HEAD)
			moveIn(LEFT_AREA, listOf(pointA))
			// Edit mode publishes no lattice layer, so the renderer's picks answer nothing there; the stub answers
			// whatever it holds, so the case empties it the way the renderer would.  The Edit overlay mounts under the
			// resting pointer and looks once, which then finds nothing and publishes nothing.
			fixture.service.latticeStackByArea[LEFT_AREA] = emptyList()

			fixture.session.toggleEditMode()
			waitForIdle()

			assertEquals(EditorMode.Edit, fixture.session.mode.value)
			assertEquals(LEFT_AREA to null, fixture.service.pushedLatticeHovers.last(), "the overlay that hovered clears as it leaves")
		}

	@Test
	fun poseModeHoversTheIdleLatticeAltQWouldTake() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession())
			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_GUIDE)

			moveIn(LEFT_AREA, listOf(pointA))

			assertEquals(LEFT_AREA to LATTICE_RIG_GUIDE, fixture.service.pushedLatticeHovers.last())

			fixture.session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(LATTICE_RIG_GUIDE, fixture.session.meshSelection.value.deformerIds.single(), "Alt+Q takes the hovered lattice")
		}

	/**
	 * The lattice rig posed between its keys with the body warp selected, in Pose mode.
	 *
	 * @return EditorSession The session.
	 */
	private fun poseSession(): EditorSession {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.commitPose(ParameterChange.SetValue(listOf(LATTICE_RIG_PARAMETER)), session.pose.value + mapOf(LATTICE_RIG_PARAMETER to 0.5f))
		session.togglePoseMode()
		assertEquals(EditorMode.Pose, session.mode.value)

		return session
	}
}