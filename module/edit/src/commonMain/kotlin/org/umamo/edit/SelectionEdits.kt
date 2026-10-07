package org.umamo.edit

import org.umamo.runtime.model.DrawableId

/*
 * Selection operations over the session: the Edit-mode select-mode switch, Select All and Invert in both
 * modes, and the Alt+Q switch of the edited mesh.  Each records one undo step through the session's
 * selection setters or commitStep; none is a document edit, so none marks the document dirty.
 */

/**
 * Switches the Edit-mode select mode (vertex / edge / face) as its own undo step, converting the
 * stored selection into the new domain with Blender's flush-down / derive-up rules (see
 * [MeshSelectionOps.changeSelectMode]). The conversion is lossy by design, so the snapshot is what
 * makes it recoverable. A no-op outside Edit mode — so the bound 1 / 2 / 3 commands need no context
 * guard of their own and the keymap stays mode-agnostic — and a no-op when already in [selectMode].
 * Not a document edit — leaves dirty untouched.
 *
 * @param MeshSelectMode selectMode The new select mode.
 */
fun EditorSession.setMeshSelectMode(selectMode: MeshSelectMode) {
	if (mode.value != EditorMode.Edit) {
		return
	}
	val current = meshSelection.value
	val model = model.value
	val converted =
		MeshSelectionOps.changeSelectMode(current, selectMode) { drawableId ->
			model.drawables.firstOrNull { it.id == drawableId }?.mesh?.indices
		}
	if (converted == current) {
		return
	}
	commitStep(EditorStateChange.MeshSelectModeChanged(selectMode), meshSelection = converted)
}

/**
 * Selects every element of every session mesh in the current select mode (Blender's Select All) as
 * one undo step.  A no-op outside Edit mode, or when the Edit session holds no meshes - so the bound
 * command stays mode-agnostic (it dispatches to [selectAllObjects] in Object mode).  Not a document edit.
 */
fun EditorSession.selectAllMeshElements() {
	if (mode.value != EditorMode.Edit) {
		return
	}
	val current = meshSelection.value
	val model = model.value
	setMeshSelection(MeshSelectionOps.selectAll(current) { drawableId -> model.drawables.firstOrNull { it.id == drawableId }?.mesh })
}

/**
 * Inverts every session mesh's element selection within the current select mode (Blender's Ctrl+I) as
 * one undo step.  A no-op outside Edit mode, or when the Edit session holds no meshes.  Not a document
 * edit.
 */
fun EditorSession.invertMeshSelection() {
	if (mode.value != EditorMode.Edit) {
		return
	}
	val current = meshSelection.value
	val model = model.value
	setMeshSelection(MeshSelectionOps.invert(current) { drawableId -> model.drawables.firstOrNull { it.id == drawableId }?.mesh })
}

/**
 * Selects every selectable entity in the model (Object mode's Select All) as one undo step.  A no-op
 * outside Object mode - so the bound command stays mode-agnostic (it dispatches to [selectAllMeshElements]
 * in Edit mode).  Not a document edit.
 */
fun EditorSession.selectAllObjects() {
	if (mode.value != EditorMode.Object) {
		return
	}
	setSelection(SelectionOps.selectAll(selection.value, model.value))
}

/**
 * Inverts the object selection over every selectable entity (Object mode's Ctrl+I) as one undo step.  A
 * no-op outside Object mode.  Not a document edit.
 */
fun EditorSession.invertObjectSelection() {
	if (mode.value != EditorMode.Object) {
		return
	}
	setSelection(SelectionOps.invert(selection.value, model.value))
}

/**
 * Re-seeds the Edit session onto one drawable (Alt+Q's switch), as ONE undo step covering both
 * selections: the session's meshes become just [drawableId] (with its remembered elements restored
 * where they still fit), and the OBJECT selection moves onto the same drawable - so tabbing back to
 * Object mode keeps the switched mesh instead of reviving the selection Edit mode was entered with.
 * The outgoing meshes' element selections stash into the per-mesh memory first, and the
 * remembered-drawable memory follows.  A no-op outside Edit mode or when the drawable carries no
 * mesh.
 *
 * Built as one combined snapshot push (never chained setSelection + setMeshSelection - each of
 * those snapshots the OTHER selection's pre-change value, which would tear the pair across two
 * undo steps).
 *
 * @param DrawableId drawableId The mesh to edit next.
 */
fun EditorSession.switchEditDrawable(drawableId: DrawableId) {
	if (mode.value != EditorMode.Edit) {
		return
	}
	val model = model.value
	if (model.drawables.none { drawable -> drawable.id == drawableId && drawable.mesh != null }) {
		return
	}
	elementMemory.stash(meshSelection.value)
	elementMemory.lastActiveDrawableId = drawableId
	val newObjectSelection = SelectionOps.replace(SelectionTarget.Drawable(drawableId))
	val newMeshSelection = elementMemory.restore(MeshSelection.editing(listOf(drawableId)), model)
	if (newObjectSelection == selection.value && newMeshSelection == meshSelection.value) {
		return
	}
	commitStep(EditorStateChange.MeshSelectionChanged, selection = newObjectSelection, meshSelection = newMeshSelection)
}