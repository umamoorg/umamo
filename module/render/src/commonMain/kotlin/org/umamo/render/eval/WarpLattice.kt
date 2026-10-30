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
 * @property FloatArray world The rendered lattice in world space, x right and z up - the space the viewport
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
 * Warp [deformerId]'s lattice at [parameters], baking the warp and its ancestors alone ([WarpLatticeResolver] over
 * that one warp), so one warp never pays for the whole rig.  Parameters resolve as the renderer resolves them, and blend shapes measure from the real
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
): WarpLattice? = WarpLatticeResolver(model, parameters, latticeOverrides, listOf(deformerId)).lattice(deformerId)

/**
 * Resolves the lattices of many warps at one (model, pose), baking the deformer worlds once for all of them: the
 * union of the named warps' ancestor chains ([ancestorChain]), or the whole rig when no warps are named.  The one
 * lattice evaluation - [warpLatticeAt] is this over a single warp - so per warp the answer never depends on what else
 * was asked.
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 * @param Map latticeOverrides Pending lattices by warp, standing in for those warps' grid blends.
 * @param Collection<DeformerId>? warpIds The warps to bake for, or null for every deformer.
 */
class WarpLatticeResolver(
	private val model: PuppetModel,
	parameters: Map<ParameterId, Float>,
	private val latticeOverrides: Map<DeformerId, WarpLatticeForm> = emptyMap(),
	warpIds: Collection<DeformerId>? = null,
) {
	private val pose = PoseParameters(model, parameters)

	// Baked on the first request, so a resolver asked about nothing bakes nothing.  Each chain is root first and a
	// shared ancestor joins once, so the bake builds the union in about one pass.
	private val worlds: Map<DeformerId, DeformerWorld> by lazy {
		val deformers = warpIds?.flatMapTo(LinkedHashSet()) { warpId -> ancestorChain(model, warpId) }?.toList() ?: model.deformers
		buildDeformerWorlds(deformers, pose.paramValue, pose.defaultValue, latticeOverrides = latticeOverrides)
	}

	/**
	 * Warp [deformerId]'s lattice at this pose.  A warp the resolver was not made for is answered only when a named
	 * warp's chain holds it.
	 *
	 * @param DeformerId deformerId The warp.
	 * @return WarpLattice? The lattice, or null when the model holds no warp by that id or it is not drawn.
	 */
	fun lattice(deformerId: DeformerId): WarpLattice? {
		val warp = model.deformerById[deformerId] as? Deformer.Warp ?: return null

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
	val world = warpWorld.worldControlPoints()
	// A parent the model does not hold is treated as the root, exactly as the bake treats it.
	val parentWorld = warp.parent?.let { parentId -> worlds[parentId] }

	return WarpLattice(warp.rows, warp.columns, gridLocal, local, world, ParentSpaceMapping(parentWorld))
}