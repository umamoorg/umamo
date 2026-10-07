package org.umamo.edit

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot

/*
 * Entity deletion over the org tree. Removing a drawable, a deformer, or a part scrubs every dangling
 * reference so no later code dereferences a deleted id. Deleting an art mesh is the only inherently
 * destructive case; deleting a deformer "unwraps" it (its sub-deformers and bound meshes re-home to its
 * parent); deleting a part is either a cascade (the part and its whole subtree) or an ungroup (the folder
 * only, contents spliced up into its parent). Org-tree edits re-derive the render order.
 */

/** A copy of this deformer re-nested under [newParent] in the transform hierarchy (used to re-home orphans). */
private fun Deformer.reparentedTo(newParent: DeformerId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(parent = newParent)
		is Deformer.Rotation -> copy(parent = newParent)
	}

/** A copy of this deformer re-bound to the organizational part [newPart] (or null to clear a dangling ref). */
private fun Deformer.reboundToPart(newPart: PartId?): Deformer =
	when (this) {
		is Deformer.Warp -> copy(partId = newPart)
		is Deformer.Rotation -> copy(partId = newPart)
	}

/**
 * Returns a copy of [this] with every drawable in [ids] removed and every reference to them scrubbed: the
 * drawables list, the org tree (their [OrgChild.Drawable] entries), any other drawable's clip mask, any
 * part's composite mask, and any glue whose either partner was deleted. A drawable's `textureSourceId` is
 * left alone: it keys the atlas page mapping rather than naming a live drawable, so a copy keeps its art
 * when its source goes. The render order is NOT re-derived here - the caller does that once it has
 * finished its structural changes. An empty set returns the same instance.
 *
 * @param Set<DrawableId> ids The drawables to delete.
 * @return PuppetModel The model with those drawables and their references gone.
 */
internal fun PuppetModel.removingDrawables(ids: Set<DrawableId>): PuppetModel {
	if (ids.isEmpty()) {
		return this
	}
	val remaining =
		drawables.filterNot { drawable -> drawable.id in ids }
			.map { drawable ->
				val cleanedMasks = drawable.maskedBy.filterNot { maskId -> maskId in ids }
				if (cleanedMasks.size != drawable.maskedBy.size) drawable.copy(maskedBy = cleanedMasks) else drawable
			}
	val cleanedGlues = glues.filterNot { glue -> glue.meshA in ids || glue.meshB in ids }
	val cleanedRoot = rootChildren.filterNot { child -> child is OrgChild.Drawable && child.id in ids }
	val cleanedParts =
		parts.map { part ->
			val kids = part.children.filterNot { child -> child is OrgChild.Drawable && child.id in ids }
			val masks = part.composite.maskedBy.filterNot { maskId -> maskId in ids }
			if (kids.size == part.children.size && masks.size == part.composite.maskedBy.size) {
				part
			} else {
				part.copy(children = kids, composite = part.composite.copy(maskedBy = masks))
			}
		}
	return copy(drawables = remaining, glues = cleanedGlues, rootChildren = cleanedRoot, parts = cleanedParts)
}

/**
 * These parts with every id in [deleted] taken out of their composites' part masks.
 *
 * @param Set<PartId> deleted The parts being deleted.
 * @return List The parts, each the same instance when it named none of them.
 */
private fun List<Part>.withPartMasksRemoved(deleted: Set<PartId>): List<Part> =
	map { part ->
		val masks = part.composite.maskedByParts.filterNot { maskId -> maskId in deleted }
		if (masks.size == part.composite.maskedByParts.size) part else part.copy(composite = part.composite.copy(maskedByParts = masks))
	}

/**
 * These parts with the dissolved part [dissolved] replaced, in every composite that masks by it, by its own
 * direct children - child parts as part masks, child drawables as drawable masks - so ungrouping a folder
 * keeps the coverage it gave.  A part never takes itself as a mask, and a mask already present is not added
 * twice.
 *
 * @param Part dissolved The part an ungroup dissolves.
 * @return List The parts, each the same instance when it did not mask by [dissolved].
 */
private fun List<Part>.withPartMaskUngrouped(dissolved: Part): List<Part> =
	map { part ->
		if (dissolved.id !in part.composite.maskedByParts) {
			return@map part
		}
		val childParts = dissolved.children.filterIsInstance<OrgChild.Part>().map { child -> child.id }.filter { childId -> childId != part.id }
		val childDrawables = dissolved.children.filterIsInstance<OrgChild.Drawable>().map { child -> child.id }
		val partMasks = (part.composite.maskedByParts.flatMap { maskId -> if (maskId == dissolved.id) childParts else listOf(maskId) }).distinct()
		val drawableMasks = (part.composite.maskedBy + childDrawables).distinct()
		part.copy(composite = part.composite.copy(maskedByParts = partMasks, maskedBy = drawableMasks))
	}

/**
 * Returns a copy of [this] with the single drawable [id] deleted (mask / glue / tree references scrubbed,
 * render order re-derived). A no-op (no such drawable) returns the same instance.
 *
 * @param DrawableId id The drawable to delete.
 * @return PuppetModel The model without that drawable, or [this] if it was absent.
 */
fun PuppetModel.withDrawableDeleted(id: DrawableId): PuppetModel {
	if (drawables.none { it.id == id }) {
		return this
	}
	return removingDrawables(setOf(id)).withDerivedRenderRoot()
}

/**
 * Returns a copy of [this] with the deformer [id] deleted by unwrapping it: its child deformers and the
 * drawables it deformed re-home to its own parent (null = an armature root), so removing a transform
 * wrapper never deletes art. Does not touch the org tree, so the render order is unchanged. A no-op (no
 * such deformer) returns the same instance.
 *
 * A re-homed drawable's base was in the deleted deformer's space; one listed in [localPositionsByDrawable]
 * takes that base, already in its new parent's space, so it stays where it rests (see
 * [withDrawableParentDeformer]).  Every other re-homed drawable, and every child deformer, keeps its numbers,
 * so a caller passing an empty map chooses the art following the new parent, in so many words.
 *
 * @param DeformerId id                      The deformer to delete.
 * @param Map        localPositionsByDrawable The bases of the re-homed drawables that keep their place.
 * @return PuppetModel The model without that deformer, or [this] if it was absent.
 */
fun PuppetModel.withDeformerDeleted(id: DeformerId, localPositionsByDrawable: Map<DrawableId, FloatArray>): PuppetModel {
	val deformer = deformers.firstOrNull { it.id == id } ?: return this
	val grandParent = deformer.parent
	val updatedDeformers =
		deformers.filter { it.id != id }
			.map { other -> if (other.parent == id) other.reparentedTo(grandParent) else other }
	val updatedDrawables =
		drawables.map { drawable ->
			if (drawable.parentDeformerId == id) drawable.rebound(grandParent, localPositionsByDrawable[drawable.id]) else drawable
		}
	return copy(deformers = updatedDeformers, drawables = updatedDrawables)
}

/** The set of part ids in [id]'s org-tree subtree (the part itself plus every descendant part). */
private fun PuppetModel.partSubtreeIds(id: PartId): Set<PartId> {
	val partById = parts.associateBy { it.id }
	val subtree = LinkedHashSet<PartId>()
	val stack = ArrayDeque<PartId>()
	stack.add(id)
	while (stack.isNotEmpty()) {
		val next = stack.removeLast()
		if (!subtree.add(next)) {
			continue
		}
		partById[next]?.children?.forEach { child -> if (child is OrgChild.Part) stack.add(child.id) }
	}
	return subtree
}

/**
 * Returns a copy of [this] with the part [id] deleted. A [cascade] removes the whole subtree - the part,
 * every descendant part, and every drawable under any of them (references scrubbed); an ungroup (cascade
 * false) dissolves only the folder - its children (sub-parts and drawables) splice into its parent in
 * place, so nothing is destroyed. The render order is re-derived. A no-op (no such part) returns the same
 * instance.
 *
 * @param PartId id The part to delete.
 * @param Boolean cascade True to delete the subtree, false to ungroup (keep contents, splice them up).
 * @return PuppetModel The model with the part deleted, or [this] if it was absent.
 */
fun PuppetModel.withPartDeleted(id: PartId, cascade: Boolean): PuppetModel {
	val part = parts.firstOrNull { it.id == id } ?: return this
	val partRef = OrgChild.Part(id)
	// The deleted part's parent (where ungrouped contents land / dangling deformer bindings fall back to).
	val grandParentId = parts.firstOrNull { partRef in it.children }?.id
	return if (cascade) {
		val subtree = partSubtreeIds(id)
		val doomedDrawables =
			parts.filter { it.id in subtree }
				.flatMap { it.children }
				.filterIsInstance<OrgChild.Drawable>()
				.map { it.id }
				.toSet()
		// Drop the subtree parts; detach the part ref from wherever it sits (root or its parent).
		val remainingParts =
			parts.filterNot { it.id in subtree }
				.map { candidate -> if (partRef in candidate.children) candidate.copy(children = candidate.children - partRef) else candidate }
				.withPartMasksRemoved(subtree)
		val cleanedRoot = rootChildren.filter { it != partRef }
		// A deformer's organizational partId may point into the deleted subtree; clear it so nothing dangles.
		val cleanedDeformers =
			deformers.map { deformer ->
				if (deformer.partId != null && deformer.partId in subtree) deformer.reboundToPart(null) else deformer
			}
		copy(parts = remainingParts, rootChildren = cleanedRoot, deformers = cleanedDeformers)
			.removingDrawables(doomedDrawables)
			.copy(rootPartId = if (rootPartId != null && rootPartId in subtree) null else rootPartId)
			.withDerivedRenderRoot()
	} else {
		// Ungroup: replace the part ref with the part's own children, in place, wherever it sits.
		fun splice(children: List<OrgChild>): List<OrgChild> = children.flatMap { if (it == partRef) part.children else listOf(it) }
		val cleanedRoot = splice(rootChildren)
		val remainingParts = parts.filterNot { it.id == id }.map { it.copy(children = splice(it.children)) }.withPartMaskUngrouped(part)
		val cleanedDeformers = deformers.map { deformer -> if (deformer.partId == id) deformer.reboundToPart(grandParentId) else deformer }
		copy(
			parts = remainingParts,
			rootChildren = cleanedRoot,
			deformers = cleanedDeformers,
			rootPartId = if (rootPartId == id) grandParentId else rootPartId,
		).withDerivedRenderRoot()
	}
}

/**
 * Deletes the drawable [id] as one undo step. A no-op records nothing.
 *
 * @param DrawableId id The drawable to delete.
 */
fun EditorSession.deleteDrawable(id: DrawableId) {
	mutate(DrawableChange.Delete(id)) { model -> model.withDrawableDeleted(id) }
}

/**
 * Deletes the deformer [id] (unwrapping it - children re-home to its parent) as one undo step.
 *
 * @param DeformerId id                       The deformer to delete.
 * @param Function1  localPositionsByDrawable The bases of the re-homed drawables that keep their place, over
 *                                            the unwrapped model.
 */
fun EditorSession.deleteDeformer(id: DeformerId, localPositionsByDrawable: (unwrapped: PuppetModel) -> Map<DrawableId, FloatArray>) {
	mutate(DeformerChange.Delete(id)) { model ->
		val unwrapped = model.withDeformerDeleted(id, emptyMap())
		if (unwrapped === model) {
			return@mutate model
		}
		unwrapped.withDrawableBases(localPositionsByDrawable(unwrapped))
	}
}

/**
 * This model with each listed drawable's keyform-space base replaced, its binding and canvas mesh kept.  A
 * drawable the model does not carry, or a base not of its mesh's length, is skipped (see [Drawable.rebound]).
 *
 * @param Map localPositionsByDrawable The new base per drawable.
 * @return PuppetModel The model with those bases, or [this] when the map is empty.
 */
private fun PuppetModel.withDrawableBases(localPositionsByDrawable: Map<DrawableId, FloatArray>): PuppetModel {
	if (localPositionsByDrawable.isEmpty()) {
		return this
	}
	return copy(
		drawables =
			drawables.map { drawable ->
				localPositionsByDrawable[drawable.id]?.let { local -> drawable.rebound(drawable.parentDeformerId, local) } ?: drawable
			},
	)
}

/**
 * Deletes the part [id] as one undo step: a cascade (subtree) when [cascade], else an ungroup (contents spliced up).
 *
 * @param PartId id The part to delete.
 * @param Boolean cascade True to delete the subtree, false to ungroup.
 */
fun EditorSession.deletePart(id: PartId, cascade: Boolean) {
	mutate(PartChange.Delete(id, cascade)) { model -> model.withPartDeleted(id, cascade) }
}

/**
 * Deletes the entity named by [target] as one undo step. [cascade] applies only to a part (a drawable or a
 * deformer ignores it - a deformer always unwraps).
 *
 * @param SelectionTarget target                   The entity to delete.
 * @param Boolean         cascade                  For a part, true to delete the subtree, false to ungroup; ignored otherwise.
 * @param Function1       localPositionsByDrawable For a deformer, the bases of the re-homed drawables that keep
 *                                                 their place over the unwrapped model, a drawable left out
 *                                                 keeping its numbers (see [deleteDeformer]); ignored otherwise.
 */
fun EditorSession.deleteTarget(target: SelectionTarget, cascade: Boolean, localPositionsByDrawable: (unwrapped: PuppetModel) -> Map<DrawableId, FloatArray>) {
	when (target) {
		is SelectionTarget.Part -> deletePart(target.id, cascade)
		is SelectionTarget.Drawable -> deleteDrawable(target.id)
		is SelectionTarget.Deformer -> deleteDeformer(target.id, localPositionsByDrawable)
	}
}