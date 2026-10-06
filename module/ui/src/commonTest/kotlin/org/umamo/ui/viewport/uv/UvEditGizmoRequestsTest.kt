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
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.UvMirrorRequest
import org.umamo.edit.UvSnapKind
import org.umamo.edit.UvSnapRequest
import org.umamo.edit.snapToGrid
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Pins the UV Edit overlay's request routing ([collectUvEditGizmoRequests]): with two areas collecting, each
 * request runs in the area it names and nowhere else, only in Edit mode, against the asking area's own
 * surface, meshes, and pointer, and a surface holding none of the edit's meshes answers with the notice once
 * (save for the cursor snaps that read no mesh) and drops a latch or tool made over it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class UvEditGizmoRequestsTest {
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
		for (areaId in listOf(LEFT_AREA, RIGHT_AREA)) {
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

			areas.session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA))
			runCurrent()

			assertEquals(stepsBefore + 1, areas.session.historyView.value.steps.size, "one mirror")
			assertNotEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(areas.session, UV_RIG_QUAD), "that was not undone by a second")
		}

	/** Outside Edit mode a request runs nowhere. */
	@Test
	fun aRequestOutsideEditModeRunsNowhere() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1))))
			areas.session.setMode(EditorMode.Object)
			val stepsBefore = areas.session.historyView.value.steps.size

			areas.session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA))
			areas.session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA))
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
			areas.pointerByArea.getValue(LEFT_AREA).value = uvRigScreenOf(100f, 100f)
			areas.pointerByArea.getValue(RIGHT_AREA).value = Offset(20f, 20f)

			areas.session.requestSelectLinked(fromSelection = false, areaId = LEFT_AREA)
			runCurrent()
			assertEquals((0..3).map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet(), areas.session.meshSelection.value.elementsOf(UV_RIG_QUAD))

			areas.session.requestSelectLinked(fromSelection = false, areaId = RIGHT_AREA)
			runCurrent()
			assertEquals(4, areas.session.meshSelection.value.elementsOf(UV_RIG_QUAD).size, "nothing under the right area's pointer changed it")
		}

	/** Cursor to Selected lands the UV cursor on the covered median, in stored coordinates. */
	@Test
	fun aSnapRunsAgainstTheShownGeometry() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2))))

			areas.session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA))
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
			areas.geometriesByArea.getValue(LEFT_AREA).value = emptyList()

			for (request in listOf<(EditorSession) -> Unit>(
				{ session -> session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA)) },
				{ session -> session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA)) },
				{ session -> session.requestSelectLinked(fromSelection = true, areaId = LEFT_AREA) },
			)) {
				val serialBefore = areas.session.notice.value?.serial ?: 0L
				request(areas.session)
				runCurrent()

				val notice = assertNotNull(areas.session.notice.value)
				assertEquals("notice.uv.noEditableGeometry", notice.messageKey)
				assertEquals(serialBefore + 1, notice.serial, "one notice, not one per area")
			}
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(areas.session, UV_RIG_QUAD), "nothing moved")
			assertNull(areas.session.uvCursor.value, "the cursor stayed")
			assertEquals(2, areas.session.meshSelection.value.elementsOf(UV_RIG_QUAD).size, "nothing was flooded")
		}

	/**
	 * The cursor snaps that read no mesh still run over a surface with none of the edit's meshes: they round
	 * the UV cursor over the shown surface, which is there whatever it holds.
	 */
	@Test
	fun theCursorOnlySnapsRunOverAnEmptySurface() =
		runTest {
			val areas = collectInTwoAreas(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			areas.geometriesByArea.getValue(LEFT_AREA).value = emptyList()
			val subdivisions = areas.session.gridConfig.value.subdivisions.coerceAtLeast(1)
			val gridStep = UV_RIG_PAGE_SIDE.toFloat() / subdivisions

			areas.session.setUvCursor(100.4f / 256, 1f - 100.4f / 256)
			areas.session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToPixels, LEFT_AREA))
			runCurrent()
			val pixelCursor = assertNotNull(areas.session.uvCursor.value)
			assertEquals(100f / 256, pixelCursor.u, 1e-6f, "the cursor rounded to the texel corner")
			assertEquals(1f - 100f / 256, pixelCursor.v, 1e-6f, "the cursor rounded to the texel corner")

			areas.session.setUvCursor(100.4f / 256, 1f - 100.4f / 256)
			areas.session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToGrid, LEFT_AREA))
			runCurrent()
			val gridCursor = assertNotNull(areas.session.uvCursor.value)
			assertEquals(snapToGrid(100.4f, 0f, gridStep) / 256, gridCursor.u, 1e-6f, "the cursor landed on the grid")

			assertNull(areas.session.notice.value, "neither said there was nothing to edit")
		}

	/**
	 * A mirror reflects only the meshes on the shown surface, about their own median: a selected mesh shown
	 * elsewhere neither moves nor pulls the pivot toward itself.
	 */
	@Test
	fun aMirrorActsOnTheShownMeshesOnly() =
		runTest {
			val session = uvEditSession(editing = listOf(UV_RIG_QUAD, UV_RIG_OTHER), elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))
			session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, UV_RIG_OTHER, MeshElement.Vertex(0)))
			val otherBefore = uvRigUvsOf(session, UV_RIG_OTHER)
			val areas = collectInTwoAreas(session)
			areas.geometriesByArea.getValue(LEFT_AREA).value = uvRigGeometries(session.model.value, uvRigPageFrame(), listOf(UV_RIG_QUAD))

			session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA))
			runCurrent()

			val quad = uvRigUvsOf(session, UV_RIG_QUAD)
			assertEquals(120f / 256, quad[0], 1e-6f, "vertex 0 reflected about the quad's own median at 110")
			assertEquals(100f / 256, quad[2], 1e-6f, "vertex 1 reflected about the quad's own median at 110")
			assertEquals(otherBefore, uvRigUvsOf(session, UV_RIG_OTHER), "the mesh not shown here is untouched")
		}

	/** A latch made in an area whose surface holds nothing to edit is dropped, with the requests' notice. */
	@Test
	fun aLatchWithNothingToEditIsDroppedWithTheNotice() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)

		dropUvEditLatchesWithNothingToEdit(LEFT_AREA, session)

		assertNull(session.activeUvOperator.value)
		assertEquals("notice.uv.noEditableGeometry", session.notice.value?.messageKey)
	}

	/** A select tool armed in an area whose surface holds nothing to select is dropped the same way. */
	@Test
	fun aToolWithNothingToSelectIsDroppedWithTheNotice() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		session.beginBoxSelect(LEFT_AREA)
		assertNotNull(session.activeSelectTool.value, "the box armed")

		dropUvEditLatchesWithNothingToEdit(LEFT_AREA, session)

		assertNull(session.activeSelectTool.value)
		assertEquals("notice.uv.noEditableGeometry", session.notice.value?.messageKey)
	}

	/** Another area's latch is not this area's to drop, and with nothing latched there is nothing to say. */
	@Test
	fun anotherAreasLatchIsLeftAlone() {
		val session = uvEditSession(elements = listOf(MeshElement.Vertex(0)))
		dropUvEditLatchesWithNothingToEdit(LEFT_AREA, session)
		assertNull(session.notice.value, "nothing latched, nothing said")

		session.beginUvOperator(MeshOperatorKind.Grab, RIGHT_AREA)
		dropUvEditLatchesWithNothingToEdit(LEFT_AREA, session)

		assertEquals(RIGHT_AREA, session.activeUvOperator.value?.areaId)
		assertNull(session.notice.value)
	}
}