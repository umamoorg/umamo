package org.umamo.edit.relation

import org.umamo.edit.SelectionTarget
import org.umamo.edit.property.withDeformerPart
import org.umamo.edit.property.withDrawableMaskedBy
import org.umamo.edit.property.withPartComposite
import org.umamo.edit.structure.withOrgChildMoved
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PartComposite
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel

/*
 * The relation writes that move no art: an object's parent part and its clip masks.  A parent deformer's write keeps
 * the moved object where it rests, which needs :render's eval, so it lives beside the other keep-in-place re-parents.
 */

/**
 * A copy with [owner] held by [partId] (null for the top level): a drawable or a part moves in the org tree, appended
 * at the destination, and a deformer's part field is set.  A move the tree refuses (a part into its own subtree)
 * leaves the model as it is.
 *
 * @param SelectionTarget owner The drawable, part, or deformer.
 * @param PartId? partId The part that holds it, or null.
 * @return PuppetModel The model with the binding made.
 */
fun PuppetModel.withParentPart(owner: SelectionTarget, partId: PartId?): PuppetModel =
	when (owner) {
		is SelectionTarget.Drawable -> withOrgChildMoved(OrgChild.Drawable(owner.id), partId, null)
		is SelectionTarget.Part -> withOrgChildMoved(OrgChild.Part(owner.id), partId, null)
		is SelectionTarget.Deformer -> withDeformerPart(owner.id, partId)
	}

/**
 * A copy with [entry] added to [owner]'s clip masks, or this model itself when it is already there or [owner] takes
 * no such mask.
 *
 * @param SelectionTarget owner The drawable or Isolated part.
 * @param SelectionTarget entry The drawable or part that clips it.
 * @return PuppetModel The model with the mask added.
 */
fun PuppetModel.withMaskAdded(owner: SelectionTarget, entry: SelectionTarget): PuppetModel = withMasksChanged(owner, entry, adding = true)

/**
 * A copy with [entry] taken out of [owner]'s clip masks, or this model itself when it was not there.
 *
 * @param SelectionTarget owner The drawable or Isolated part.
 * @param SelectionTarget entry The drawable or part that clips it.
 * @return PuppetModel The model with the mask removed.
 */
fun PuppetModel.withMaskRemoved(owner: SelectionTarget, entry: SelectionTarget): PuppetModel = withMasksChanged(owner, entry, adding = false)

/**
 * [owner]'s clip masks with [entry] added or removed, as the new mask list of a drawable or the new composite of an
 * Isolated part; null when nothing would change.
 *
 * @param SelectionTarget owner The drawable or Isolated part.
 * @param SelectionTarget entry The drawable or part that clips it.
 * @param Boolean adding True to add [entry], false to remove it.
 * @return PuppetModel The model with the masks changed.
 */
private fun PuppetModel.withMasksChanged(owner: SelectionTarget, entry: SelectionTarget, adding: Boolean): PuppetModel {
	if (adding == (entry in masksOf(owner))) {
		return this
	}

	return when (owner) {
		is SelectionTarget.Drawable -> {
			val maskId = (entry as? SelectionTarget.Drawable)?.id ?: return this
			val current = drawables.firstOrNull { drawable -> drawable.id == owner.id }?.maskedBy ?: return this
			withDrawableMaskedBy(owner.id, if (adding) current + maskId else current - maskId)
		}

		is SelectionTarget.Part -> {
			val composite = parts.firstOrNull { part -> part.id == owner.id }?.activeComposite ?: return this
			val changed = composite.withMask(entry, adding) ?: return this
			withPartComposite(owner.id, changed)
		}

		is SelectionTarget.Deformer -> {
			this
		}
	}
}

/**
 * This composite with [entry] added to or removed from its masks, the drawable list or the part list by its kind;
 * null for a kind that cannot mask.
 *
 * @param SelectionTarget entry The drawable or part.
 * @param Boolean adding True to add it, false to remove it.
 * @return PartComposite? The composite, or null.
 */
private fun PartComposite.withMask(entry: SelectionTarget, adding: Boolean): PartComposite? =
	when (entry) {
		is SelectionTarget.Drawable -> copy(maskedBy = if (adding) maskedBy + entry.id else maskedBy - entry.id)
		is SelectionTarget.Part -> copy(maskedByParts = if (adding) maskedByParts + entry.id else maskedByParts - entry.id)
		is SelectionTarget.Deformer -> null
	}