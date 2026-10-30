package org.umamo.ui.transform

import org.umamo.edit.transform.allPointIndices
import org.umamo.render.eval.WarpLattice
import org.umamo.render.eval.WarpLatticeResolver
import org.umamo.render.warpChildrenKeptInPlace
import org.umamo.runtime.keyform.withLatticeShifted
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deformerChain

/*
 * An Edit-mode reshape of warp lattices, lowered onto the model.  Edit mode shapes a cage to the art without moving
 * the art: the dragged world points become a parent-space offset that every form of the warp shifts by, so each
 * keyed motion stays what it was, and the warp's children are re-expressed in the reshaped lattice so they rest
 * where they rested.  Lives in :ui because the inverse and the keep-in-place both need :render's evaluator.
 */

/**
 * A reshape lowered onto the model, with the children it could not keep in place.
 *
 * @property PuppetModel model The model with every lattice reshaped and every child it could keep in place kept.
 * @property List<DrawableId> unconvertedDrawables The direct child drawables left as they were; their art moves.
 * @property List<DeformerId> unconvertedDeformers The direct child deformers left as they were.
 */
internal class LatticeReshape(
	val model: PuppetModel,
	val unconvertedDrawables: List<DrawableId>,
	val unconvertedDeformers: List<DeformerId>,
) {
	/** How many children the reshape could not keep in place. */
	val unconvertedCount: Int get() = unconvertedDrawables.size + unconvertedDeformers.size
}

/**
 * Lowers dragged lattice points onto [model]: each warp in [targetWorldById] ends with its rest lattice on the given
 * world points and its children resting where they rested.
 *
 * Warps are taken parents first, each against the model its ancestors' reshapes left: a warp's target is inverted
 * into its parent's space through the parent as it is NOW, after an edited parent has kept this warp in place, so
 * nested edited warps each land where they were dragged.  The offset from the warp's current rest lattice to the
 * inverted target shifts every grid cell and blend-shape form ([withLatticeShifted]), which moves the evaluated
 * lattice by exactly that offset at every pose, and [warpChildrenKeptInPlace] then re-expresses its children.  A
 * warp the rest pose does not draw, or a target of another point count, is left alone.
 *
 * @param PuppetModel model The model the lattices are dragged over.
 * @param Map<DeformerId, FloatArray> targetWorldById Each reshaped warp's new rest lattice in world space,
 *   interleaved x, z.
 * @return LatticeReshape The reshaped model and the children it could not keep in place.
 */
internal fun latticeReshapeOf(model: PuppetModel, targetWorldById: Map<DeformerId, FloatArray>): LatticeReshape {
	val unconvertedDrawables = ArrayList<DrawableId>()
	val unconvertedDeformers = ArrayList<DeformerId>()
	var current = model

	forEachWarpParentsFirst(model, targetWorldById.keys, { level -> WarpLatticeResolver(current, emptyMap(), warpIds = level) }) { deformerId, lattice ->
		val target = targetWorldById.getValue(deformerId)
		val warp = current.deformerById[deformerId] as? Deformer.Warp ?: return@forEachWarpParentsFirst

		if (target.size != lattice.world.size) {
			return@forEachWarpParentsFirst
		}

		val targetLocal = lattice.parentMapping.worldToLocalLinearized(target, lattice.local, allPointIndices(target))
		val delta = FloatArray(target.size) { componentIndex -> targetLocal[componentIndex] - lattice.local[componentIndex] }

		if (delta.all { component -> component == 0f } || delta.any { component -> !component.isFinite() }) {
			return@forEachWarpParentsFirst
		}

		val shifted = warp.withLatticeShifted(delta)
		val reshaped = current.copy(deformers = current.deformers.map { deformer -> if (deformer.id == deformerId) shifted else deformer })
		val kept = warpChildrenKeptInPlace(current, reshaped, deformerId)
		unconvertedDrawables.addAll(kept.unconvertedDrawables)
		unconvertedDeformers.addAll(kept.unconvertedDeformers)
		current = kept.model
	}

	return LatticeReshape(current, unconvertedDrawables, unconvertedDeformers)
}

/**
 * Visits each warp of [deformerIds] parents first, one depth level at a time, with its lattice from the resolver
 * [resolverFor] makes for that level: a level bakes its warps' chains once, and the resolver is re-made after the
 * level lands, so a child reads its parents as they landed.  Warps of one level are never each other's ancestors, so
 * one level's landings cannot move another warp of the same level.  A warp the resolver does not draw is skipped.
 *
 * @param PuppetModel model The model whose hierarchy orders the warps.
 * @param Collection<DeformerId> deformerIds The warps, in the order a level visits them.
 * @param Function resolverFor Makes the resolver for one level's warps, over whatever the earlier levels landed.
 * @param Function visit Called with each drawn warp and its lattice.
 */
internal fun forEachWarpParentsFirst(
	model: PuppetModel,
	deformerIds: Collection<DeformerId>,
	resolverFor: (List<DeformerId>) -> WarpLatticeResolver,
	visit: (DeformerId, WarpLattice) -> Unit,
) {
	val levels = deformerIds.groupBy { deformerId -> model.deformerChain(deformerId).size }

	for (depth in levels.keys.sorted()) {
		val level = levels.getValue(depth)
		val resolver = resolverFor(level)

		for (deformerId in level) {
			resolver.lattice(deformerId)?.let { lattice -> visit(deformerId, lattice) }
		}
	}
}