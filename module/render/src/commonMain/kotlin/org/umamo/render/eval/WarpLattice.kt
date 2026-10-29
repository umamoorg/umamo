package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm

/**
 * A warp's lattice at one pose, in the three spaces an edit needs it in: its parent's space before blend
 * shapes ([gridLocal]), its parent's space as rendered ([local]), and world space ([world]), with the mapping
 * between its parent's space and world.
 *
 * The rest lattice is this at the default pose - a warp stores none of its own, so it is derived, the same way
 * the rest view of a drawable is.
 *
 * @property Int rows The lattice's cell count along v.
 * @property Int columns The lattice's cell count along u.
 * @property FloatArray gridLocal The grid-blended control points (or the pending lattice standing in for them),
 *   interleaved x, y, in the parent's space: what a capture at this pose writes into the grid.
 * @property FloatArray local [gridLocal] with the blend shapes added: the lattice as rendered, in the parent's space.
 * @property FloatArray world The rendered lattice in world space, x right and y up - the space the viewport
 *   draws and picks in.
 * @property ParentSpaceMapping parentMapping Between the warp's parent's space and world space at this pose.
 */
class WarpLattice internal constructor(
	val rows: Int,
	val columns: Int,
	val gridLocal: FloatArray,
	val local: FloatArray,
	val world: FloatArray,
	val parentMapping: ParentSpaceMapping,
)

/**
 * Warp [deformerId]'s lattice at [parameters], baking the warp and its ancestors alone, so one warp never pays
 * for the whole rig.  Parameters resolve as the renderer resolves them, and blend shapes measure from the real
 * defaults, so the lattice is where the viewport draws it.
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 * @param DeformerId deformerId The warp.
 * @param Map latticeOverrides Pending lattices by warp, standing in for those warps' grid blends.
 * @return WarpLattice? The lattice, or null when the model holds no warp by that id or the renderer would not
 *   draw it: unkeyed, out of its keyed range, or under an ancestor that is.
 */
fun warpLatticeAt(
	model: PuppetModel,
	parameters: Map<ParameterId, Float>,
	deformerId: DeformerId,
	latticeOverrides: Map<DeformerId, WarpLatticeForm> = emptyMap(),
): WarpLattice? {
	val chain = ancestorChain(model, deformerId)
	val warp = chain.lastOrNull() as? Deformer.Warp ?: return null
	val pose = PoseParameters(model, parameters)
	val worlds = buildDeformerWorlds(chain, pose.paramValue, pose.defaultValue, latticeOverrides = latticeOverrides)

	return latticeOf(warp, pose, worlds, latticeOverrides)
}

/**
 * Resolves the lattices of MANY warps at one (model, pose), baking the deformer worlds once for all of them -
 * what the viewport needs to draw every lattice in a frame.  Per warp the answer is exactly [warpLatticeAt]'s.
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 * @param Map latticeOverrides Pending lattices by warp, standing in for those warps' grid blends.
 */
class WarpLatticeResolver(
	model: PuppetModel,
	parameters: Map<ParameterId, Float>,
	private val latticeOverrides: Map<DeformerId, WarpLatticeForm> = emptyMap(),
) {
	private val pose = PoseParameters(model, parameters)
	private val warpById: Map<DeformerId, Deformer.Warp> = model.deformers.filterIsInstance<Deformer.Warp>().associateBy { warp -> warp.id }

	// Baked on the first request, so a resolver asked about nothing bakes nothing.
	private val worlds: Map<DeformerId, DeformerWorld> by lazy {
		buildDeformerWorlds(model.deformers, pose.paramValue, pose.defaultValue, latticeOverrides = latticeOverrides)
	}

	/**
	 * Warp [deformerId]'s lattice at this pose: what [warpLatticeAt] answers.
	 *
	 * @param DeformerId deformerId The warp.
	 * @return WarpLattice? The lattice, or null when the model holds no warp by that id or it is not drawn.
	 */
	fun lattice(deformerId: DeformerId): WarpLattice? {
		val warp = warpById[deformerId] ?: return null

		return latticeOf(warp, pose, worlds, latticeOverrides)
	}
}

/**
 * The lattice of [warp] from a bake that holds its world and its ancestors'.
 *
 * @param Deformer.Warp warp The warp.
 * @param PoseParameters pose The pose's lookups.
 * @param Map worlds The baked deformer worlds.
 * @param Map latticeOverrides The pending lattices the bake used.
 * @return WarpLattice? The lattice, or null when the bake did not build the warp (it is not drawn).
 */
private fun latticeOf(
	warp: Deformer.Warp,
	pose: PoseParameters,
	worlds: Map<DeformerId, DeformerWorld>,
	latticeOverrides: Map<DeformerId, WarpLatticeForm>,
): WarpLattice? {
	val warpWorld = worlds[warp.id] as? WarpWorld ?: return null
	val gridLocal = warpGridLattice(warp, pose.paramValue, latticeOverrides[warp.id]) ?: return null
	val local = gridLocal.copyOf()
	addWarpBlendDeltas(warp, pose.paramValue, pose.defaultValue, local)
	val world = warpWorld.cp.copyOf()
	var componentIndex = 1

	// The cascade's y grows downward; world space's grows upward.
	while (componentIndex < world.size) {
		world[componentIndex] = -world[componentIndex]
		componentIndex += 2
	}

	// A parent the model does not hold is treated as the root, exactly as the bake treats it.
	val parentWorld = warp.parent?.let { parentId -> worlds[parentId] }

	return WarpLattice(warp.rows, warp.columns, gridLocal, local, world, ParentSpaceMapping(parentWorld))
}