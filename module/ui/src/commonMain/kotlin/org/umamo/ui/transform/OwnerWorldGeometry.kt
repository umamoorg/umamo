package org.umamo.ui.transform

import org.umamo.edit.ElementTopology
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.allPointIndices
import org.umamo.render.eval.WarpLattice
import org.umamo.render.eval.WarpLatticeResolver
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

	/** Every vertex index - the whole set an object-level transform moves. */
	val allIndices: Set<Int> get() = allPointIndices(world)

	/**
	 * A capture whose arrays nothing else holds, for a gesture that must transform a fixed snapshot.
	 *
	 * @return OwnerWorldGeometry The frozen capture.
	 */
	fun frozen(): OwnerWorldGeometry

	/**
	 * The owner as a whole-object transform's capture source: every vertex covered, over a topology that connects
	 * nothing - an object transform pivots whole objects and takes no proportional halo, so connectivity is unused.
	 *
	 * @return ModalCaptureSource The source.
	 */
	fun wholeObjectSource(): ModalCaptureSource = ModalCaptureSource(owner, world, ElementTopology.unconnected(world.size / 2), allIndices)
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

	/**
	 * This capture itself: its arrays are already the bake's own copies.
	 *
	 * @return OwnerWorldGeometry This capture.
	 */
	override fun frozen(): OwnerWorldGeometry = this
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
): LatticeWorldGeometry? = captureLatticeWorlds(model, listOf(deformerId), stage).firstOrNull()

/**
 * Captures the lattices of warps [deformerIds] at [stage] in one bake of their deformer chains ([WarpLatticeResolver]),
 * in the given order; a warp the model does not hold, or the stage's pose does not draw, is left out.
 *
 * @param PuppetModel model The model.
 * @param Collection<DeformerId> deformerIds The warps.
 * @param ElementEditStage stage Where to capture them.
 * @return List<LatticeWorldGeometry> The captures.
 */
internal fun captureLatticeWorlds(
	model: PuppetModel,
	deformerIds: Collection<DeformerId>,
	stage: ElementEditStage = ElementEditStage.Rest,
): List<LatticeWorldGeometry> {
	if (deformerIds.isEmpty()) {
		return emptyList()
	}

	val resolver = WarpLatticeResolver(model, stage.pose, stage.latticeOverrides, deformerIds)

	return deformerIds.mapNotNull { deformerId -> resolver.lattice(deformerId)?.let { lattice -> LatticeWorldGeometry(deformerId, lattice) } }
}