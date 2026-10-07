package org.umamo.edit.structure

import org.umamo.edit.Change
import org.umamo.edit.DeformerChange
import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorSession
import org.umamo.edit.PartChange
import org.umamo.edit.SelectionTarget
import org.umamo.edit.subtreeTargets

/*
 * Target-aware convenience edits on an EditorSession: a single selectable entity (part/drawable/deformer)
 * is toggled or renamed as one undo step, and the subtree variants (a Blender-style Shift+Click on an
 * outliner restriction icon) flip a whole outliner subtree in one step.  These dispatch the right
 * Change + transform per target kind so call sites (the outliner's per-row eye and inline rename) stay a
 * thin one-liner.  The selection-wide visibility toggle stays separate (see withSelectionVisibility) —
 * that one flips the whole selection.
 *
 * 対象1件（パーツ／描画／デフォーマ）を1段の取り消し単位で切り替え・改名するセッション補助。サブツリー版
 * （Shift クリック）は配下全体を1段で切り替える。
 */

/**
 * Toggles the visibility of one [target] as a single undo step, dispatching the part- or drawable-specific
 * change and transform.  A deformer has no visibility flag, so it is a no-op.
 *
 * @param SelectionTarget target The entity whose eyeball to flip.
 */
fun EditorSession.toggleVisibility(target: SelectionTarget) {
	val newVisible = !model.value.visibilityOf(target)
	when (target) {
		is SelectionTarget.Part ->
			mutate(PartChange.SetVisibility(target.id, newVisible)) { model -> model.withPartVisibility(target.id, newVisible) }
		is SelectionTarget.Drawable ->
			mutate(DrawableChange.SetVisibility(target.id, newVisible)) { model -> model.withDrawableVisibility(target.id, newVisible) }
		is SelectionTarget.Deformer -> {
			// Deformers have no visibility flag; nothing to toggle.
		}
	}
}

/**
 * Renames one [target] to [newName] as a single undo step, dispatching the per-kind change and transform.
 * A blank name (after trimming) is ignored, so an empty commit keeps the old name.
 *
 * @param SelectionTarget target The entity to rename.
 * @param String newName The requested new name.
 */
fun EditorSession.rename(target: SelectionTarget, newName: String) {
	val trimmed = newName.trim()
	if (trimmed.isEmpty()) {
		return
	}
	when (target) {
		is SelectionTarget.Part -> mutate(PartChange.Rename(target.id, trimmed)) { model -> model.withPartName(target.id, trimmed) }
		is SelectionTarget.Drawable -> mutate(DrawableChange.Rename(target.id, trimmed)) { model -> model.withDrawableName(target.id, trimmed) }
		is SelectionTarget.Deformer -> mutate(DeformerChange.Rename(target.id, trimmed)) { model -> model.withDeformerName(target.id, trimmed) }
	}
}

/**
 * Toggles the viewport selectability of one [target] as a single undo step, dispatching the per-kind
 * change and transform. Unlike visibility, a deformer has a selectable flag too.
 *
 * @param SelectionTarget target The entity whose selectability to flip.
 */
fun EditorSession.toggleSelectable(target: SelectionTarget) {
	val newSelectable = !model.value.selectableOf(target)
	when (target) {
		is SelectionTarget.Part ->
			mutate(PartChange.SetSelectable(target.id, newSelectable)) { model -> model.withPartSelectable(target.id, newSelectable) }
		is SelectionTarget.Drawable ->
			mutate(DrawableChange.SetSelectable(target.id, newSelectable)) { model -> model.withDrawableSelectable(target.id, newSelectable) }
		is SelectionTarget.Deformer ->
			mutate(DeformerChange.SetSelectable(target.id, newSelectable)) { model -> model.withDeformerSelectable(target.id, newSelectable) }
	}
}

/**
 * Toggles viewport selectability for [target] and its whole outliner subtree as one undo step: the new
 * value is the flip of the clicked target's current state, applied uniformly to every subtree entity (a
 * Blender-style Shift+Click), so a mixed subtree lands on one state.  Reuses the clicked target's
 * per-kind SetSelectable change, mirroring the selection-wide visibility command.
 *
 * @param SelectionTarget target The clicked subtree root.
 */
fun EditorSession.toggleSelectableSubtree(target: SelectionTarget) {
	val newSelectable = !model.value.selectableOf(target)
	val change: Change =
		when (target) {
			is SelectionTarget.Part -> PartChange.SetSelectable(target.id, newSelectable)
			is SelectionTarget.Drawable -> DrawableChange.SetSelectable(target.id, newSelectable)
			is SelectionTarget.Deformer -> DeformerChange.SetSelectable(target.id, newSelectable)
		}
	mutate(change) { model -> model.withSelectionSelectable(model.subtreeTargets(target).toSet(), newSelectable) }
}

/**
 * Toggles visibility for [target] and its whole outliner subtree as one undo step, applied uniformly like
 * the selectable variant.  Deformers have no visibility flag, so a deformer target is a no-op (the
 * outliner shows no eye for one anyway) and any deformer inside a part subtree is skipped by
 * withSelectionVisibility.
 *
 * @param SelectionTarget target The clicked subtree root.
 */
fun EditorSession.toggleVisibilitySubtree(target: SelectionTarget) {
	val newVisible = !model.value.visibilityOf(target)
	val change: Change =
		when (target) {
			is SelectionTarget.Part -> PartChange.SetVisibility(target.id, newVisible)
			is SelectionTarget.Drawable -> DrawableChange.SetVisibility(target.id, newVisible)
			// A deformer has no visibility flag; nothing to toggle.
			is SelectionTarget.Deformer -> return
		}
	mutate(change) { model -> model.withSelectionVisibility(model.subtreeTargets(target).toSet(), newVisible) }
}