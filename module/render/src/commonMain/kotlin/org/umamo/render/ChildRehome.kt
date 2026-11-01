package org.umamo.render

import org.umamo.render.eval.PI_F
import org.umamo.render.eval.ParentSpaceMapping
import org.umamo.render.eval.PoseParameters
import org.umamo.render.eval.SINGULAR_DETERMINANT
import org.umamo.render.eval.addWarpBlendDeltas
import org.umamo.render.eval.localPosedOver
import org.umamo.render.eval.parentSpaceAt
import org.umamo.render.eval.rotationBlendDeltas
import org.umamo.render.eval.warpGridLattice
import org.umamo.render.eval.wrapRadians
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.keyform.withEveryLatticeMapped
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import kotlin.math.abs

/*
 * Keeping children where they rest across a change of the space they are stored in.
 *
 * Whatever lives under a deformer is stored in that deformer's space: the canvas with no parent, a warp's normalized
 * lattice, a rotation's affine frame.  Changing that space - reshaping or resizing the warp, re-parenting the child,
 * deleting its parent so it falls to the grandparent, wrapping it in a new warp - would move it, so each child is
 * re-expressed in its new space so it rests exactly where it rested.  Grandchildren then follow at rest too, because
 * their parent's world at rest is unchanged.
 *
 * A keyed child also stores its motion in its parent's coordinates, so re-expressing only its rest would stretch that
 * motion with the change.  Each keyed offset is re-mapped through the local stretch at its rest point instead - the
 * new space's Jacobian inverse times the old one's - so the motion keeps its on-screen size and direction: exact at
 * rest, exact everywhere between affine spaces, approximate on a strongly curved lattice.
 */

/**
 * The children a rehome re-expresses.
 *
 * @property Collection<DrawableId> drawables The child drawables; one without a mesh has nothing to convert.
 * @property Collection<DeformerId> deformers The child warps and rotations.
 */
class RehomedChildren(
	val drawables: Collection<DrawableId> = emptyList(),
	val deformers: Collection<DeformerId> = emptyList(),
)

/**
 * A model whose children have been re-expressed so they rest where they rested, with the children that could not be.
 *
 * @property PuppetModel model The model with every child that could be re-expressed rewritten.
 * @property List<DrawableId> unconvertedDrawables The child drawables left as they were: hidden at the default pose,
 *   or not reachable in their new space.  Their art moves.
 * @property List<DeformerId> unconvertedDeformers The child deformers left as they were, for the same reasons.
 */
class ChildrenRehomed(
	val model: PuppetModel,
	val unconvertedDrawables: List<DrawableId>,
	val unconvertedDeformers: List<DeformerId>,
) {
	/** How many children could not be kept in place. */
	val unconvertedCount: Int get() = unconvertedDrawables.size + unconvertedDeformers.size
}

/**
 * [after] with [children] re-expressed so each rests where it rested in [before].
 *
 * Each child is read in its parent's space as [before] has it and re-expressed in its parent's space as [after] has
 * it - the same parent reshaped or resized, a new parent, or none.  [after] carries every change but the children's
 * own numbers.  Per child:
 *  - a drawable: each vertex's rest point moves to where the new space reaches the same world point, every keyform
 *    and blend-shape delta is re-mapped by the local stretch there, and the base is whatever puts the rest back on
 *    the moved point.  Its canvas mesh is untouched.
 *  - a warp: every control point of every form moves the same way, about its rest lattice point.
 *  - a rotation: every form's origin moves the same way, about its rest origin, its base angle takes up the turn the
 *    new space gives it there, and its scales take up the change in the scale the new space hands it, so its world
 *    pivot, angle, and size hold.
 *
 * A child the default pose hides, one whose old or new space the default pose hides, or one the new space cannot reach
 * within [LANDING_TOLERANCE_PX] (it folds over or collapses there) keeps its numbers and is listed.
 *
 * @param PuppetModel before The model before the change.
 * @param PuppetModel after The model after it, with the children's own numbers as they were.
 * @param RehomedChildren children The children to re-express.
 * @return ChildrenRehomed The model with its children kept in place, and those that could not be.
 */
fun childrenRehomed(before: PuppetModel, after: PuppetModel, children: RehomedChildren): ChildrenRehomed {
	if (children.drawables.isEmpty() && children.deformers.isEmpty()) {
		return ChildrenRehomed(after, emptyList(), emptyList())
	}

	val rest = PoseParameters(before, emptyMap())
	val oldSpaces = RestSpaces(before)
	val newSpaces = RestSpaces(after)
	val convertedDrawables = HashMap<DrawableId, Drawable>()
	val convertedDeformers = HashMap<DeformerId, Deformer>()
	val unconvertedDrawables = ArrayList<DrawableId>()
	val unconvertedDeformers = ArrayList<DeformerId>()

	for (drawableId in children.drawables) {
		val drawable = after.drawables.firstOrNull { candidate -> candidate.id == drawableId } ?: continue

		if (drawable.mesh == null) {
			continue
		}

		val previous = before.drawables.firstOrNull { candidate -> candidate.id == drawableId }
		val change = previous?.let { ParentChange.between(oldSpaces.of(previous.parentDeformerId), newSpaces.of(drawable.parentDeformerId)) }
		val converted = change?.drawableKeptInPlace(drawable, rest)

		if (converted == null) {
			unconvertedDrawables.add(drawableId)
		} else {
			convertedDrawables[drawableId] = converted
		}
	}

	for (deformerId in children.deformers) {
		val deformer = after.deformerById[deformerId] ?: continue
		val previous = before.deformerById[deformerId]
		val change = previous?.let { ParentChange.between(oldSpaces.of(previous.parent), newSpaces.of(deformer.parent)) }
		val converted =
			when (deformer) {
				is Deformer.Warp -> change?.warpKeptInPlace(deformer, rest)
				is Deformer.Rotation -> change?.rotationKeptInPlace(deformer, rest)
			}

		if (converted == null) {
			unconvertedDeformers.add(deformerId)
		} else {
			convertedDeformers[deformerId] = converted
		}
	}

	return ChildrenRehomed(
		model =
			after.copy(
				drawables = after.drawables.map { drawable -> convertedDrawables[drawable.id] ?: drawable },
				deformers = after.deformers.map { deformer -> convertedDeformers[deformer.id] ?: deformer },
			),
		unconvertedDrawables = unconvertedDrawables,
		unconvertedDeformers = unconvertedDeformers,
	)
}

/**
 * Each parent's space at the default pose in one model, baked once per parent however many children read it.
 *
 * @property PuppetModel model The model.
 */
private class RestSpaces(private val model: PuppetModel) {
	private val spaces = HashMap<DeformerId?, ParentSpaceMapping?>()

	/**
	 * The space under [parentId] at the default pose, or null when the pose hides it.
	 *
	 * @param DeformerId? parentId The parent, or null for the root.
	 * @return ParentSpaceMapping? The space, or null.
	 */
	fun of(parentId: DeformerId?): ParentSpaceMapping? = spaces.getOrPut(parentId) { parentSpaceAt(model, parentId) }
}

/**
 * A child's space before and after a change, both at the default pose, and how a set of points in the old space's
 * coordinates moves into the new one's.
 *
 * @property ParentSpaceMapping oldSpace The space the child was stored in.
 * @property ParentSpaceMapping newSpace The space it is stored in after the change.
 */
private class ParentChange(private val oldSpace: ParentSpaceMapping, private val newSpace: ParentSpaceMapping) {
	/**
	 * [drawable] re-expressed in the new space, or null when it cannot be.
	 *
	 * @param Drawable drawable The child drawable, which carries a mesh.
	 * @param PoseParameters rest The default pose's lookups.
	 * @return Drawable? The re-expressed drawable, or null.
	 */
	fun drawableKeptInPlace(drawable: Drawable, rest: PoseParameters): Drawable? {
		val mesh = drawable.mesh ?: return null
		val restPoints = localPosedOver(drawable, rest.paramValue) ?: return null
		val moves = movesOf(restPoints) ?: return null
		val restDeltas = FloatArray(restPoints.size) { componentIndex -> restPoints[componentIndex] - mesh.localPositions.getOrElse(componentIndex) { 0f } }
		val mappedRestDeltas = moves.offsetsMapped(restDeltas)
		val base = FloatArray(restPoints.size) { componentIndex -> moves.moved[componentIndex] - mappedRestDeltas[componentIndex] }
		val grid =
			drawable.geometryGrid?.let { keyed ->
				KeyformGrid(keyed.axes, keyed.cells.map { cell -> KeyformCell(cell.coordinate, MeshDeltaForm(moves.offsetsMapped(cell.form.positionDeltas))) })
			}
		val blendShapes =
			drawable.blendShapes.map { binding ->
				binding.copy(
					forms =
						binding.forms.map { form ->
							form?.let { MeshForm(moves.offsetsMapped(form.positionDeltas), form.drawOrder, form.opacity, form.multiplyColor, form.screenColor) }
						},
				)
			}

		return drawable.copy(mesh = DrawableMesh(mesh.positions, base, mesh.uvs, mesh.indices), geometryGrid = grid, blendShapes = blendShapes)
	}

	/**
	 * [warp] re-expressed in the new space, or null when it cannot be.
	 *
	 * @param Deformer.Warp warp The child warp.
	 * @param PoseParameters rest The default pose's lookups.
	 * @return Deformer.Warp? The re-expressed warp, or null.
	 */
	fun warpKeptInPlace(warp: Deformer.Warp, rest: PoseParameters): Deformer.Warp? {
		val restLattice = warpGridLattice(warp, rest.paramValue, override = null) ?: return null
		addWarpBlendDeltas(warp, rest.paramValue, rest.defaultValue, restLattice)
		val moves = movesOf(restLattice) ?: return null

		return warp.withEveryLatticeMapped { points -> moves.pointsMoved(points) }
	}

	/**
	 * [rotation] re-expressed in the new space, or null when it cannot be.
	 *
	 * @param Deformer.Rotation rotation The child rotation.
	 * @param PoseParameters rest The default pose's lookups.
	 * @return Deformer.Rotation? The re-expressed rotation, or null.
	 */
	fun rotationKeptInPlace(rotation: Deformer.Rotation, rest: PoseParameters): Deformer.Rotation? {
		val restPivot = rotationFormAt(rotation.geometryGrid, rest.paramValue) ?: return null
		val restBlend = rotationBlendDeltas(rotation, rest.paramValue, rest.defaultValue)
		val restOrigin = floatArrayOf(restPivot.originX + (restBlend?.originX ?: 0f), restPivot.originY + (restBlend?.originY ?: 0f))
		val moves = movesOf(restOrigin) ?: return null
		val turn = newSpace.inheritedAngleAt(moves.moved[0], moves.moved[1]) - oldSpace.inheritedAngleAt(restOrigin[0], restOrigin[1])
		// The child's world scale is the scale its space hands it times its own, so its own takes up the change.
		val newInheritedScale = newSpace.inheritedScale
		val scaleRatio = if (newInheritedScale == 0f) 1f else oldSpace.inheritedScale / newInheritedScale
		val grid =
			rotation.geometryGrid?.let { keyed ->
				KeyformGrid(
					keyed.axes,
					keyed.cells.map { cell ->
						val origin = moves.pointsMoved(floatArrayOf(cell.form.originX, cell.form.originY))
						KeyformCell(cell.coordinate, RotationPivotForm(origin[0], origin[1], cell.form.angle, cell.form.scale * scaleRatio))
					},
				)
			}
		val blendShapes =
			rotation.blendShapes.map { binding ->
				binding.copy(
					forms =
						binding.forms.map { form ->
							form?.let {
								val origin = moves.pointsMoved(floatArrayOf(form.originX, form.originY))
								RotationForm(origin[0], origin[1], form.angle, form.scale * scaleRatio, form.flipX, form.flipY, form.opacity, form.multiplyColor, form.screenColor)
							}
						},
				)
			}

		// The child's world angle is its own angle minus what its space turns it by at its origin, so the base angle
		// takes up the change for the world angle to hold.
		return rotation.copy(baseAngle = rotation.baseAngle + wrapRadians(turn) * 180f / PI_F, geometryGrid = grid, blendShapes = blendShapes)
	}

	/**
	 * How [restPoints] move: each to where the new space reaches the world point the old one put it at, with the
	 * per-point linear map that carries an offset from the old space to the new one.  Null when any point cannot be
	 * reached within [LANDING_TOLERANCE_PX] or the new space is singular there.
	 *
	 * The new space's inverse is seeded from the points themselves when both spaces are lattices (a reshape or a
	 * move between warps, whose coordinates share a range), and from the new lattice's middle otherwise: an old
	 * canvas or frame coordinate says nothing about where a point sits in a lattice.
	 *
	 * @param FloatArray restPoints Interleaved points in the old space's coordinates.
	 * @return PointMoves? The moves, or null.
	 */
	private fun movesOf(restPoints: FloatArray): PointMoves? {
		val pointCount = restPoints.size / 2
		val world = oldSpace.localToWorld(restPoints)
		val seed = if (oldSpace.isLattice || !newSpace.isLattice) restPoints else FloatArray(restPoints.size) { LATTICE_MIDDLE }
		val moved = newSpace.worldToLocalLinearized(world, seed, (0 until pointCount).toSet())
		val landed = newSpace.localToWorld(moved)
		val linear = FloatArray(pointCount * 4)
		val oldJacobian = FloatArray(4)
		val newJacobian = FloatArray(4)

		for (pointIndex in 0 until pointCount) {
			if (abs(landed[pointIndex * 2] - world[pointIndex * 2]) > LANDING_TOLERANCE_PX || abs(landed[pointIndex * 2 + 1] - world[pointIndex * 2 + 1]) > LANDING_TOLERANCE_PX) {
				return null
			}

			oldSpace.jacobianAt(restPoints[pointIndex * 2], restPoints[pointIndex * 2 + 1], oldJacobian)
			newSpace.jacobianAt(moved[pointIndex * 2], moved[pointIndex * 2 + 1], newJacobian)
			val determinant = newJacobian[0] * newJacobian[3] - newJacobian[1] * newJacobian[2]

			if (abs(determinant) < SINGULAR_DETERMINANT) {
				return null
			}

			// The new Jacobian's inverse times the old one: an offset in the old space's coordinates to the offset in
			// the new one's that covers the same world distance.
			val inverse00 = newJacobian[3] / determinant
			val inverse01 = -newJacobian[1] / determinant
			val inverse10 = -newJacobian[2] / determinant
			val inverse11 = newJacobian[0] / determinant
			linear[pointIndex * 4] = inverse00 * oldJacobian[0] + inverse01 * oldJacobian[2]
			linear[pointIndex * 4 + 1] = inverse00 * oldJacobian[1] + inverse01 * oldJacobian[3]
			linear[pointIndex * 4 + 2] = inverse10 * oldJacobian[0] + inverse11 * oldJacobian[2]
			linear[pointIndex * 4 + 3] = inverse10 * oldJacobian[1] + inverse11 * oldJacobian[3]
		}

		return PointMoves(restPoints, moved, linear)
	}

	companion object {
		/**
		 * The change between [oldSpace] and [newSpace], or null when either is missing (the default pose hides it).
		 *
		 * @param ParentSpaceMapping? oldSpace The space before.
		 * @param ParentSpaceMapping? newSpace The space after.
		 * @return ParentChange? The change, or null.
		 */
		fun between(oldSpace: ParentSpaceMapping?, newSpace: ParentSpaceMapping?): ParentChange? =
			if (oldSpace == null || newSpace == null) null else ParentChange(oldSpace, newSpace)
	}
}

/**
 * How far, in canvas pixels, a re-expressed point may land from the world point it was re-expressed from.  Above the
 * warp inverse's own convergence (1e-5 of the lattice's span) for any lattice up to ten thousand pixels across, and far
 * below the whole pixels a point the inverse could not reach misses by.
 */
internal const val LANDING_TOLERANCE_PX = 0.1f

/** The normalized lattice coordinate of a lattice's middle, the inverse's seed when the old coordinates mean nothing. */
private const val LATTICE_MIDDLE = 0.5f

/**
 * Where a set of points went in a change of space.
 *
 * @property FloatArray rest The points before, interleaved, in the old space's coordinates.
 * @property FloatArray moved The points after, interleaved, in the new space's: each lands where its rest point did in
 *   world space.
 * @property FloatArray linear Four values per point, the row-major 2x2 map of an offset from the old space's
 *   coordinates to the new one's at that point.
 */
private class PointMoves(val rest: FloatArray, val moved: FloatArray, val linear: FloatArray) {
	/**
	 * [points] (absolute, one per rest point) moved about their rest points: `moved + M (point - rest)`.
	 *
	 * @param FloatArray points Interleaved points, as many as the rest points.
	 * @return FloatArray The moved points, a fresh array.
	 */
	fun pointsMoved(points: FloatArray): FloatArray {
		val offsets = FloatArray(points.size) { componentIndex -> points[componentIndex] - rest.getOrElse(componentIndex) { 0f } }
		val mapped = offsetsMapped(offsets)

		return FloatArray(points.size) { componentIndex -> moved.getOrElse(componentIndex) { 0f } + mapped[componentIndex] }
	}

	/**
	 * [offsets] (relative, one per rest point) carried from the old space's coordinates to the new one's: `M offset`.
	 *
	 * @param FloatArray offsets Interleaved offsets, as many as the rest points.
	 * @return FloatArray The mapped offsets, a fresh array.
	 */
	fun offsetsMapped(offsets: FloatArray): FloatArray {
		val mapped = FloatArray(offsets.size)
		val pointCount = minOf(offsets.size, rest.size) / 2

		for (pointIndex in 0 until pointCount) {
			val offsetX = offsets[pointIndex * 2]
			val offsetY = offsets[pointIndex * 2 + 1]
			mapped[pointIndex * 2] = linear[pointIndex * 4] * offsetX + linear[pointIndex * 4 + 1] * offsetY
			mapped[pointIndex * 2 + 1] = linear[pointIndex * 4 + 2] * offsetX + linear[pointIndex * 4 + 3] * offsetY
		}

		return mapped
	}
}