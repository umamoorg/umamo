package org.umamo.edit

import org.umamo.edit.SessionTestModels.meshModel
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Tests for ToolArming.kt: the mode and selection guards between a command and a tool latch.
 */
class ToolArmingTest {
	/** Entering Edit auto-seeds the topmost editable drawable; a modal operator latches only with a non-empty selection. */
	@Test
	fun editModeSeedAndOperatorGuards() {
		val session = EditorSession(meshModel())

		// No active drawable and nothing remembered: Edit auto-selects the topmost editable drawable (d)
		// rather than opening an inert session, but the operator still no-ops with no elements selected.
		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("d"), session.meshSelection.value.activeDrawableId)
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertNull(session.activeMeshOperator.value, "operator no-ops with an empty element selection")

		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)))
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeMeshOperator.value?.kind)
		session.clearMeshOperator()
		assertNull(session.activeMeshOperator.value)

		// An edge selection latches too - G / S / R moves the vertices the selected elements cover.
		session.setMeshSelectMode(MeshSelectMode.Edge)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Edge.of(0, 1)))
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeMeshOperator.value?.kind)
		session.clearMeshOperator()
	}
}