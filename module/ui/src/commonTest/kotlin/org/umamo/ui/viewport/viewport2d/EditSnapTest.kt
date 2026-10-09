package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.SnapKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins the Edit-mode snaps that aim at the active element ([handleEditSnapRequest]) - the cursor moves
 * onto the active element's own median, and the selection piles onto it - and the grid snap, which rounds
 * each covered vertex to the step it is handed, the executing area's.  The rig's quad has vertex 0 at
 * world (0, 0), vertex 1 at (20, 0), and vertex 2 at (20, -20).
 */
class EditSnapTest {
	/**
	 * Runs one snap over the session's live geometry.
	 *
	 * @param EditorSession session The session.
	 * @param SnapKind kind The snap.
	 * @param Float gridStep The area's grid step a grid snap rounds to.
	 */
	private fun snap(session: EditorSession, kind: SnapKind, gridStep: Float = 10f) {
		handleEditSnapRequest(session, editMeshGeometries(session.model.value, session.meshSelection.value.drawableIds), kind, gridStep)
	}

	/** Selection to Grid rounds each covered vertex to the area's step; the rest of the mesh stays. */
	@Test
	fun selectionToGridRoundsEachVertexToTheAreasStep() {
		val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1), MeshElement.Vertex(2)))

		snap(session, SnapKind.SelectionToGrid, gridStep = 15f)

		// (0, 0) stays; (20, 0) rounds to (15, 0); (20, -20) to (15, -15), which is local (15, 15); vertex 3 is not covered.
		assertEquals(listOf(0f, 0f, 15f, 0f, 15f, 15f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
	}

	/** Cursor to Active lands on the active vertex, not on the selection's median. */
	@Test
	fun cursorToActiveLandsOnTheActiveVertex() {
		val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(2)))

		snap(session, SnapKind.CursorToActive)

		val cursor = assertNotNull(session.cursor2d.value)
		assertEquals(20f, cursor.worldX)
		assertEquals(-20f, cursor.worldZ)
	}

	/** Selection to Active piles every selected vertex onto the active one. */
	@Test
	fun selectionToActivePilesOntoTheActiveVertex() {
		val session = gizmoEditSession(elements = listOf(MeshElement.Vertex(0), MeshElement.Vertex(1)))

		snap(session, SnapKind.SelectionToActive)

		assertEquals(listOf(20f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD))
	}
}