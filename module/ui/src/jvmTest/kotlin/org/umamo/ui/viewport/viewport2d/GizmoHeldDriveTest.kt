package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.beginMeshOperator
import org.umamo.edit.beginObjectOperator
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the 2D overlays' modal drive through the overlays themselves while the drive dispatcher is held: a
 * pointer move leaves the handler before its drive computes, a confirm commits where the
 * pointer is whether or not the drive caught up, and a drive still in flight when the gesture is cancelled
 * or its overlay leaves publishes nothing over the renderer's resync.
 */
@OptIn(ExperimentalTestApi::class)
class GizmoHeldDriveTest {
	/** Where the pointer rests before a gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = Offset(240f, 150f)

	/** Sixty pixels right of [gestureStart]: fifteen world units at the rig's zoom. */
	private val fifteenUnitsRight = Offset(260f, 150f)

	/** A held drive leaves the pointer handler with nothing pushed, and lands once the dispatcher lets it run. */
	@Test
	fun aHeldDriveLeavesThePointerHandler() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			val pushesBefore = fixture.service.pushedModels.size
			fixture.driveDispatcher.hold()

			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			assertEquals(pushesBefore, fixture.service.pushedModels.size, "the move pushed nothing while its drive is held")
			assertEquals(1, fixture.driveDispatcher.queuedCount, "the drive is in flight on the drive dispatcher")
			fixture.driveDispatcher.release()
			waitForIdle()
			val landed = fixture.service.pushedModels.last().drawables.first { drawable -> drawable.id == RIG_QUAD }
			assertEquals(10f, landed.mesh!!.positions[0], "the drive landed as it finished")
		}

	/** A right-click while a drive is held cancels, and the held drive pushes nothing over the resync. */
	@Test
	fun aCancelWhileHeldPushesNothingAfterTheResync() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)
			assertNull(session.activeMeshOperator.value)
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer")
			val pushesAfterResync = fixture.service.pushedModels.size
			fixture.driveDispatcher.release()
			waitForIdle()

			assertEquals(pushesAfterResync, fixture.service.pushedModels.size, "the held drive pushed nothing after the resync")
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing committed")
		}

	/** An overlay leaving mid-gesture while a drive is held resyncs, and the held drive pushes nothing after. */
	@Test
	fun anUnmountWhileHeldPushesNothingAfterTheResync() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()
			assertNull(session.activeMeshOperator.value, "the abandon cleared the latch")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the abandon resynced the renderer")
			val pushesAfterResync = fixture.service.pushedModels.size
			fixture.driveDispatcher.release()
			waitForIdle()

			assertEquals(pushesAfterResync, fixture.service.pushedModels.size, "the held drive pushed nothing after the resync")
		}

	/** A click while a drive is held commits where the click landed, not where the last published drive was. */
	@Test
	fun aClickWhileHeldCommitsTheClickedPointer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			fixture.driveDispatcher.hold()

			clickIn(LEFT_AREA, fifteenUnitsRight)

			assertNull(session.activeMeshOperator.value, "the click confirmed")
			assertEquals(15f, rigPositionsOf(session, RIG_QUAD)[0], "at the clicked pointer")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer")
			val pushesAfterResync = fixture.service.pushedModels.size
			fixture.driveDispatcher.release()
			waitForIdle()
			assertEquals(pushesAfterResync, fixture.service.pushedModels.size, "the held drive pushed nothing after the resync")
		}

	/** Enter while a drive is held commits the latest pointer. */
	@Test
	fun enterWhileHeldCommitsTheLatestPointer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			session.requestMeshConfirm()
			waitForIdle()

			assertNull(session.activeMeshOperator.value)
			assertEquals(10f, rigPositionsOf(session, RIG_QUAD)[0], "the held drive was settled for the confirm")
			fixture.driveDispatcher.release()
			waitForIdle()
		}

	/** An Object-mode right-click while a drive is held cancels, and the held drive pushes nothing over the resync. */
	@Test
	fun anObjectCancelWhileHeldPushesNothingAfterTheResync() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			fixture.driveDispatcher.hold()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			assertTrue(fixture.driveDispatcher.queuedCount > 0, "the drive is in flight")

			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)
			assertNull(session.activeObjectOperator.value)
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer")
			val pushesAfterResync = fixture.service.pushedModels.size
			fixture.driveDispatcher.release()
			waitForIdle()

			assertEquals(pushesAfterResync, fixture.service.pushedModels.size, "the held drive pushed nothing after the resync")
		}

	/** An Object-mode click while a drive is held commits the whole drawable where the click landed. */
	@Test
	fun anObjectClickWhileHeldCommitsTheClickedPointer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			fixture.driveDispatcher.hold()

			clickIn(LEFT_AREA, fifteenUnitsRight)

			assertNull(session.activeObjectOperator.value)
			assertEquals(listOf(15f, 0f, 35f, 0f, 35f, 20f, 15f, 20f), rigPositionsOf(session, RIG_QUAD), "the whole quad, at the clicked pointer")
			fixture.driveDispatcher.release()
			waitForIdle()
		}
}