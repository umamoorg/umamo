package org.umamo.edit

import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.meshOf

/*
 * Selection operations over the session: the select-mode switch of the modes that edit elements (Edit and Pose),
 * Select All and Invert over elements or objects, their by-kind object variants, and the Alt+Q switch of the edited
 * owner.  Each records one undo step through the session's selection setters or commitStep; none is a document edit,
 * so none marks the document dirty.
 */

/**
 * Switches the select mode (vertex / edge / face, or a lattice's points / grid lines / cells) as its own undo step,
 * converting the stored selection into the new domain ([MeshSelectionOps.changeSelectMode]); the conversion is lossy
 * by design, so the snapshot is what makes it recoverable.  A no-op in a mode that edits no elements
 * ([editsElements]), so the bound 1 / 2 / 3 commands need no context guard of their own.
 *
 * @param MeshSelectMode selectMode The new select mode.
 */
fun EditorSession.setMeshSelectMode(selectMode: MeshSelectMode) {
	if (!mode.value.editsElements) {
		return
	}

	val current = meshSelection.value
	val model = model.value
	val converted =
		MeshSelectionOps.changeSelectMode(current, selectMode) { owner ->
			model.editTopologyOf(owner)
		}

	if (converted == current) {
		return
	}

	commitStep(EditorStateChange.MeshSelectModeChanged(selectMode), meshSelection = converted)
}

/**
 * Selects every element of every session owner in the current select mode (Blender's Select All) as
 * one undo step.  A no-op in a mode that edits no elements, or when the session holds no owners - so the bound
 * command stays mode-agnostic (it dispatches to [selectAllObjects] in Object mode).  Not a document edit.
 */
fun EditorSession.selectAllMeshElements() {
	if (!mode.value.editsElements) {
		return
	}

	val current = meshSelection.value
	val model = model.value
	setMeshSelection(MeshSelectionOps.selectAll(current) { owner -> model.editTopologyOf(owner) })
}

/**
 * Inverts every session owner's element selection within the current select mode (Blender's Ctrl+I) as
 * one undo step.  A no-op in a mode that edits no elements, or when the session holds no owners.  Not a document
 * edit.
 */
fun EditorSession.invertMeshSelection() {
	if (!mode.value.editsElements) {
		return
	}

	val current = meshSelection.value
	val model = model.value
	setMeshSelection(MeshSelectionOps.invert(current) { owner -> model.editTopologyOf(owner) })
}

/**
 * Selects every selectable entity in the model (Object mode's Select All) as one undo step.  A no-op in a mode that
 * edits elements - so the bound command stays mode-agnostic (it dispatches to [selectAllMeshElements] there).  Not a
 * document edit.
 */
fun EditorSession.selectAllObjects() {
	if (mode.value.editsElements) {
		return
	}

	setSelection(SelectionOps.selectAll(selection.value, model.value))
}

/**
 * Inverts the object selection over every selectable entity (Object mode's Ctrl+I) as one undo step.  A no-op in a
 * mode that edits elements.  Not a document edit.
 */
fun EditorSession.invertObjectSelection() {
	if (mode.value.editsElements) {
		return
	}

	setSelection(SelectionOps.invert(selection.value, model.value))
}

/**
 * Replaces the object selection with every selectable entity of one kind (Select All of Type) as one undo step.
 * A no-op in a mode that edits elements.  Not a document edit.
 *
 * @param SelectableKind kind The kind to select.
 */
fun EditorSession.selectAllObjectsOfKind(kind: SelectableKind) {
	if (mode.value.editsElements) {
		return
	}

	setSelection(SelectionOps.selectAllOfKind(selection.value, model.value, kind))
}

/**
 * Inverts the object selection within one kind (Invert Selection of Type) as one undo step, leaving every other
 * kind's selected entities alone.  A no-op in a mode that edits elements.  Not a document edit.
 *
 * @param SelectableKind kind The kind to invert.
 */
fun EditorSession.invertObjectSelectionOfKind(kind: SelectableKind) {
	if (mode.value.editsElements) {
		return
	}

	setSelection(SelectionOps.invertKind(selection.value, model.value, kind))
}

/**
 * Re-seeds the session onto one owner (Alt+Q's switch) as ONE undo step covering both selections: the session's
 * owners become just [owner], and the OBJECT selection moves onto the same item, so leaving the mode keeps the
 * switched item instead of reviving the selection the mode was entered with.  A no-op unless [owner] can join a
 * session in the current mode ([isSessionOwner]).
 *
 * Built as one combined push, never chained setSelection + setMeshSelection: each of those snapshots the OTHER
 * selection's pre-change value, which would tear the pair across two undo steps.
 *
 * @param ElementOwner owner The mesh or lattice to edit next.
 */
fun EditorSession.switchEditOwner(owner: ElementOwner) {
	val model = model.value

	if (!isSessionOwner(model, owner, mode.value, shownPose)) {
		return
	}

	elementMemory.stash(meshSelection.value)
	elementMemory.lastActiveOwner = owner
	val newObjectSelection = SelectionOps.replace(owner.toSelectionTarget())
	val newMeshSelection = elementMemory.restore(MeshSelection.editing(listOf(owner)).copy(selectMode = meshSelection.value.selectMode), model)

	if (newObjectSelection == selection.value && newMeshSelection == meshSelection.value) {
		return
	}

	commitStep(EditorStateChange.MeshSelectionChanged, selection = newObjectSelection, meshSelection = newMeshSelection)
}