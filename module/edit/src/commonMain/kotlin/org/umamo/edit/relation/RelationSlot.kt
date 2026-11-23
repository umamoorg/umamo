package org.umamo.edit.relation

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerSelfAndDescendants
import org.umamo.runtime.model.parentPartByPart
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.partSelfAndDescendants

/*
 * The relations an object's Properties fields bind - its parent part, its parent deformer, its clip masks - as
 * data, with the one eligibility rule each answers to.  The field's list, its eyedropper, its context menu, and Copy
 * to Selected all read these, so none of them can offer a binding another would refuse.
 */

/**
 * One relation an object holds, named by the object that holds it.  A slot is a value, so it doubles as the
 * identity of the field that shows it: two fields over the same relation of the same object are the same field.
 */
sealed interface RelationSlot {
	/** The object whose relation this is. */
	val owner: SelectionTarget

	/**
	 * A parent part of a part, drawable, or deformer.
	 *
	 * @property SelectionTarget owner The drawable, deformer, or part.
	 */
	data class ParentPart(override val owner: SelectionTarget) : RelationSlot

	/**
	 * A parent deformer of a drawable or deformer.
	 *
	 * @property SelectionTarget owner The drawable or deformer.
	 */
	data class ParentDeformer(override val owner: SelectionTarget) : RelationSlot

	/**
	 * [owner]'s clip masks as a whole: what the list's add field and eyedropper bind into.  A drawable is clipped
	 * by drawables; an Isolated part's composite by drawables and parts.
	 *
	 * @property SelectionTarget owner The drawable or part.
	 */
	data class Masks(override val owner: SelectionTarget) : RelationSlot

	/**
	 * One entry of [owner]'s clip masks: a row of the list.
	 *
	 * @property SelectionTarget owner The drawable or part.
	 * @property SelectionTarget entry The drawable or part that clips it.
	 */
	data class Mask(override val owner: SelectionTarget, val entry: SelectionTarget) : RelationSlot
}

/**
 * This slot over [peer] in place of its owner: the same relation of another object.
 *
 * @param SelectionTarget peer The other object.
 * @return RelationSlot The slot.
 */
fun RelationSlot.withOwner(peer: SelectionTarget): RelationSlot =
	when (this) {
		is RelationSlot.ParentPart -> copy(owner = peer)
		is RelationSlot.ParentDeformer -> copy(owner = peer)
		is RelationSlot.Masks -> copy(owner = peer)
		is RelationSlot.Mask -> copy(owner = peer)
	}

/**
 * What [slot] binds now: the parent part or the parent deformer, or the row's entry while the owner still holds
 * it; null when it binds nothing, and always for a whole mask list, which binds many.
 *
 * @param RelationSlot slot The relation.
 * @return SelectionTarget? The bound object, or null.
 */
fun PuppetModel.relationBound(slot: RelationSlot): SelectionTarget? =
	when (slot) {
		is RelationSlot.ParentPart -> parentPartOf(slot.owner)?.let { partId -> SelectionTarget.Part(partId) }
		is RelationSlot.ParentDeformer -> parentDeformerOf(slot.owner)?.let { deformerId -> SelectionTarget.Deformer(deformerId) }
		is RelationSlot.Masks -> null
		is RelationSlot.Mask -> slot.entry.takeIf { entry -> entry in masksOf(slot.owner) }
	}

/**
 * Whether [slot] may bind [candidate]: for a single relation, whether it may become the bound object, and for the
 * masks (the list or one of its rows), whether it may be added to the owner's.  See [relationAdmitter] for the rules;
 * a caller testing many candidates takes the admitter once instead.
 *
 * @param RelationSlot slot The relation.
 * @param SelectionTarget candidate The object to bind.
 * @return Boolean True when the binding is legal.
 */
fun PuppetModel.relationAdmits(slot: RelationSlot, candidate: SelectionTarget): Boolean = relationAdmitter(slot)(candidate)

/**
 * The test [relationAdmits] applies for [slot], with the hierarchy and mask lookups it needs made once, so a list
 * filtering every candidate stays linear.  The rules are the hierarchy's and the mask's own: nothing nests inside
 * itself or its own subtree, nothing masks itself, a mask already applied is not applied twice, a part takes masks
 * only while Isolated, and only the kinds a relation holds pass.
 *
 * @param RelationSlot slot The relation.
 * @return Function The test, true for a candidate the slot may bind.
 */
fun PuppetModel.relationAdmitter(slot: RelationSlot): (SelectionTarget) -> Boolean {
	val owner = slot.owner

	return when (slot) {
		is RelationSlot.ParentPart -> {
			val known = parts.mapTo(HashSet()) { part -> part.id }
			val cyclic = (owner as? SelectionTarget.Part)?.let { part -> partSelfAndDescendants(part.id) }.orEmpty()
			val admitter: (SelectionTarget) -> Boolean = { candidate -> candidate is SelectionTarget.Part && candidate.id in known && candidate.id !in cyclic }
			admitter
		}

		is RelationSlot.ParentDeformer -> {
			val cyclic = (owner as? SelectionTarget.Deformer)?.let { deformer -> deformerSelfAndDescendants(deformer.id) }.orEmpty()
			val admitter: (SelectionTarget) -> Boolean = { candidate -> candidate is SelectionTarget.Deformer && candidate.id in deformerById && candidate.id !in cyclic }
			admitter
		}

		is RelationSlot.Masks, is RelationSlot.Mask -> {
			maskAdmitter(owner)
		}
	}
}

/**
 * The objects Copy to Selected gives [slot]'s binding to: the selected objects of its owner's kind, in the order
 * the selection holds them, the owner left out.
 *
 * @param RelationSlot slot The relation.
 * @param Selection selection The object selection.
 * @return List<SelectionTarget> The peers.
 */
fun relationPeers(slot: RelationSlot, selection: Selection): List<SelectionTarget> = selection.targets.filter { target -> target != slot.owner && target::class == slot.owner::class }

/**
 * The part that holds [owner] in the org tree or, for a deformer, its part field.
 *
 * @param SelectionTarget owner The object.
 * @return PartId? The part, or null at the root or for an object the model does not hold.
 */
private fun PuppetModel.parentPartOf(owner: SelectionTarget): PartId? =
	when (owner) {
		is SelectionTarget.Drawable -> partByDrawable()[owner.id]
		is SelectionTarget.Deformer -> deformerById[owner.id]?.partId
		is SelectionTarget.Part -> parentPartByPart()[owner.id]
	}

/**
 * The deformer that deforms [owner], or null for a part or an object under none.
 *
 * @param SelectionTarget owner The object.
 * @return DeformerId? The parent deformer, or null.
 */
private fun PuppetModel.parentDeformerOf(owner: SelectionTarget): DeformerId? =
	when (owner) {
		is SelectionTarget.Drawable -> drawables.firstOrNull { drawable -> drawable.id == owner.id }?.parentDeformerId
		is SelectionTarget.Deformer -> deformerById[owner.id]?.parent
		is SelectionTarget.Part -> null
	}

/**
 * Every object that clips [owner]: a drawable's masking drawables, or an Isolated part's masking drawables then
 * parts.  Empty for anything else.
 *
 * @param SelectionTarget owner The drawable or part.
 * @return List<SelectionTarget> The masks, in stored order.
 */
fun PuppetModel.masksOf(owner: SelectionTarget): List<SelectionTarget> =
	when (owner) {
		is SelectionTarget.Drawable -> {
			drawables.firstOrNull { drawable -> drawable.id == owner.id }?.maskedBy.orEmpty().map { maskId -> SelectionTarget.Drawable(maskId) }
		}

		is SelectionTarget.Part -> {
			val composite = parts.firstOrNull { part -> part.id == owner.id }?.activeComposite
			composite?.let { active -> active.maskedBy.map { maskId -> SelectionTarget.Drawable(maskId) } + active.maskedByParts.map { maskId -> SelectionTarget.Part(maskId) } }.orEmpty()
		}

		is SelectionTarget.Deformer -> {
			emptyList()
		}
	}

/**
 * The test for whether a candidate may be added to [owner]'s clip masks: a drawable clips a drawable, a drawable or
 * another part clips an Isolated part, nothing clips itself, and an applied mask is not applied again.
 *
 * @param SelectionTarget owner The drawable or part being clipped.
 * @return Function The test, true for a candidate that may be added.
 */
private fun PuppetModel.maskAdmitter(owner: SelectionTarget): (SelectionTarget) -> Boolean {
	val clippable =
		when (owner) {
			is SelectionTarget.Drawable -> drawables.any { drawable -> drawable.id == owner.id }
			is SelectionTarget.Part -> parts.firstOrNull { part -> part.id == owner.id }?.activeComposite != null
			is SelectionTarget.Deformer -> false
		}

	if (!clippable) {
		return { false }
	}

	val applied = masksOf(owner).toHashSet()
	val knownDrawables = drawables.mapTo(HashSet()) { drawable -> drawable.id }
	val knownParts = parts.mapTo(HashSet()) { part -> part.id }

	return { candidate ->
		val kindFits =
			when (candidate) {
				is SelectionTarget.Drawable -> candidate.id in knownDrawables

				// Only a part's composite takes a part as a mask; a drawable is clipped by drawables alone.
				is SelectionTarget.Part -> owner is SelectionTarget.Part && candidate.id in knownParts

				is SelectionTarget.Deformer -> false
			}
		kindFits && candidate != owner && candidate !in applied
	}
}