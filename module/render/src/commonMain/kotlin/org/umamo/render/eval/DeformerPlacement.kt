package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel

/**
 * Where a deformer sits in world space at one pose: what the Properties panel shows as its transform.
 * World space is the one the viewport draws in, x right and z up; the deform cascade itself runs y-down, so
 * every vertical value here is that cascade's y negated.
 */
sealed interface DeformerPlacement {
	/**
	 * A warp deformer's lattice bounds: the box around its control points as the lattice is drawn.
	 *
	 * @property Float worldMinX The lattice's left edge.
	 * @property Float worldMinZ The lattice's bottom edge.
	 * @property Float worldMaxX The lattice's right edge.
	 * @property Float worldMaxZ The lattice's top edge.
	 */
	class Warp(val worldMinX: Float, val worldMinZ: Float, val worldMaxX: Float, val worldMaxZ: Float) : DeformerPlacement

	/**
	 * A rotation deformer's pivot with its own angle and scale.  The angle and scale are the deformer's own
	 * (its base angle plus what its keyforms and blend shapes add, and the scale those same keyforms and blend
	 * shapes give it), not the world values every ancestor composes onto them: under a warp the world angle
	 * carries an inherited part probed from the lattice, which moves whenever the lattice does.
	 *
	 * @property Float worldPivotX The pivot's world x.
	 * @property Float worldPivotZ The pivot's world z.
	 * @property Float angleDegrees The deformer's own angle in degrees.
	 * @property Float scale The deformer's own scale.
	 */
	class Rotation(val worldPivotX: Float, val worldPivotZ: Float, val angleDegrees: Float, val scale: Float) : DeformerPlacement
}

/**
 * Resolves where [deformerId] sits at [parameters], baking the deformer and its ancestors alone, so a panel
 * reading one deformer never pays for the whole rig.  Parameters resolve as the renderer resolves them: the
 * pose's value, else the parameter's default, and blend shapes measure from the real defaults, so the
 * answer is where the viewport draws the deformer.
 *
 * @param PuppetModel model The rig.
 * @param Map parameters Parameter id -> value (partial; the rest default).
 * @param DeformerId deformerId The deformer to place.
 * @return DeformerPlacement? The placement, or null when the model does not hold the deformer, or the
 *   renderer would not draw it: unkeyed, out of its keyed range, or under an ancestor that is.
 */
fun deformerPlacement(model: PuppetModel, parameters: Map<ParameterId, Float>, deformerId: DeformerId): DeformerPlacement? {
	val chain = ancestorChain(model, deformerId)

	if (chain.isEmpty()) {
		return null
	}

	val pose = PoseParameters(model, parameters)

	return when (val world = buildDeformerWorlds(chain, pose.paramValue, pose.defaultValue)[deformerId]) {
		is WarpWorld -> warpPlacement(world)
		is RotationWorld -> rotationPlacement(world)
		null -> null
	}
}

/**
 * The deformer and every ancestor the model holds, root first, stopping at a parent the model does not hold
 * (which the full bake treats as the root too) or at a cycle (which the full bake never builds, so the
 * shortened chain does not build either).
 *
 * @param PuppetModel model The rig.
 * @param DeformerId deformerId The deformer the chain ends at.
 * @return List<Deformer> The chain, root first; empty when the model does not hold the deformer.
 */
internal fun ancestorChain(model: PuppetModel, deformerId: DeformerId): List<Deformer> {
	val deformerById = model.deformers.associateBy { deformer -> deformer.id }
	val chain = ArrayList<Deformer>()
	val visited = HashSet<DeformerId>()
	var current = deformerById[deformerId]

	while (current != null && visited.add(current.id)) {
		chain.add(current)
		current = current.parent?.let { parentId -> deformerById[parentId] }
	}

	chain.reverse()

	return chain
}

/**
 * A warp's lattice bounds from its baked world control points.
 *
 * @param WarpWorld world The warp's baked world.
 * @return DeformerPlacement.Warp? The bounds, or null for a lattice with no points.
 */
private fun warpPlacement(world: WarpWorld): DeformerPlacement.Warp? {
	val extent = pointExtentOf(world.cp) ?: return null

	// The cascade's y grows downward, so its largest y is the lowest world z.
	return DeformerPlacement.Warp(worldMinX = extent.minX, worldMinZ = -extent.maxY, worldMaxX = extent.maxX, worldMaxZ = -extent.minY)
}

/**
 * A rotation's world pivot with its own angle and scale.
 *
 * @param RotationWorld world The rotation's baked world.
 * @return DeformerPlacement.Rotation The placement.
 */
private fun rotationPlacement(world: RotationWorld): DeformerPlacement.Rotation =
	DeformerPlacement.Rotation(
		worldPivotX = world.xform.ox,
		worldPivotZ = -world.xform.oy,
		angleDegrees = world.ownAngleDegrees,
		scale = world.ownScale,
	)