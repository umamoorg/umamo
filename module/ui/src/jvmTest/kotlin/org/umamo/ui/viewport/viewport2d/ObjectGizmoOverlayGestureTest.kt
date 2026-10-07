package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.beginObjectOperator
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the Object-mode gizmo overlay's modal transform through the overlay itself: a Grab previews into
 * the render service and commits the whole drawable as one step when confirmed, and a cancel puts the
 * renderer back on the committed model.
 */
@OptIn(ExperimentalTestApi::class)
class ObjectGizmoOverlayGestureTest {
	/** Where the pointer rests before the gesture latches. */
	private val gestureStart = Offset(200f, 150f)

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = Offset(240f, 150f)

	/** A Grab moved and clicked moves every vertex of the drawable as one step and registers the strip. */
	@Test
	fun aClickConfirmsTheGrabAsOneStep() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size

			session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			clickIn(LEFT_AREA, tenUnitsRight)

			assertNull(session.activeObjectOperator.value)
			assertEquals(listOf(10f, 0f, 30f, 0f, 30f, 20f, 10f, 20f), rigPositionsOf(session, RIG_QUAD), "the whole quad moved ten units right")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId)
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer")
			assertEquals(1, fixture.service.pushedModels.count { pushed -> pushed === session.model.value }, "exactly one resync")
		}

	/** A right-click cancels: nothing commits, and the renderer goes back to the committed model. */
	@Test
	fun aRightClickCancelsTheGrab() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)

			assertNull(session.activeObjectOperator.value)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing committed")
			assertSame(session.model.value, fixture.service.pushedModels.last())
		}

	/** Enter confirms through the session's request. */
	@Test
	fun enterConfirmsTheGrab() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			session.requestMeshConfirm()
			waitForIdle()

			assertNull(session.activeObjectOperator.value)
			assertEquals(10f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	/** Leaving Object mode mid-gesture puts the renderer back on the committed model. */
	@Test
	fun leavingObjectModeMidGestureRestoresTheRenderer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginObjectOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the move previewed")

			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertEquals(EditorMode.Edit, session.mode.value)
			assertNull(session.activeObjectOperator.value)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing committed")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the renderer is back on the committed model")
		}
}