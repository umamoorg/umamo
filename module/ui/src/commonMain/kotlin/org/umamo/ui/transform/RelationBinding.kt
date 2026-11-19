package org.umamo.ui.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.RelationChange
import org.umamo.edit.SelectionTarget
import org.umamo.edit.property.setDeformerPart
import org.umamo.edit.property.setDrawableMaskedBy
import org.umamo.edit.property.setPartComposite
import org.umamo.edit.relation.RelationSlot
import org.umamo.edit.relation.masksOf
import org.umamo.edit.relation.relationAdmits
import org.umamo.edit.relation.relationBound
import org.umamo.edit.relation.relationPeers
import org.umamo.edit.relation.withMaskAdded
import org.umamo.edit.relation.withMaskRemoved
import org.umamo.edit.relation.withOwner
import org.umamo.edit.relation.withParentPart
import org.umamo.edit.structure.moveOrgChild
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * The one write path behind a relation field - its list, its eyedropper, and its context menu - and Copy to Selected.
 * A parent deformer is bound keeping the object where it rests (Rehome.kt), so this sits beside the other
 * keep-in-place lowerings.  Every write reads the live model: a pick resolves long after it was armed, and the panel
 * stays editable in between.
 */

/** The notice Copy to Selected raises for the selected objects the binding was refused for. */
private const val COPY_SKIPPED_NOTICE = "notice.relation.copySkipped"

/**
 * Binds [slot] to [target] as one undo step: a single relation takes [target] as its object (null clears it), a mask
 * list adds it, and a mask row only unbinds (a target changes nothing there).  A binding the slot does not admit, or
 * one it already holds, records nothing.  A parent deformer's binding registers Keep in Place on the operation strip
 * in [areaId].
 *
 * @param RelationSlot slot The relation.
 * @param SelectionTarget? target The object to bind, or null to unbind.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.bindRelation(slot: RelationSlot, target: SelectionTarget?, areaId: String? = null) {
	val current = model.value

	if (target != null && slot !is RelationSlot.Mask && !current.relationAdmits(slot, target)) {
		return
	}

	// Re-binding what a single relation holds would still move it: a tree move appends at the destination, and a
	// deformer re-nested under its own parent goes to the end of its siblings.
	if ((slot is RelationSlot.ParentPart || slot is RelationSlot.ParentDeformer) && current.relationBound(slot) == target) {
		return
	}

	when (slot) {
		is RelationSlot.ParentPart -> {
			bindParentPart(slot.owner, (target as? SelectionTarget.Part)?.id)
		}

		is RelationSlot.ParentDeformer -> {
			bindParentDeformer(slot.owner, (target as? SelectionTarget.Deformer)?.id, areaId)
		}

		is RelationSlot.Masks -> {
			target?.let { entry -> writeMasks(slot.owner, current.withMaskAdded(slot.owner, entry)) }
		}

		is RelationSlot.Mask -> {
			if (target == null) {
				writeMasks(slot.owner, current.withMaskRemoved(slot.owner, slot.entry))
			}
		}
	}
}

/**
 * Gives the selected objects of [slot]'s owner's kind the binding the owner holds, as one undo step: its owning part
 * or its parent deformer (an unbound one included), or a mask row's entry added to each one's masks.  An object that
 * already holds it is passed over; one the binding is refused for (it would nest inside itself, or it takes no such
 * mask) is skipped, and one notice says how many were.  A parent deformer's copy keeps every object where it rests and
 * registers Keep in Place on the operation strip in [areaId].  A whole mask list copies nothing.
 *
 * @param RelationSlot slot The relation, on the object whose binding is copied.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
internal fun EditorSession.copyRelationToSelected(slot: RelationSlot, areaId: String? = null) {
	if (slot is RelationSlot.Masks) {
		return
	}

	val current = model.value
	val binding = current.relationBound(slot)

	// A row whose entry is gone has nothing to copy; an unbound single relation copies the unbinding.
	if (slot is RelationSlot.Mask && binding == null) {
		return
	}

	val taking = ArrayList<SelectionTarget>()
	var refused = 0

	for (peer in relationPeers(slot, selection.value)) {
		val peerSlot = slot.withOwner(peer)
		val alreadyHeld = if (slot is RelationSlot.Mask) binding in current.masksOf(peer) else current.relationBound(peerSlot) == binding

		when {
			alreadyHeld -> {}

			binding == null || current.relationAdmits(peerSlot, binding) -> {
				taking.add(peer)
			}

			else -> {
				refused++
			}
		}
	}

	if (refused > 0) {
		emitNotice(COPY_SKIPPED_NOTICE, arguments = listOf(refused.toString()))
	}

	if (taking.isEmpty()) {
		return
	}

	val change = RelationChange.CopyToSelected(taking)

	when (slot) {
		is RelationSlot.ParentPart -> {
			val partId = (binding as? SelectionTarget.Part)?.id
			mutate(change) { model -> taking.fold(model) { bound, peer -> bound.withParentPart(peer, partId) } }
		}

		is RelationSlot.ParentDeformer -> {
			val deformerId = (binding as? SelectionTarget.Deformer)?.id
			setDrawablesParentKeepingRest(taking.mapNotNull { peer -> (peer as? SelectionTarget.Drawable)?.id }, deformerId, change, areaId)
			moveDeformersKeepingRest(taking.mapNotNull { peer -> (peer as? SelectionTarget.Deformer)?.id }, deformerId, change, areaId)
		}

		is RelationSlot.Mask -> {
			mutate(change) { model -> taking.fold(model) { masked, peer -> masked.withMaskAdded(peer, slot.entry) } }
		}

		is RelationSlot.Masks -> {}
	}
}

/**
 * Binds [owner] to [partId] through the edit its kind's relation has: a tree move for a drawable or a part, the part
 * field for a deformer.
 *
 * @param SelectionTarget owner The drawable, part, or deformer.
 * @param PartId? partId The part, or null for the top level.
 */
private fun EditorSession.bindParentPart(owner: SelectionTarget, partId: PartId?) {
	when (owner) {
		// Part membership lives only in the org tree, so re-homing a drawable is a tree move.  It appends at the
		// destination, which changes draw order (Cubism does the same).
		is SelectionTarget.Drawable -> moveOrgChild(OrgChild.Drawable(owner.id), partId, null)

		is SelectionTarget.Part -> moveOrgChild(OrgChild.Part(owner.id), partId, null)

		is SelectionTarget.Deformer -> setDeformerPart(owner.id, partId)
	}
}

/**
 * Binds [owner] to [deformerId], keeping it where it rests.
 *
 * @param SelectionTarget owner The drawable or deformer.
 * @param DeformerId? deformerId The deformer, or null to unbind.
 * @param String? areaId The area the strip should show in, or null for nowhere.
 */
private fun EditorSession.bindParentDeformer(owner: SelectionTarget, deformerId: DeformerId?, areaId: String?) {
	when (owner) {
		is SelectionTarget.Drawable -> {
			setDrawableParentKeepingRest(owner.id, deformerId, areaId)
		}

		is SelectionTarget.Deformer -> {
			moveDeformerKeepingRest(owner.id, deformerId, null, areaId)
		}

		is SelectionTarget.Part -> {}
	}
}

/**
 * Commits [owner]'s masks as [changed] holds them, through the edit its kind's masks have, so the step carries the
 * same label the field's own edits do.  Nothing is recorded when [changed] is the model as it is.
 *
 * @param SelectionTarget owner The drawable or part.
 * @param PuppetModel changed The model with the masks changed.
 */
private fun EditorSession.writeMasks(owner: SelectionTarget, changed: PuppetModel) {
	if (changed === model.value) {
		return
	}

	when (owner) {
		is SelectionTarget.Drawable -> {
			changed.drawables.firstOrNull { drawable -> drawable.id == owner.id }?.let { drawable -> setDrawableMaskedBy(owner.id, drawable.maskedBy) }
		}

		is SelectionTarget.Part -> {
			changed.parts.firstOrNull { part -> part.id == owner.id }?.let { part -> setPartComposite(owner.id, part.composite) }
		}

		is SelectionTarget.Deformer -> {}
	}
}