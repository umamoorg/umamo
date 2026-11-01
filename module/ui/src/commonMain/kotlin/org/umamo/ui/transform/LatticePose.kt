package org.umamo.ui.transform

import org.umamo.edit.transform.allPointIndices
import org.umamo.render.eval.WarpLatticeResolver
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
 * world targets at [stage]'s pose.  Warps are taken parents first, each evaluated with the lattices its ancestors
 * just landed standing in for theirs, so a nested warp's moved points invert through the parent as it now is.
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
	val forms = LinkedHashMap<DeformerId, WarpLatticeForm>()
	var overrides = stage.latticeOverrides

	forEachWarpParentsFirst(model, targetWorldById.keys, { level -> WarpLatticeResolver(model, stage.pose, overrides, level) }) { deformerId, lattice ->
		val target = targetWorldById.getValue(deformerId)

		if (target.size != lattice.world.size) {
			return@forEachWarpParentsFirst
		}

		val moved = movedById[deformerId] ?: allPointIndices(target)
		val newLocal = lattice.parentMapping.worldToLocalLinearized(target, lattice.local, moved)
		val gridLocal = lattice.gridLocal
		// The offset lands on the grid's own share of the lattice, not the lattice as shown, so the blend shapes still
		// add on top.
		val form = WarpLatticeForm(FloatArray(newLocal.size) { componentIndex -> gridLocal[componentIndex] + (newLocal[componentIndex] - lattice.local[componentIndex]) })

		if (form.controlPoints.any { component -> !component.isFinite() }) {
			return@forEachWarpParentsFirst
		}

		forms[deformerId] = form
		overrides = overrides + (deformerId to form)
	}

	return forms
}