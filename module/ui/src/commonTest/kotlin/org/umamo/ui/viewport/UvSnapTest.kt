package org.umamo.ui.viewport

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.GridConfig
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshSelectionOps
import org.umamo.edit.UvCursor
import org.umamo.edit.UvSnapKind
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Verifies the UV snap executor ([handleUvSnapRequest]) end to end at the model level: each of the
 * seven operations transforms the selected texture coordinates (or moves the UV cursor) as the UV snap
 * pie promises, over the texel display space the UV editor works in.  A 100x100 page with a
 * 10-subdivision grid is used throughout, so the display coordinate of a uv is u * 100 across and
 * (1 - v) * 100 down (the v-flip), the pixel step is one texel, and the grid step is ten.
 *
 * The single triangle's three vertices sit at display (12.3, 45.7), (34.6, 45.7), (12.3, 78.2) - chosen
 * so no round lands on a tie and every expected target is exact to four decimals.
 */
class UvSnapTest {
	private val pageWidth = 100
	private val pageHeight = 100

	/** The triangle's stored uvs, the display positions above run back through the display mapping. */
	private val startUvs = floatArrayOf(0.123f, 0.543f, 0.346f, 0.543f, 0.123f, 0.218f)

	private fun snapModel(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = DrawableId("a"),
						name = "a",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), startUvs.copyOf(), intArrayOf(0, 1, 2)),
						keyforms = null,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** An Edit-mode session with the whole triangle selected and a 100-unit / 10-subdivision grid. */
	private fun snapSession(select: Boolean = true): EditorSession {
		val session = EditorSession(snapModel())
		session.setMode(EditorMode.Edit)
		session.setGridConfig(GridConfig(scale = 100f, subdivisions = 10))
		if (select) {
			var selection = session.meshSelection.value
			for (vertexIndex in 0..2) {
				selection = MeshSelectionOps.add(selection, DrawableId("a"), MeshElement.Vertex(vertexIndex))
			}
			session.setMeshSelection(selection)
		}
		return session
	}

	/** The shown mesh's gizmo geometry, its positions the stored uvs projected into display space. */
	private fun geometriesOf(session: EditorSession): List<GizmoMeshGeometry> =
		session.model.value.drawables.mapNotNull { drawable ->
			val mesh = drawable.mesh ?: return@mapNotNull null
			GizmoMeshGeometry(drawable.id, mesh.indices, emptyList(), uvToDisplay(mesh.uvs, pageWidth, pageHeight))
		}

	private fun snap(session: EditorSession, kind: UvSnapKind) {
		handleUvSnapRequest(session, geometriesOf(session), pageWidth, pageHeight, kind)
	}

	private fun currentUvs(session: EditorSession): FloatArray = session.model.value.drawables[0].mesh!!.uvs

	private fun assertUvsEqual(expected: List<Float>, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)
		for (componentIndex in expected.indices) {
			assertEquals(expected[componentIndex], actual[componentIndex], 1e-4f, "$message (component $componentIndex)")
		}
	}

	/** Selection to Pixels rounds each covered vertex to its nearest integer texel (a pixel corner). */
	@Test
	fun selectionToPixelsRoundsToTexelCorners() {
		val session = snapSession()
		snap(session, UvSnapKind.SelectionToPixels)
		// (12.3,45.7)->(12,46) (34.6,45.7)->(35,46) (12.3,78.2)->(12,78), back through the display mapping.
		assertUvsEqual(listOf(0.12f, 0.54f, 0.35f, 0.54f, 0.12f, 0.22f), currentUvs(session), "each vertex snaps to a pixel corner")
		assertEquals("change.mesh.moveUvs", session.historyView.value.steps.last().labelKey, "one MoveUvs undo step")
	}

	/** Selection to Grid rounds each covered vertex to the page / subdivisions grid (step 10 here). */
	@Test
	fun selectionToGridRoundsToTheDrawnGrid() {
		val session = snapSession()
		snap(session, UvSnapKind.SelectionToGrid)
		// (12.3,45.7)->(10,50) (34.6,45.7)->(30,50) (12.3,78.2)->(10,80).
		assertUvsEqual(listOf(0.10f, 0.50f, 0.30f, 0.50f, 0.10f, 0.20f), currentUvs(session), "each vertex snaps to a grid line")
	}

	/** Selection to Cursor piles every covered vertex onto the UV cursor (Blender parity). */
	@Test
	fun selectionToCursorCollapsesOntoTheCursor() {
		val session = snapSession()
		session.setUvCursor(0.5f, 0.5f)
		snap(session, UvSnapKind.SelectionToCursor)
		assertUvsEqual(listOf(0.5f, 0.5f, 0.5f, 0.5f, 0.5f, 0.5f), currentUvs(session), "every vertex lands on the cursor")
	}

	/** Selection to Cursor (Offset) rigidly translates so the covered median lands on the cursor. */
	@Test
	fun selectionToCursorOffsetTranslatesKeepingShape() {
		val session = snapSession()
		session.setUvCursor(0.5f, 0.5f)
		snap(session, UvSnapKind.SelectionToCursorOffset)
		// Median display (19.7333, 56.5333) -> cursor (50, 50): a shared delta of (30.2667, -6.5333).
		assertUvsEqual(
			listOf(0.42567f, 0.60833f, 0.64867f, 0.60833f, 0.42567f, 0.28333f),
			currentUvs(session),
			"the selection translates rigidly, its median on the cursor",
		)
	}

	/** Cursor to Pixels snaps the UV cursor to its nearest pixel corner. */
	@Test
	fun cursorToPixelsRoundsTheCursor() {
		val session = snapSession(select = false)
		session.setUvCursor(0.123f, 0.543f)
		snap(session, UvSnapKind.CursorToPixels)
		val cursor = session.uvCursor.value!!
		assertEquals(0.12f, cursor.u, 1e-4f, "cursor u snaps to a texel corner")
		assertEquals(0.54f, cursor.v, 1e-4f, "cursor v snaps to a texel corner")
	}

	/** Cursor to Grid snaps the UV cursor to the page / subdivisions grid. */
	@Test
	fun cursorToGridSnapsTheCursor() {
		val session = snapSession(select = false)
		session.setUvCursor(0.123f, 0.543f)
		snap(session, UvSnapKind.CursorToGrid)
		assertEquals(UvCursor(0.10f, 0.50f), session.uvCursor.value, "cursor snaps to a grid line")
	}

	/** Cursor to Selected moves the UV cursor to the covered vertices' median. */
	@Test
	fun cursorToSelectedMovesToTheMedian() {
		val session = snapSession()
		snap(session, UvSnapKind.CursorToSelected)
		val cursor = session.uvCursor.value!!
		// Median display (19.7333, 56.5333) -> uv (0.19733, 0.43467).
		assertEquals(0.19733f, cursor.u, 1e-4f, "cursor u lands on the covered median")
		assertEquals(0.43467f, cursor.v, 1e-4f, "cursor v lands on the covered median")
	}

	/** With nothing selected the selection snaps commit nothing and Cursor to Selected no-ops. */
	@Test
	fun emptySelectionIsANoOp() {
		val session = snapSession(select = false)
		val modelBefore = session.model.value
		snap(session, UvSnapKind.SelectionToPixels)
		snap(session, UvSnapKind.SelectionToGrid)
		assertSame(modelBefore, session.model.value, "no covered vertices commit nothing")
		snap(session, UvSnapKind.CursorToSelected)
		assertNull(session.uvCursor.value, "no covered median leaves the cursor unplaced")
	}
}
