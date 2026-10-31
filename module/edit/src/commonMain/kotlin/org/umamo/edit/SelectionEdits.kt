package org.umamo.edit

import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.meshOf

/*
 * Selection operations over the session: the select-mode switch of the modes that edit elements (Edit and Pose),
 * Select All and Invert over elements or objects, and the Alt+Q switch of the edited owner.  Each records one undo
 * step through the session's selection setters or commitStep; none is a document edit, so none marks the document
 * dirty.
 */

/**
 * Switches the select mode (vertex / edge / face, or a lattice's points / grid lines / cells) as its own undo step,
 * converting the stored selection into the new domain with Blender's flush-down / derive-up rules (see
 * [MeshSelectionOps.changeSelectMode]). The conversion is lossy by design, so the snapshot is what makes it
 * recoverable. A no-op in a mode that edits no elements ([editsElements]) — so the bound 1 / 2 / 3 commands need
 * no context guard of their own and the keymap stays mode-agnostic — and a no-op when already in [selectMode].
 * Not a document edit — leaves dirty untouched.
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
 * Re-seeds the session onto one owner (Alt+Q's switch), as ONE undo step covering both selections: the session's
 * owners become just [owner] (with its remembered elements restored where they still fit, in the select mode the
 * session keeps), and the OBJECT selection
 * moves onto the same item - so leaving the mode keeps the switched item instead of reviving the selection the mode
 * was entered with.  The outgoing owners' element selections stash into the per-owner memory first, and the
 * remembered-owner memory follows.  A no-op unless the mode edits elements and [owner] can join a session there
 * ([isSessionOwner]: in Edit mode a meshed drawable or a warp with a rest lattice, in Pose mode a warp the pose draws).
 *
 * Built as one combined snapshot push (never chained setSelection + setMeshSelection - each of
 * those snapshots the OTHER selection's pre-change value, which would tear the pair across two
 * undo steps).
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