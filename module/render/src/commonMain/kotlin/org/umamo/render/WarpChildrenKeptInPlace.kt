package org.umamo.render

import org.umamo.render.eval.PI_F
import org.umamo.render.eval.ParentSpaceMapping
import org.umamo.render.eval.PoseParameters
import org.umamo.render.eval.SINGULAR_DETERMINANT
import org.umamo.render.eval.WarpWorld
import org.umamo.render.eval.addWarpBlendDeltas
import org.umamo.render.eval.ancestorChain
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.render.eval.inheritedAngleRadians
import org.umamo.render.eval.latticeJacobianAt
import org.umamo.render.eval.localPosedOver
import org.umamo.render.eval.rotationBlendDeltas
import org.umamo.render.eval.warpApply
import org.umamo.render.eval.warpDifferenceJacobian
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
 * Keeping a reshaped warp's children where they rest.
 *
 * Whatever lives under a warp is stored in its lattice's normalized coordinates, so reshaping the lattice moves
 * it.  Edit mode sets a warp's size and shape up before it affects the art, so the art must not move: every
 * direct child is re-expressed in the reshaped lattice so it rests exactly where it rested.  Grandchildren then
 * follow at rest too, because their parent's world at rest is unchanged.
 *
 * A keyed child also stores its motion in the lattice's coordinates, so re-expressing only its rest would scale
 * that motion with the cage.  Each keyed offset is re-mapped through the cage's local stretch at its rest point
 * instead - the ratio of the reshaped lattice's Jacobian to the old one's - so the motion keeps its on-screen size
 * and direction: exact at rest, exact everywhere for an affine reshape, approximate on a strongly curved cage.
 */

/**
 * The step, in normalized lattice coordinates, of the forward differences a Jacobian outside the lattice is taken by:
 * coarse enough that a float difference of the extrapolation keeps its precision there.
 */
private const val EXTRAPOLATION_JACOBIAN_STEP = 1e-3f

/**
 * A model whose reshaped warp's children have been re-expressed so they rest where they rested, with the children
 * that could not be.
 *
 * @property PuppetModel model The model with every child that could be re-expressed rewritten.
 * @property List<DrawableId> unconvertedDrawables The direct child drawables left as they were: hidden at the
 *   default pose, or not reachable in the reshaped lattice.  Their art moves.
 * @property List<DeformerId> unconvertedDeformers The direct child deformers left as they were, for the same reasons.
 */
class WarpChildrenKeptInPlace(
	val model: PuppetModel,
	val unconvertedDrawables: List<DrawableId>,
	val unconvertedDeformers: List<DeformerId>,
)

/**
 * [after] with warp [warpId]'s direct children re-expressed so each rests where it rested in [before].
 *
 * [after] carries the warp's reshaped lattice with its children not yet touched.  Per child:
 *  - a drawable: each vertex's rest point is moved to where the reshaped lattice reaches the same world point,
 *    every keyform and blend-shape delta is re-mapped by the lattice's local stretch there, and the base is
 *    whatever puts the rest back on the moved point.  Its canvas mesh is untouched.
 *  - a warp: every control point of every form is moved the same way, about its rest lattice point.
 *  - a rotation: every form's origin is moved the same way, about its rest origin, and its base angle takes up
 *    the turn the reshaped lattice gives it there, so its world pivot and angle hold.
 *
 * A child the default pose hides, or one the reshaped lattice cannot reach within [LANDING_TOLERANCE_PX] (it
 * folds over or collapses there), keeps its numbers and is listed.  Nothing is converted when the default pose
 * hides the warp itself.
 *
 * @param PuppetModel before The model before the reshape.
 * @param PuppetModel after The model with the warp reshaped and its children as they were.
 * @param DeformerId warpId The reshaped warp.
 * @return WarpChildrenKeptInPlace The model with its children kept in place, and those that could not be.
 */
fun warpChildrenKeptInPlace(before: PuppetModel, after: PuppetModel, warpId: DeformerId): WarpChildrenKeptInPlace {
	val childDrawables = after.drawables.filter { drawable -> drawable.parentDeformerId == warpId && drawable.mesh != null }
	val childDeformers = after.deformers.filter { deformer -> deformer.parent == warpId }

	if (childDrawables.isEmpty() && childDeformers.isEmpty()) {
		return WarpChildrenKeptInPlace(after, emptyList(), emptyList())
	}

	val rest = PoseParameters(before, emptyMap())
	val oldWarp = buildDeformerWorlds(ancestorChain(before, warpId), rest.paramValue, rest.defaultValue)[warpId] as? WarpWorld
	val newWarp = buildDeformerWorlds(ancestorChain(after, warpId), rest.paramValue, rest.defaultValue)[warpId] as? WarpWorld

	if (oldWarp == null || newWarp == null) {
		return WarpChildrenKeptInPlace(after, childDrawables.map { drawable -> drawable.id }, childDeformers.map { deformer -> deformer.id })
	}

	val reshape = LatticeReshape(oldWarp, newWarp)
	val convertedDrawables = HashMap<DrawableId, Drawable>()
	val convertedDeformers = HashMap<DeformerId, Deformer>()

	for (drawable in childDrawables) {
		reshape.drawableKeptInPlace(drawable, rest)?.let { converted -> convertedDrawables[drawable.id] = converted }
	}

	for (deformer in childDeformers) {
		val converted =
			when (deformer) {
				is Deformer.Warp -> reshape.warpKeptInPlace(deformer, rest)
				is Deformer.Rotation -> reshape.rotationKeptInPlace(deformer, rest)
			}

		converted?.let { kept -> convertedDeformers[deformer.id] = kept }
	}

	return WarpChildrenKeptInPlace(
		model =
			after.copy(
				drawables = after.drawables.map { drawable -> convertedDrawables[drawable.id] ?: drawable },
				deformers = after.deformers.map { deformer -> convertedDeformers[deformer.id] ?: deformer },
			),
		unconvertedDrawables = childDrawables.map { drawable -> drawable.id }.filter { drawableId -> drawableId !in convertedDrawables },
		unconvertedDeformers = childDeformers.map { deformer -> deformer.id }.filter { deformerId -> deformerId !in convertedDeformers },
	)
}

/**
 * One warp's lattice before and after a reshape, both baked at the default pose, and how a set of points in its
 * normalized coordinates moves between them.
 *
 * @property WarpWorld oldWarp The warp's baked world before the reshape.
 * @property WarpWorld newWarp The warp's baked world after it.
 */
private class LatticeReshape(private val oldWarp: WarpWorld, private val newWarp: WarpWorld) {
	private val oldMapping = ParentSpaceMapping(oldWarp)
	private val newMapping = ParentSpaceMapping(newWarp)

	/**
	 * [drawable] re-expressed in the reshaped lattice, or null when it cannot be.
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
	 * [warp] re-expressed in the reshaped lattice, or null when it cannot be.
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
	 * [rotation] re-expressed in the reshaped lattice, or null when it cannot be.
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
		val turn = inheritedAt(newWarp, moves.moved) - inheritedAt(oldWarp, restOrigin)
		val grid =
			rotation.geometryGrid?.let { keyed ->
				KeyformGrid(
					keyed.axes,
					keyed.cells.map { cell ->
						val origin = moves.pointsMoved(floatArrayOf(cell.form.originX, cell.form.originY))
						KeyformCell(cell.coordinate, RotationPivotForm(origin[0], origin[1], cell.form.angle, cell.form.scale))
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
								RotationForm(origin[0], origin[1], form.angle, form.scale, form.flipX, form.flipY, form.opacity, form.multiplyColor, form.screenColor)
							}
						},
				)
			}

		// The child's world angle is its own angle minus what the lattice turns it by at its origin, so the base
		// angle takes up the change for the world angle to hold.
		return rotation.copy(baseAngle = rotation.baseAngle + wrapRadians(turn) * 180f / PI_F, geometryGrid = grid, blendShapes = blendShapes)
	}

	/**
	 * How [restPoints] move: each to where the reshaped lattice reaches the world point the old one put it at, with
	 * the per-point linear map that carries an offset from the old lattice to the new one.  Null when any point
	 * cannot be reached within [LANDING_TOLERANCE_PX] or the reshaped lattice is singular there.
	 *
	 * @param FloatArray restPoints Interleaved points in the warp's normalized coordinates.
	 * @return PointMoves? The moves, or null.
	 */
	private fun movesOf(restPoints: FloatArray): PointMoves? {
		val pointCount = restPoints.size / 2
		val world = oldMapping.localToWorld(restPoints)
		val moved = newMapping.worldToLocalLinearized(world, restPoints, (0 until pointCount).toSet())
		val landed = newMapping.localToWorld(moved)
		val linear = FloatArray(pointCount * 4)
		val oldJacobian = FloatArray(4)
		val newJacobian = FloatArray(4)

		for (pointIndex in 0 until pointCount) {
			if (abs(landed[pointIndex * 2] - world[pointIndex * 2]) > LANDING_TOLERANCE_PX || abs(landed[pointIndex * 2 + 1] - world[pointIndex * 2 + 1]) > LANDING_TOLERANCE_PX) {
				return null
			}

			jacobianOf(oldWarp, restPoints[pointIndex * 2], restPoints[pointIndex * 2 + 1], oldJacobian)
			jacobianOf(newWarp, moved[pointIndex * 2], moved[pointIndex * 2 + 1], newJacobian)
			val determinant = newJacobian[0] * newJacobian[3] - newJacobian[1] * newJacobian[2]

			if (abs(determinant) < SINGULAR_DETERMINANT) {
				return null
			}

			// The new Jacobian's inverse times the old one: an offset in the old lattice's coordinates to the offset
			// in the new one's that covers the same world distance.
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
}

/**
 * Where a set of points in a warp's normalized coordinates went in a reshape.
 *
 * @property FloatArray rest The points before, interleaved.
 * @property FloatArray moved The points after, interleaved: each lands where its rest point did in world space.
 * @property FloatArray linear Four values per point, the row-major 2x2 map of an offset from the old lattice's
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
	 * [offsets] (relative, one per rest point) carried from the old lattice's coordinates to the new one's: `M offset`.
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

/**
 * The Jacobian of [warp]'s world map at (u, v), written row-major into [out]: d(x)/du, d(x)/dv, d(y)/du, d(y)/dv.
 * Exact inside the lattice; outside it, in the extrapolation, by forward differences.
 *
 * @param WarpWorld warp The baked warp.
 * @param Float u The horizontal coordinate.
 * @param Float v The vertical coordinate.
 * @param FloatArray out Four slots for the Jacobian.
 */
private fun jacobianOf(warp: WarpWorld, u: Float, v: Float, out: FloatArray) {
	if (latticeJacobianAt(warp.cp, warp.cols, warp.rows, warp.bilinear, u, v, out)) {
		return
	}

	val origin = FloatArray(2)
	warpApply(warp.cp, warp.cols, warp.rows, warp.bilinear, u, v, origin, 0)
	warpDifferenceJacobian(warp, u, v, origin[0], origin[1], EXTRAPOLATION_JACOBIAN_STEP, FloatArray(2), out)
}

/**
 * The turn [warp] gives a child rotation at [origin], in radians, by the probe the rotation's own world build uses.
 *
 * @param WarpWorld warp The baked warp.
 * @param FloatArray origin The rotation's origin in the warp's normalized coordinates.
 * @return Float The inherited angle in radians.
 */
private fun inheritedAt(warp: WarpWorld, origin: FloatArray): Float {
	val scratch = FloatArray(2)
	warp.apply(origin[0], origin[1], scratch, 0)

	return inheritedAngleRadians(warp, origin[0], origin[1], scratch[0], scratch[1], scratch)
}