package org.umamo.edit.structure

import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * The per-target flag transforms over the immutable PuppetModel - visibility, name, and selectable for a
 * part, a drawable, or a deformer - and the selection-wide forms that flip a whole Selection at once.  Pure
 * and copy-on-write like every other transform; the EditorSession wrappers that record them as undo steps
 * live in SessionTargetEdits.
 */

/**
 * Returns a copy of [this] with the part [id]'s Parts-panel visibility set to [visible], sharing every
 * other part and the rest of the model. A no-op id (no such part, or the flag already matches) returns
 * the same instance, so callers can compare by reference to detect a real change.
 *
 * @param PartId id The part to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with that part's visibility updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartVisibility(id: PartId, visible: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isVisible == visible) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isVisible = visible)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s own Parts-panel visibility set to [visible], sharing
 * every other drawable and the rest of the model. A no-op id (no such drawable, or the flag already
 * matches) returns the same instance.
 *
 * @param DrawableId id The drawable to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with that drawable's visibility updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableVisibility(id: DrawableId, visible: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].isVisible == visible) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(isVisible = visible)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s display name set to [name], sharing every other entity.
 * A no-op id (no such part, or the name already matches) returns the same instance.
 *
 * @param PartId id The part to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that part renamed, or [this] if nothing changed.
 */
fun PuppetModel.withPartName(id: PartId, name: String): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].name == name) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(name = name)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s display name set to [name], sharing every other
 * entity. A no-op id (no such drawable, or the name already matches) returns the same instance.
 *
 * @param DrawableId id The drawable to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that drawable renamed, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableName(id: DrawableId, name: String): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].name == name) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(name = name)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s display name set to [name], sharing every other
 * entity. Handles both deformer kinds. A no-op id (no such deformer, or the name already matches)
 * returns the same instance.
 *
 * @param DeformerId id The deformer to rename.
 * @param String name The new display name.
 * @return PuppetModel The model with that deformer renamed, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerName(id: DeformerId, name: String): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].name == name) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(name = name)
			is Deformer.Rotation -> deformer.copy(name = name)
		}
	return copy(deformers = updated)
}

/**
 * Returns a copy of [this] with the part [id]'s selectable flag set to [selectable], sharing every other
 * entity. A no-op id (no such part, or the flag already matches) returns the same instance.
 *
 * @param PartId id The part whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that part's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withPartSelectable(id: PartId, selectable: Boolean): PuppetModel {
	val index = parts.indexOfFirst { part -> part.id == id }
	if (index < 0 || parts[index].isSelectable == selectable) {
		return this
	}
	val updated = parts.toMutableList()
	updated[index] = updated[index].copy(isSelectable = selectable)
	return copy(parts = updated)
}

/**
 * Returns a copy of [this] with the drawable [id]'s selectable flag set to [selectable], sharing every
 * other entity. A no-op id (no such drawable, or the flag already matches) returns the same instance.
 *
 * @param DrawableId id The drawable whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that drawable's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withDrawableSelectable(id: DrawableId, selectable: Boolean): PuppetModel {
	val index = drawables.indexOfFirst { drawable -> drawable.id == id }
	if (index < 0 || drawables[index].isSelectable == selectable) {
		return this
	}
	val updated = drawables.toMutableList()
	updated[index] = updated[index].copy(isSelectable = selectable)
	return copy(drawables = updated)
}

/**
 * Returns a copy of [this] with the deformer [id]'s selectable flag set to [selectable], handling both
 * deformer kinds and sharing every other entity. A no-op id returns the same instance.
 *
 * @param DeformerId id The deformer whose selectability to set.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with that deformer's selectability updated, or [this] if nothing changed.
 */
fun PuppetModel.withDeformerSelectable(id: DeformerId, selectable: Boolean): PuppetModel {
	val index = deformers.indexOfFirst { deformer -> deformer.id == id }
	if (index < 0 || deformers[index].isSelectable == selectable) {
		return this
	}
	val updated = deformers.toMutableList()
	updated[index] =
		when (val deformer = updated[index]) {
			is Deformer.Warp -> deformer.copy(isSelectable = selectable)
			is Deformer.Rotation -> deformer.copy(isSelectable = selectable)
		}
	return copy(deformers = updated)
}

/**
 * The current Parts-panel visibility of [target] in [this]. A deformer has no visibility flag, so it
 * reports true (it is never "hidden"); a missing part/drawable also reports true.
 *
 * @param SelectionTarget target The entity to query.
 * @return Boolean The entity's visibility (true when it has none).
 */
fun PuppetModel.visibilityOf(target: SelectionTarget): Boolean =
	when (target) {
		is SelectionTarget.Part -> parts.firstOrNull { it.id == target.id }?.isVisible ?: true
		is SelectionTarget.Drawable -> drawables.firstOrNull { it.id == target.id }?.isVisible ?: true
		is SelectionTarget.Deformer -> true
	}

/**
 * Returns a copy of [this] with every part and drawable in [targets] set to [visible], structurally
 * sharing untouched entities; deformer targets are skipped (they have no visibility flag). Folding all
 * targets into one model makes the whole multi-selection toggle a single undo step.
 *
 * @param Set targets The selected targets to retoggle.
 * @param Boolean visible The new visibility.
 * @return PuppetModel The model with those entities' visibility updated.
 */
fun PuppetModel.withSelectionVisibility(targets: Set<SelectionTarget>, visible: Boolean): PuppetModel {
	var next = this
	for (target in targets) {
		next =
			when (target) {
				is SelectionTarget.Part -> next.withPartVisibility(target.id, visible)
				is SelectionTarget.Drawable -> next.withDrawableVisibility(target.id, visible)
				is SelectionTarget.Deformer -> next
			}
	}
	return next
}

/**
 * Whether [target] is viewport-selectable in [this] model. A missing entity reports true (selectable).
 *
 * @param SelectionTarget target The entity to query.
 * @return Boolean The entity's selectable state.
 */
fun PuppetModel.selectableOf(target: SelectionTarget): Boolean =
	when (target) {
		is SelectionTarget.Part -> parts.firstOrNull { it.id == target.id }?.isSelectable ?: true
		is SelectionTarget.Drawable -> drawables.firstOrNull { it.id == target.id }?.isSelectable ?: true
		is SelectionTarget.Deformer -> deformers.firstOrNull { it.id == target.id }?.isSelectable ?: true
	}

/**
 * Returns a copy of [this] with every entity in [targets] set to [selectable], structurally sharing
 * untouched entities.  Unlike the visibility counterpart (withSelectionVisibility) this covers deformers
 * too — they carry the flag.  Folding all targets into one model makes a whole subtree toggle a single
 * undo step.
 *
 * @param Set targets The targets to retoggle.
 * @param Boolean selectable The new selectable state.
 * @return PuppetModel The model with those entities' selectability updated.
 */
fun PuppetModel.withSelectionSelectable(targets: Set<SelectionTarget>, selectable: Boolean): PuppetModel {
	var next = this
	for (target in targets) {
		next =
			when (target) {
				is SelectionTarget.Part -> next.withPartSelectable(target.id, selectable)
				is SelectionTarget.Drawable -> next.withDrawableSelectable(target.id, selectable)
				is SelectionTarget.Deformer -> next.withDeformerSelectable(target.id, selectable)
			}
	}
	return next
}