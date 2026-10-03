package org.umamo.ui.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.deleteTarget
import org.umamo.edit.setDrawableParentDeformer
import org.umamo.edit.withDeformerDeleted
import org.umamo.edit.withDrawableParentDeformer
import org.umamo.render.localPositionsKeepingRest
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.hasUnkeyedGeometry
import org.umamo.storage.UmamoLog

/*
 * Rebinding a drawable to another deformer without moving its art.
 *
 * A drawable's keyform-space base lives in its parent's space, so a rebinding has to re-express the base in
 * the new parent's space for the art to stay put.  For a drawable with no keyed geometry the base is its whole
 * shape, and the evaluator's inverse carries it over; that inverse is :render's, which is why this sits in :ui
 * beside DrawableWorldTransform.kt.  A keyed drawable's shapes all live in the old parent's space and cannot
 * follow one base, so it keeps its numbers and its art follows the new parent, as every rebinding did before.
 */

/**
 * Binds drawable [id] to [parentDeformerId] (null unbinds) as one undo step, keeping it where it rests when it
 * has no keyed geometry.  When the new chain cannot map it there, the binding is still made, its base kept, and
 * the log says so.
 *
 * @param DrawableId  id               The drawable to rebind.
 * @param DeformerId? parentDeformerId The deformer that deforms it, or null to unbind.
 */
internal fun EditorSession.setDrawableParentDeformerKeepingRest(id: DrawableId, parentDeformerId: DeformerId?) {
	val before = model.value
	val drawable = before.drawables.firstOrNull { candidate -> candidate.id == id }
	if (drawable == null || drawable.parentDeformerId == parentDeformerId || drawable.mesh == null || !drawable.hasUnkeyedGeometry) {
		setDrawableParentDeformer(id, parentDeformerId)
		return
	}
	val local = localPositionsKeepingRest(before, before.withDrawableParentDeformer(id, parentDeformerId), listOf(id))[id]
	if (local == null) {
		UmamoLog.warn("rebound ${id.raw} without keeping its place: the deformer chain cannot map it")
	}
	setDrawableParentDeformer(id, parentDeformerId, local)
}

/**
 * Deletes the entity [target] names as one undo step (see deleteTarget); a deleted deformer's drawables with
 * no keyed geometry keep their place as they re-home to its parent.
 *
 * @param SelectionTarget target  The entity to delete.
 * @param Boolean         cascade For a part, true to delete the subtree, false to ungroup; ignored otherwise.
 */
internal fun EditorSession.deleteTargetKeepingRest(target: SelectionTarget, cascade: Boolean) {
	if (target !is SelectionTarget.Deformer) {
		deleteTarget(target, cascade)
		return
	}
	val before = model.value
	val rehomed =
		before.drawables
			.filter { drawable -> drawable.parentDeformerId == target.id && drawable.mesh != null && drawable.hasUnkeyedGeometry }
			.map { drawable -> drawable.id }
	val locals = localPositionsKeepingRest(before, before.withDeformerDeleted(target.id), rehomed)
	val unconverted = rehomed.filter { drawableId -> drawableId !in locals }
	if (unconverted.isNotEmpty()) {
		UmamoLog.warn("deleted ${target.id.raw}; ${unconverted.size} drawable(s) re-homed without keeping their place: " + unconverted.joinToString { drawableId -> drawableId.raw })
	}
	deleteTarget(target, cascade, locals)
}