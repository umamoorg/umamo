package org.umamo.ui.viewport.uv

import androidx.compose.runtime.mutableStateOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.UvSnapKind
import org.umamo.edit.UvSnapRequest
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull

/**
 * Pins the UV Object overlay's request routing ([collectUvObjectGizmoRequests]): with two areas collecting, a
 * snap runs in the area it names and nowhere else, and only in Object mode - a request made in Edit mode is
 * the Edit overlay's.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UvObjectGizmoRequestsTest {
	/** A placement snap runs once, in the area it names: a second run would find nothing left to move and record nothing, so one step proves one area. */
	@Test
	fun aSnapRunsInItsOwnAreaOnly() =
		runTest {
			val session = uvObjectSession(model = uvRigPlacedModel())
			collectInTwoAreas(session)
			placeCursorAt(session, 60f, 50f)
			val before = uvRigPlacementOf(session, UV_RIG_QUAD_TILE)
			val stepsBefore = session.historyView.value.steps.size

			session.requestUvSnap(UvSnapRequest(UvSnapKind.SelectionToCursor, LEFT_AREA))
			runCurrent()

			assertNotEquals(before, uvRigPlacementOf(session, UV_RIG_QUAD_TILE), "the tile moved")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "once")
		}

	/** A request made in Edit mode runs nowhere here. */
	@Test
	fun aRequestInEditModeIsNotThisCollectors() =
		runTest {
			val session = uvObjectSession(model = uvRigPlacedModel())
			collectInTwoAreas(session)
			session.setMode(EditorMode.Edit)
			assertEquals(EditorMode.Edit, session.mode.value, "the case must really be in Edit mode")
			val stepsBefore = session.historyView.value.steps.size

			session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA))
			session.requestUvSnap(UvSnapRequest(UvSnapKind.SelectionToCursor, LEFT_AREA))
			runCurrent()

			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertNull(session.uvCursor.value)
			assertNull(session.notice.value)
		}

	/**
	 * Starts an Object collector for the left and the right area over [session], both showing the rig's page,
	 * in the test's background scope.
	 *
	 * @param EditorSession session The session.
	 */
	private fun TestScope.collectInTwoAreas(session: EditorSession) {
		val frame = uvRigPageFrame()
		for (areaId in listOf(LEFT_AREA, RIGHT_AREA)) {
			backgroundScope.launch {
				collectUvObjectGizmoRequests(
					areaId = areaId,
					session = session,
					surface = mutableStateOf(uvRigPlacementSurface()),
					geometries = mutableStateOf(uvRigGeometries(session.model.value, frame)),
					frame = mutableStateOf(frame),
					overlays = null,
					computeDispatcher = Dispatchers.Unconfined,
				)
			}
		}
		runCurrent()
	}

	/**
	 * Places the UV cursor at a display-space point on the page.
	 *
	 * @param EditorSession session The session.
	 * @param Float displayX The point's display x.
	 * @param Float displayY The point's display y.
	 */
	private fun placeCursorAt(session: EditorSession, displayX: Float, displayY: Float) {
		val (cursorU, cursorV) = uvRigPageFrame().storedUvAt(displayX, displayY)
		session.setUvCursor(cursorU, cursorV)
	}
}