package org.umamo.edit

import org.umamo.edit.SessionTestModels.meshModel
import org.umamo.edit.SessionTestModels.model
import org.umamo.edit.SessionTestModels.twoMeshModel
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Tests for SelectionEdits.kt: the select-mode switch, the hierarchy expansion of Select All, and the Alt+Q
 * switch of the edited mesh with its per-mesh element memory.
 */
class SelectionEditsTest {
	/**
	 * Alt+Q's switch moves the OBJECT selection with the edit session in ONE undo step, and the
	 * per-mesh element memory restores each mesh's selection when the session returns to it.
	 */
	@Test
	fun switchEditDrawableSyncsObjectSelectionAndRemembersElementsPerMesh() {
		val session = EditorSession(twoMeshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("a"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, DrawableId("a"), MeshElement.Vertex(1)))

		val stepsBefore = session.historyView.value.steps.size
		session.switchEditDrawable(DrawableId("b"))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the switch is one undo step")
		assertEquals(listOf(DrawableId("b")), session.meshSelection.value.drawableIds, "the edit session moved to b")
		assertEquals(
			SelectionTarget.Drawable(DrawableId("b")),
			session.selection.value.active,
			"the object selection follows the switch (tabbing out keeps b)",
		)

		// Switching back restores a's remembered element selection (the per-mesh memory).
		session.switchEditDrawable(DrawableId("a"))
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("a")), "a's elements return")

		// One undo rewinds the whole switch - BOTH selections together, never torn across two steps.
		session.undo()
		assertEquals(listOf(DrawableId("b")), session.meshSelection.value.drawableIds, "undo returns the session to b")
		assertEquals(SelectionTarget.Drawable(DrawableId("b")), session.selection.value.active, "undo returns the object selection with it")
	}

	/**
	 * A select-mode switch converts the selection (Blender's derive-up / flush-down), is one undo step
	 * labeled "change.mesh.selectMode", and no-ops outside Edit mode or when already in the requested mode.
	 */
	@Test
	fun selectModeSwitchConvertsAndIsUndoable() {
		val session = EditorSession(meshModel())
		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(MeshSelectMode.Vertex, session.meshSelection.value.selectMode, "no-op outside Edit mode")

		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(
			MeshSelectionOps.add(MeshSelectionOps.add(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)), DrawableId("d"), MeshElement.Vertex(1)),
		)

		val stepsBefore = session.historyView.value.steps.size
		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(MeshSelectMode.Edge, session.meshSelection.value.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(DrawableId("d")), "both-endpoint edge derives")
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the switch is exactly one undo step")
		assertEquals("change.mesh.selectMode", session.historyView.value.steps.last().labelKey)
		assertFalse(session.dirty.value, "a select-mode switch never dirties the document")

		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "a same-mode switch records nothing")

		session.undo()
		assertEquals(MeshSelectMode.Vertex, session.meshSelection.value.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("d")))

		session.redo()
		assertEquals(MeshSelectMode.Edge, session.meshSelection.value.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(DrawableId("d")))
	}

	/** switchEditDrawable re-seeds the Edit session onto one mesh as an undoable selection step. */
	@Test
	fun switchEditDrawableReseedsTheSession() {
		val model =
			meshModel().let { base ->
				base.copy(drawables = base.drawables + base.drawables.first().copy(id = DrawableId("d2"), name = "d2"))
			}
		val session = EditorSession(model)
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		assertEquals(listOf(DrawableId("d")), session.meshSelection.value.drawableIds)

		session.switchEditDrawable(DrawableId("d2"))
		assertEquals(listOf(DrawableId("d2")), session.meshSelection.value.drawableIds, "the session re-seeds onto the target")
		assertEquals(DrawableId("d2"), session.meshSelection.value.activeDrawableId)

		session.undo()
		assertEquals(listOf(DrawableId("d")), session.meshSelection.value.drawableIds, "the switch is one undoable step")

		// A mesh-less or unknown drawable refuses the switch.
		session.switchEditDrawable(DrawableId("missing"))
		assertEquals(listOf(DrawableId("d")), session.meshSelection.value.drawableIds)
	}

	/**
	 * selectHierarchy expands a part to its outliner subtree (descendant parts + their drawables) and
	 * leaves a drawable-only selection unchanged (a leaf's subtree is itself).
	 */
	@Test
	fun selectHierarchyExpandsPartSubtrees() {
		val fixture = model() // part a holds drawable d; part b is empty.
		val partTarget = SelectionTarget.Part(PartId("a"))
		val expanded = SelectionOps.selectHierarchy(Selection(setOf(partTarget), partTarget), fixture)
		assertEquals(
			setOf<SelectionTarget>(partTarget, SelectionTarget.Drawable(DrawableId("d"))),
			expanded.targets,
			"the part's subtree (itself + its drawable) is selected",
		)
		assertEquals(partTarget, expanded.active, "the original active target is kept")

		val leaf = SelectionTarget.Drawable(DrawableId("d"))
		val unchanged = SelectionOps.selectHierarchy(Selection(setOf(leaf), leaf), fixture)
		assertEquals(setOf<SelectionTarget>(leaf), unchanged.targets, "a drawable expands to just itself")

		assertTrue(SelectionOps.selectHierarchy(Selection(), fixture).isEmpty, "an empty selection stays empty")
	}
}