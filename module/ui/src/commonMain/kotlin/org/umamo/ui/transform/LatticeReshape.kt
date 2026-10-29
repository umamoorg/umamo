package org.umamo.ui.transform

import org.umamo.render.eval.warpLatticeAt
import org.umamo.render.warpChildrenKeptInPlace
import org.umamo.runtime.keyform.withLatticeShifted
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel

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
	val parentById = model.deformers.associate { deformer -> deformer.id to deformer.parent }
	val ordered = targetWorldById.keys.sortedBy { deformerId -> ancestorDepth(deformerId, parentById) }
	val unconvertedDrawables = ArrayList<DrawableId>()
	val unconvertedDeformers = ArrayList<DeformerId>()
	var current = model

	for (deformerId in ordered) {
		val target = targetWorldById.getValue(deformerId)
		val warp = current.deformers.firstOrNull { deformer -> deformer.id == deformerId } as? Deformer.Warp ?: continue
		val lattice = warpLatticeAt(current, emptyMap(), deformerId) ?: continue

		if (target.size != lattice.world.size) {
			continue
		}

		val allIndices = (0 until target.size / 2).toSet()
		val targetLocal = lattice.parentMapping.worldToLocalLinearized(target, lattice.local, lattice.world, allIndices)
		val delta = FloatArray(target.size) { componentIndex -> targetLocal[componentIndex] - lattice.local[componentIndex] }

		if (delta.all { component -> component == 0f } || delta.any { component -> !component.isFinite() }) {
			continue
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
 * How many ancestors a deformer has, a parent the model does not hold ending the chain.
 *
 * @param DeformerId deformerId The deformer.
 * @param Map<DeformerId, DeformerId?> parentById Each deformer's parent.
 * @return Int The ancestor count.
 */
private fun ancestorDepth(deformerId: DeformerId, parentById: Map<DeformerId, DeformerId?>): Int {
	var depth = 0
	var parent = parentById[deformerId]
	val seen = HashSet<DeformerId>()

	while (parent != null && seen.add(parent) && parent in parentById) {
		depth += 1
		parent = parentById[parent]
	}

	return depth
}