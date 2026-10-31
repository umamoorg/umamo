package org.umamo.ui.transform

import org.umamo.edit.Change
import org.umamo.edit.DeformerChange
import org.umamo.edit.DrawableChange
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.booleanValue
import org.umamo.edit.property.withDrawableParentDeformer
import org.umamo.edit.structure.RehomeParameterKeys
import org.umamo.edit.structure.deleteDeformer
import org.umamo.edit.structure.deleteTarget
import org.umamo.edit.structure.moveDeformer
import org.umamo.edit.structure.rehomeParameters
import org.umamo.edit.structure.withDeformerMoved
import org.umamo.render.ChildrenRehomed
import org.umamo.render.RehomedChildren
import org.umamo.render.childrenRehomed
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog

/*
 * Re-parenting and deleting without moving art.
 *
 * Whatever lives under a deformer is stored in that deformer's space, so giving it another parent - re-parenting a
 * drawable, re-nesting a deformer, deleting its parent so it falls to the grandparent - would move it.  Each moved
 * child is re-expressed in its new space instead (childrenRehomed), keyed motion included, so it rests where it
 * rested.  The conversion is :render's, which is why this sits in :ui beside the other transform lowerings.
 *
 * A re-parent registers on the operation strip with Keep in Place, so a rigger who wanted the old behaviour - the
 * item keeping its numbers and following its new parent - unchecks it.  A child that cannot be kept (its new parent
 * folds over or cannot reach it) still moves with the edit, and a notice and the log say so, since its art visibly
 * moves.
 */

/**
 * Binds drawable [id] to [parentDeformerId] (null unbinds) as one undo step, keeping it where it rests - keyed
 * motion included - and registers the binding on the operation strip in [areaId] with Keep in Place.  Binding a
 * drawable to the parent it has changes nothing.
 *
 * @param DrawableId  id               The drawable to rebind.
 * @param DeformerId? parentDeformerId The deformer that deforms it, or null to unbind.
 * @param String?     areaId           The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.setDrawableParentKeepingRest(id: DrawableId, parentDeformerId: DeformerId?, areaId: String? = null) {
	val drawable = model.value.drawables.firstOrNull { candidate -> candidate.id == id }

	if (drawable == null || drawable.parentDeformerId == parentDeformerId) {
		return
	}

	rehomeAdjustable(DrawableChange.SetParentDeformer(id, parentDeformerId), RehomedChildren(drawables = listOf(id)), areaId) { current ->
		current.withDrawableParentDeformer(id, parentDeformerId, localPositions = null)
	}
}

/**
 * Re-nests deformer [id] under [newParentId] (null for the root), before [beforeId] among its new siblings, as one
 * undo step.  A move to another parent keeps the deformer where it rests - its lattice or pivot, its keyed motion, and
 * so everything under it - and registers on the operation strip in [areaId] with Keep in Place; a reorder among its
 * siblings moves nothing and registers nothing.  A move into its own subtree is refused.
 *
 * @param DeformerId  id          The deformer to move.
 * @param DeformerId? newParentId Its new parent, or null for the root.
 * @param DeformerId? beforeId    The sibling it lands before, or null for the end.
 * @param String?     areaId      The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.moveDeformerKeepingRest(id: DeformerId, newParentId: DeformerId?, beforeId: DeformerId?, areaId: String? = null) {
	val deformer = model.value.deformerById[id] ?: return

	if (deformer.parent == newParentId) {
		moveDeformer(id, newParentId, beforeId)

		return
	}

	rehomeAdjustable(DeformerChange.Move(id, newParentId, beforeId), RehomedChildren(deformers = listOf(id)), areaId) { current ->
		current.withDeformerMoved(id, newParentId, beforeId)
	}
}

/**
 * Deletes the entity [target] names as one undo step (see deleteTarget).  A deleted deformer's direct children - its
 * drawables, warps, and rotations - fall to its parent kept where they rest, keyed motion included; one the parent
 * cannot keep in place still falls, and a notice and the log say how many moved.
 *
 * @param SelectionTarget target  The entity to delete.
 * @param Boolean         cascade For a part, true to delete the subtree, false to ungroup; ignored otherwise.
 */
internal fun EditorSession.deleteTargetKeepingRest(target: SelectionTarget, cascade: Boolean) {
	if (target !is SelectionTarget.Deformer) {
		deleteTarget(target, cascade) { unwrapped -> unwrapped }

		return
	}

	val before = model.value
	val children =
		RehomedChildren(
			drawables = before.drawables.filter { drawable -> drawable.parentDeformerId == target.id && drawable.mesh != null }.map { drawable -> drawable.id },
			deformers = before.deformers.filter { deformer -> deformer.parent == target.id }.map { deformer -> deformer.id },
		)
	var rehome: ChildrenRehomed? = null
	deleteDeformer(target.id) { unwrapped -> childrenRehomed(before, unwrapped, children).also { kept -> rehome = kept }.model }
	rehome?.let { kept -> reportRehome(before, kept, "deleted ${target.id.raw}") }
}

/**
 * Commits a re-parent as one undo step with [children] kept where they rest, and registers it on the operation strip
 * in [areaId] with Keep in Place: a rerun from the step's base lands the conversion again, or the plain move when it
 * is unchecked.  A move [moved] refuses (answers its model back) records nothing.
 *
 * @param Change change The edit descriptor.
 * @param RehomedChildren children The items the move re-parents.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 * @param Function moved The model with the move made and the moved items' numbers untouched.
 */
private fun EditorSession.rehomeAdjustable(change: Change, children: RehomedChildren, areaId: String?, moved: (PuppetModel) -> PuppetModel) {
	val before = model.value
	val unconverted = moved(before)

	if (unconverted === before) {
		return
	}

	val rehome = childrenRehomed(before, unconverted, children)
	mutate(change) { rehome.model }
	reportRehome(before, rehome, "re-parented")
	registerAdjustableOperation(model.value, areaId, rehomeParameters(keepInPlace = true)) { record ->
		val base = record.baseSnapshot.model
		val rerunMoved = moved(base)
		val landed =
			if (record.parameters.booleanValue(RehomeParameterKeys.KEEP_IN_PLACE, true)) {
				childrenRehomed(base, rerunMoved, children).also { kept -> reportRehome(base, kept, "re-parented") }.model
			} else {
				rerunMoved
			}
		amendLastCommit(record, landed)
	}
}

/**
 * Tells the rigger, near the cursor and in the log, which re-homed items could not be kept in place: one by its name,
 * several by their count.  Says nothing when every item was kept.
 *
 * @param PuppetModel before The model the items were re-homed from, which names them.
 * @param ChildrenRehomed rehome The rehome.
 * @param String action What re-homed them, for the log.
 */
internal fun EditorSession.reportRehome(before: PuppetModel, rehome: ChildrenRehomed, action: String) {
	val names =
		rehome.unconvertedDrawables.map { drawableId -> before.drawables.firstOrNull { drawable -> drawable.id == drawableId }?.name ?: drawableId.raw } +
			rehome.unconvertedDeformers.map { deformerId -> before.deformerById[deformerId]?.name ?: deformerId.raw }

	if (names.isEmpty()) {
		return
	}

	UmamoLog.warn("$action; ${names.size} item(s) re-homed without keeping their place: " + names.joinToString())

	if (names.size == 1) {
		emitNotice("notice.reparent.placeNotKept", arguments = listOf(names.single()))
	} else {
		emitNotice("notice.reparent.placesNotKept", arguments = listOf(names.size.toString()))
	}
}