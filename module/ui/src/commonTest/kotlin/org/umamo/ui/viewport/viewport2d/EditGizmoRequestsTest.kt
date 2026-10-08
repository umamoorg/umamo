package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.SnapKind
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.viewport.StubPuppetViewportService
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the Edit overlay's request routing ([collectEditGizmoRequests]): with two areas collecting, each
 * request runs in the area it names and nowhere else, an empty edit answers with the notice once, and
 * Alt+Q switches directly or asks for the picker.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class EditGizmoRequestsTest {
	/** Where the host last saw the pointer in each area. */
	private val pointer = Offset(200f, 150f)

	/** Two areas' collectors over one session and service, plus what they were handed. */
	private class TwoAreas(
		val session: EditorSession,
		val service: StubPuppetViewportService,
		val geometries: MutableState<List<EditMeshGeometry>>,
		val overlapRequests: MutableList<Pair<Offset, List<PickCandidate>>>,
	)

	/**
	 * Starts a collector for the left and the right area over [session], in the test's background scope.
	 *
	 * @param EditorSession session The session.
	 * @return TwoAreas What the collectors were handed.
	 */
	private fun TestScope.collectInTwoAreas(session: EditorSession): TwoAreas {
		val service = StubPuppetViewportService()
		val geometries = mutableStateOf(editMeshGeometries(session.model.value, session.meshSelection.value.drawableIds))
		val overlapRequests = ArrayList<Pair<Offset, List<PickCandidate>>>()
		for (areaId in listOf(LEFT_AREA_ID, RIGHT_AREA_ID)) {
			backgroundScope.launch {
				collectEditGizmoRequests(
					areaId = areaId,
					session = session,
					service = service,
					geometries = geometries,
					camera = mutableStateOf(RIG_CAMERA),
					size = mutableStateOf(RIG_AREA_SIZE),
					areaPointer = mutableStateOf(pointer),
					onOverlapRequest = { anchor, candidates -> overlapRequests.add(anchor to candidates) },
					overlays = null,
				)
			}
		}
		runCurrent()
		return TwoAreas(session, service, geometries, overlapRequests)
	}

	/** A request runs in the area it names: one pick, asked by the left area. */
	@Test
	fun aRequestRunsInItsOwnAreaOnly() =
		runTest {
			val areas = collectInTwoAreas(gizmoEditSession())

			areas.session.requestSwitchObjectUnderCursor(LEFT_AREA_ID)
			runCurrent()

			assertEquals(listOf(LEFT_AREA_ID to (pointer.x to pointer.y)), areas.service.stackRequests.toList())
		}

	/** A stack under the pointer asks for the picker, anchored at the host's pointer. */
	@Test
	fun switchObjectOverAStackAsksForThePicker() =
		runTest {
			val areas = collectInTwoAreas(gizmoEditSession())
			val stack = listOf(PickCandidate(RIG_OTHER, 0f, 0f), PickCandidate(RIG_QUAD, 1f, 0f))
			areas.service.stackByArea[LEFT_AREA_ID] = stack

			areas.session.requestSwitchObjectUnderCursor(LEFT_AREA_ID)
			runCurrent()

			assertEquals(listOf(pointer to stack), areas.overlapRequests.toList())
		}

	/** One other mesh under the pointer switches the edit to it. */
	@Test
	fun switchObjectOverOneMeshSwitches() =
		runTest {
			val areas = collectInTwoAreas(gizmoEditSession())
			areas.service.stackByArea[LEFT_AREA_ID] = listOf(PickCandidate(RIG_OTHER, 0f, 0f))

			areas.session.requestSwitchObjectUnderCursor(LEFT_AREA_ID)
			runCurrent()

			assertEquals(RIG_OTHER, areas.session.meshSelection.value.activeDrawableId)
		}

	/** Select Linked from the selection floods the quad's one island. */
	@Test
	fun selectLinkedFloodsTheIsland() =
		runTest {
			val areas = collectInTwoAreas(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))

			areas.session.requestSelectLinked(fromSelection = true, areaId = LEFT_AREA_ID)
			runCurrent()

			assertEquals((0..3).map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet(), areas.session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** Cursor to Selected lands the cursor on the covered median. */
	@Test
	fun aSnapRunsAgainstTheGeometry() =
		runTest {
			val areas = collectInTwoAreas(gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2))))

			areas.session.requestSnap(SnapKind.CursorToSelected, LEFT_AREA_ID)
			runCurrent()

			val cursor = assertNotNull(areas.session.cursor2d.value)
			assertEquals(10f, cursor.worldX)
			assertEquals(-10f, cursor.worldZ)
		}

	/** With nothing to project, each geometry request answers with the notice, once, from its area. */
	@Test
	fun anEmptyEditAnswersWithTheNoticeOnce() =
		runTest {
			val areas = collectInTwoAreas(gizmoEditSession(editing = listOf(RIG_HIDDEN)))
			assertEquals(emptyList(), areas.geometries.value, "the hidden mesh projects to nothing")

			for (request in listOf<(EditorSession) -> Unit>(
				{ session -> session.requestRip(LEFT_AREA_ID) },
				{ session -> session.requestSnap(SnapKind.CursorToSelected, LEFT_AREA_ID) },
				{ session -> session.requestSelectLinked(fromSelection = true, areaId = LEFT_AREA_ID) },
			)) {
				val serialBefore = areas.session.notice.value?.serial ?: 0L
				request(areas.session)
				runCurrent()

				val notice = assertNotNull(areas.session.notice.value)
				assertEquals("notice.edit.noEditableGeometry", notice.messageKey)
				assertEquals(serialBefore + 1, notice.serial, "one notice, not one per area")
			}
		}

	private companion object {
		const val LEFT_AREA_ID = "left"
		const val RIGHT_AREA_ID = "right"
	}
}