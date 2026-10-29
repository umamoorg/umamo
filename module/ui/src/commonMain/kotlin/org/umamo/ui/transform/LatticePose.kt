package org.umamo.ui.transform

import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm

/*
 * A Pose-mode shape of warp lattices, lowered to the forms a key would capture.  Posing a lattice moves its points
 * where the cage shows them at the current pose, and the art follows: each moved point lands on its world target,
 * each unmoved one keeps its place in its parent's space (so it follows a moved parent), and what is held is the
 * grid's own share of the lattice - the blend shapes still add on top, as they do at every pose.  Lives in :ui
 * because the inverse needs :render's evaluator.
 */

/**
 * The pending lattices a Pose-mode shape lowers to: each warp in [targetWorldById] with its moved points on their
 * world targets at [stage]'s pose.
 *
 * Warps are taken parents first, each evaluated with the lattices its ancestors just landed standing in for theirs,
 * so a nested warp's moved points invert through the parent as it now is and its unmoved points ride along.  A moved
 * point's target is inverted through the warp's posed parent mapping; the result is the lattice the pose shows moved
 * by the same parent-space offset, minus nothing else - the blend shapes' contribution is taken out by subtracting the
 * lattice as shown and adding back the grid's own, `gridLocal + (newLocal - local)`.  A warp the pose does not draw,
 * or a target of another point count, is left out.
 *
 * @param PuppetModel model The model the lattices are shaped over.
 * @param ElementEditStage stage The pose and the pending lattices in place there.
 * @param Map<DeformerId, FloatArray> targetWorldById Each shaped warp's points in world space, interleaved x, z: the
 *   moved ones at their targets, the rest where they were.
 * @param Map<DeformerId, Set<Int>> movedById Each shaped warp's moved points.
 * @return Map<DeformerId, WarpLatticeForm> Each shaped warp's lattice in its parent's space, before blend shapes.
 */
internal fun posedLatticeForms(
	model: PuppetModel,
	stage: ElementEditStage,
	targetWorldById: Map<DeformerId, FloatArray>,
	movedById: Map<DeformerId, Set<Int>>,
): Map<DeformerId, WarpLatticeForm> {
	val parentById = model.deformers.associate { deformer -> deformer.id to deformer.parent }
	val ordered = targetWorldById.keys.sortedBy { deformerId -> ancestorDepth(deformerId, parentById) }
	val forms = LinkedHashMap<DeformerId, WarpLatticeForm>()
	var overrides = stage.latticeOverrides

	for (deformerId in ordered) {
		val target = targetWorldById.getValue(deformerId)
		val lattice = warpLatticeAt(model, stage.pose, deformerId, overrides) ?: continue

		if (target.size != lattice.world.size) {
			continue
		}

		val moved = movedById[deformerId] ?: (0 until target.size / 2).toSet()
		val newLocal = lattice.parentMapping.worldToLocalLinearized(target, lattice.local, lattice.world, moved)
		val gridLocal = lattice.gridLocal
		val form = WarpLatticeForm(FloatArray(newLocal.size) { componentIndex -> gridLocal[componentIndex] + (newLocal[componentIndex] - lattice.local[componentIndex]) })

		if (form.controlPoints.any { component -> !component.isFinite() }) {
			continue
		}

		forms[deformerId] = form
		overrides = overrides + (deformerId to form)
	}

	return forms
}