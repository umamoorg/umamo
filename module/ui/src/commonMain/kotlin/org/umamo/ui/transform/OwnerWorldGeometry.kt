package org.umamo.ui.transform

import org.umamo.render.eval.WarpLattice
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel

/**
 * One owner an Edit-mode transform moves, captured in world space at the pose Edit mode is pinned to: a drawable's
 * mesh ([DrawableWorldGeometry]) or a warp's lattice ([LatticeWorldGeometry]).
 */
internal sealed interface OwnerWorldGeometry {
	/** The owner: the drawable or the warp. */
	val owner: ElementOwner

	/** Its vertices in world space, interleaved x, z (up). */
	val world: FloatArray
}

/**
 * One warp's lattice captured at the rest pose: where the default pose draws its points, in its parent's space and in
 * world space, with the mapping between them.  The arrays are the bake's own copies, so a gesture transforms them
 * without freezing them first.
 *
 * @property DeformerId deformerId The warp.
 * @property WarpLattice lattice Its lattice at the default pose.
 */
internal class LatticeWorldGeometry(
	val deformerId: DeformerId,
	val lattice: WarpLattice,
) : OwnerWorldGeometry {
	override val owner: ElementOwner = ElementOwner.Deformer(deformerId)

	override val world: FloatArray get() = lattice.world
}

/**
 * Captures warp [deformerId]'s lattice at the rest pose Edit mode is pinned to.
 *
 * @param PuppetModel model The model.
 * @param DeformerId deformerId The warp.
 * @return LatticeWorldGeometry? The capture, or null when the model holds no such warp or the rest pose does not draw
 *   it.
 */
internal fun captureLatticeWorld(model: PuppetModel, deformerId: DeformerId): LatticeWorldGeometry? =
	warpLatticeAt(model, emptyMap(), deformerId)?.let { lattice -> LatticeWorldGeometry(deformerId, lattice) }