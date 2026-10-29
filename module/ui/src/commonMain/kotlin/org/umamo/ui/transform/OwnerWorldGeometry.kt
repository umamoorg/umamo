package org.umamo.ui.transform

import org.umamo.render.eval.WarpLattice
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel

/**
 * One owner an element transform moves, captured in world space at the pose it edits at: a drawable's mesh
 * ([DrawableWorldGeometry]) at rest, or a warp's lattice ([LatticeWorldGeometry]) at rest or at the pose.
 */
internal sealed interface OwnerWorldGeometry {
	/** The owner: the drawable or the warp. */
	val owner: ElementOwner

	/** Its vertices in world space, interleaved x, z (up). */
	val world: FloatArray
}

/**
 * One warp's lattice captured at a stage: where that pose draws its points, in its parent's space and in world space,
 * with the mapping between them.  The arrays are the bake's own copies, so a gesture transforms them without freezing
 * them first.
 *
 * @property DeformerId deformerId The warp.
 * @property WarpLattice lattice Its lattice at the stage's pose.
 */
internal class LatticeWorldGeometry(
	val deformerId: DeformerId,
	val lattice: WarpLattice,
) : OwnerWorldGeometry {
	override val owner: ElementOwner = ElementOwner.Deformer(deformerId)

	override val world: FloatArray get() = lattice.world
}

/**
 * Captures warp [deformerId]'s lattice at [stage]: at rest, or at the pose with the pending lattices in place.
 *
 * @param PuppetModel model The model.
 * @param DeformerId deformerId The warp.
 * @param ElementEditStage stage Where to capture it.
 * @return LatticeWorldGeometry? The capture, or null when the model holds no such warp or the stage's pose does not
 *   draw it.
 */
internal fun captureLatticeWorld(
	model: PuppetModel,
	deformerId: DeformerId,
	stage: ElementEditStage = ElementEditStage.Rest,
): LatticeWorldGeometry? = warpLatticeAt(model, stage.pose, deformerId, stage.latticeOverrides)?.let { lattice -> LatticeWorldGeometry(deformerId, lattice) }