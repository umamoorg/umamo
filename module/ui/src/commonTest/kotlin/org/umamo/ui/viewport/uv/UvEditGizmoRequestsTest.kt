package org.umamo.ui.viewport.uv

import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.UvMirrorRequest
import org.umamo.edit.UvSnapKind
import org.umamo.edit.UvSnapRequest
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the UV Edit overlay's request routing ([collectUvEditGizmoRequests]): with two areas collecting, each
 * request runs in the area it names and nowhere else, only in Edit mode, against the asking area's own
 * surface and pointer, and a surface holding none of the edit's meshes answers with the notice once.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UvEditGizmoRequestsTest {
	/** The quad's stored coordinates as the rig builds them. */
	private val quadUvs = listOf(100f / 256, 1f - 100f / 256, 120f / 256, 1f - 100f / 256, 120f / 256, 1f - 120f / 256, 100f / 256, 1f - 120f / 256)

	/**
	 * Two areas' collectors over one session, plus what each was handed.
	 *
	 * @property EditorSession session The session.
	 * @property Map geometriesByArea Each area's shown geometry.
	 * @property Map pointerByArea Where the host last saw the pointer in each area.
	 */
	private class TwoAreas(
		val session: EditorSession,
		val geometriesByArea: Map<String, MutableState<List<GizmoMeshGeometry>>>,
		val pointerByArea: Map<String, MutableState<Offset>>,
	)

	/**
	 * Starts a collector for the left and the right area over [session], both showing the rig's page, in the
	 * test's background scope.
	 *
	 * @param EditorSession session The session.
	 * @return TwoAreas What the collectors were handed.
	 */
	private fun TestScope.collectInTwoAreas(session: EditorSession): TwoAreas {
		val frame = uvRigPageFrame()
		val geometriesByArea = HashMap<String, MutableState<List<GizmoMeshGeometry>>>()
		val pointerByArea = HashMap<String, MutableState<Offset>>()
		for (areaId in listOf(LEFT_AREA_ID, RIGHT_AREA_ID)) {
			val geometries = mutableStateOf(uvRigGeometries(session.model.value, frame))
			val pointer = mutableStateOf(Offset(200f, 150f))
			geometriesByArea[areaId] = geometries
			pointerByArea[areaId] = pointer
			backgroundScope.launch {
				collectUvEditGizmoRequests(
					areaId = areaId,
					session = session,
					geometries = geometries,
					frame = mutableStateOf(frame),
					camera = mutableStateOf(UV_RIG_PAGE_CAMERA),
					size = mutableStateOf(UV_RIG_AREA_SIZE),
					areaPointer = pointer,
				)
			}
		}
		runCurrent()
		return TwoAreas(session, geometriesByArea, pointerByArea)
	}

	/** A mirror runs in the area it names, once: a second run would mirror the coordinates straight back. */
	@Test
	fun aMirrorRunsInItsOwnAreaOnly() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1))))
			val stepsBefore = areas.session.historyView.value.steps.size

			areas.session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA_ID))
			runCurrent()

			assertEquals(stepsBefore + 1, areas.session.historyView.value.steps.size, "one mirror")
			assertNotEquals(quadUvs, uvRigUvsOf(areas.session, UV_RIG_QUAD), "that was not undone by a second")
		}

	/** Outside Edit mode a request runs nowhere. */
	@Test
	fun aRequestOutsideEditModeRunsNowhere() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1))))
			areas.session.setMode(EditorMode.Object)
			val stepsBefore = areas.session.historyView.value.steps.size

			areas.session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA_ID))
			areas.session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA_ID))
			runCurrent()

			assertEquals(stepsBefore, areas.session.historyView.value.steps.size)
			assertNull(areas.session.uvCursor.value)
			assertNull(areas.session.notice.value)
		}

	/** Select Linked under the pointer floods the island under the asking area's host pointer, and only there. */
	@Test
	fun selectLinkedReadsTheAskingAreasPointer() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession())
			areas.pointerByArea.getValue(LEFT_AREA_ID).value = uvRigScreenOf(100f, 100f)
			areas.pointerByArea.getValue(RIGHT_AREA_ID).value = Offset(20f, 20f)

			areas.session.requestSelectLinked(fromSelection = false, areaId = LEFT_AREA_ID)
			runCurrent()
			assertEquals((0..3).map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet(), areas.session.meshSelection.value.elementsOf(UV_RIG_QUAD))

			areas.session.requestSelectLinked(fromSelection = false, areaId = RIGHT_AREA_ID)
			runCurrent()
			assertEquals(4, areas.session.meshSelection.value.elementsOf(UV_RIG_QUAD).size, "nothing under the right area's pointer changed it")
		}

	/** Cursor to Selected lands the UV cursor on the covered median, in stored coordinates. */
	@Test
	fun aSnapRunsAgainstTheShownGeometry() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2))))

			areas.session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA_ID))
			runCurrent()

			val cursor = assertNotNull(areas.session.uvCursor.value)
			assertEquals(110f / 256, cursor.u)
			assertEquals(1f - 110f / 256, cursor.v)
		}

	/** Over a surface with none of the edit's meshes, each request answers with the notice, once, from its area. */
	@Test
	fun anEmptySurfaceAnswersWithTheNoticeOnce() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1))))
			areas.geometriesByArea.getValue(LEFT_AREA_ID).value = emptyList()

			for (request in listOf<(EditorSession) -> Unit>(
				{ session -> session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA_ID)) },
				{ session -> session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA_ID)) },
				{ session -> session.requestSelectLinked(fromSelection = true, areaId = LEFT_AREA_ID) },
			)) {
				val serialBefore = areas.session.notice.value?.serial ?: 0L
				request(areas.session)
				runCurrent()

				val notice = assertNotNull(areas.session.notice.value)
				assertEquals("notice.uv.noEditableGeometry", notice.messageKey)
				assertEquals(serialBefore + 1, notice.serial, "one notice, not one per area")
			}
			assertEquals(quadUvs, uvRigUvsOf(areas.session, UV_RIG_QUAD), "nothing moved")
			assertNull(areas.session.uvCursor.value, "the cursor stayed")
			assertEquals(2, areas.session.meshSelection.value.elementsOf(UV_RIG_QUAD).size, "nothing was flooded")
		}

	private companion object {
		const val LEFT_AREA_ID = "left"
		const val RIGHT_AREA_ID = "right"
	}
}