package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.atan2

/**
 * A deformer's baked world transform - a warp lattice or a rotation affine - through which child
 * deformers and bound meshes transform their points. [accY] is the Y-scale accumulator passed to
 * child rotation deformers (warps pass the parent's through; rotations contribute their world scale).
 */
internal sealed interface DeformerWorld {
	val accY: Float

	/**
	 * Transforms point `(x, y)` through this deformer, writing to `out[outIndex]`,`out[outIndex+1]`.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination for the transformed point.
	 * @param Int        outIndex Index of the x slot in [out] (y is `outIndex+1`).
	 */
	fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int)
}

/**
 * A warp deformer's world transform: its baked world control points + lattice mode. The control-point
 * fields are `internal` (not `private`) so the GPU renderer in this module can upload them as the
 * shader's lattice uniforms - the same baked data the CPU `apply` walks via [warpApply].
 */
internal class WarpWorld(
	internal val cp: FloatArray,
	internal val cols: Int,
	internal val rows: Int,
	internal val bilinear: Boolean,
	override val accY: Float,
) : DeformerWorld {
	/**
	 * Warp-transforms `(x, y)` through the baked lattice.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination.
	 * @param Int        outIndex Index of the x slot.
	 */
	override fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int) {
		warpApply(cp, cols, rows, bilinear, x, y, out, outIndex)
	}
}

/** A rotation deformer's world transform: a baked affine. [xform] is `internal` so the GPU renderer can
 *  upload its 6 coefficients as shader uniforms (the same affine the CPU `apply` evaluates). */
internal class RotationWorld(
	internal val xform: RotationXform,
	override val accY: Float,
) : DeformerWorld {
	/**
	 * Applies the rotation affine to `(x, y)`.
	 *
	 * @param Float      x        Input x.
	 * @param Float      y        Input y.
	 * @param FloatArray out      Destination.
	 * @param Int        outIndex Index of the x slot.
	 */
	override fun apply(x: Float, y: Float, out: FloatArray, outIndex: Int) {
		xform.apply(x, y, out, outIndex)
	}
}

/**
 * Builds every deformer's world transform top-down (parents first), composing each local
 * keyform-blended transform with its parent's world transform.
 *
 * Out-of-range (hidden) deformers, and any whose ancestor is hidden, are omitted from the result. At
 * default parameters nothing is hidden, so this only affects out-of-range poses.
 *
 * @param List     deformers  All deformers in the model.
 * @param Function paramValue Current value for a given parameter id.
 * @return Map<DeformerId, DeformerWorld> The built world transforms, keyed by deformer id.
 */
internal fun buildDeformerWorlds(deformers: List<Deformer>, paramValue: (ParameterId) -> Float): Map<DeformerId, DeformerWorld> {
	val worlds = HashMap<DeformerId, DeformerWorld>(deformers.size)
	val ids = deformers.mapTo(HashSet()) { it.id }
	var pending = deformers
	var passes = 0
	while (pending.isNotEmpty() && passes <= deformers.size + 2) {
		passes++
		val deferred = ArrayList<Deformer>()
		var built = false
		for (deformer in pending) {
			val parentId = deformer.parent
			val parentKnown = parentId == null || parentId !in ids || parentId in worlds
			if (!parentKnown) {
				deferred.add(deformer)
				continue
			}
			val parentWorld = parentId?.let { worlds[it] }
			val world = buildDeformerWorld(deformer, paramValue, parentWorld)
			if (world != null) {
				worlds[deformer.id] = world
				built = true
			}
		}
		if (!built) {
			break // no progress: the rest are hidden or orphaned
		}
		pending = deferred
	}
	return worlds
}

/**
 * Builds one deformer's world transform given its parent's (or null at the root).
 *
 * @param Deformer       deformer    The deformer.
 * @param Function       paramValue  Current value for a given parameter id.
 * @param DeformerWorld? parentWorld The parent's world transform, or null.
 * @return DeformerWorld? The world transform, or null when hidden.
 */
private fun buildDeformerWorld(deformer: Deformer, paramValue: (ParameterId) -> Float, parentWorld: DeformerWorld?): DeformerWorld? =
	when (deformer) {
		is Deformer.Warp -> buildWarpWorld(deformer, paramValue, parentWorld)
		is Deformer.Rotation -> buildRotationWorld(deformer, paramValue, parentWorld)
	}

/**
 * Builds a warp's world transform: blend its keyform control points, then push each through the parent
 * (warps inherit the parent's accY; they add no scale of their own).
 *
 * @param Deformer.Warp  warp        The warp deformer.
 * @param Function       paramValue  Current value for a given parameter id.
 * @param DeformerWorld? parentWorld The parent's world transform, or null.
 * @return DeformerWorld? The world transform, or null when hidden.
 */
private fun buildWarpWorld(warp: Deformer.Warp, paramValue: (ParameterId) -> Float, parentWorld: DeformerWorld?): DeformerWorld? {
	val grid = warp.keyforms ?: return null
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	val pointCount = (warp.rows + 1) * (warp.columns + 1)
	val cp = FloatArray(pointCount * 2)
	for (corner in corners) {
		val controlPoints = cells[corner.linearIndex]?.form?.controlPoints ?: continue
		val count = minOf(cp.size, controlPoints.size)
		for (coordIndex in 0 until count) {
			cp[coordIndex] += corner.weight * controlPoints[coordIndex]
		}
	}
	val parentAccY = parentWorld?.accY ?: 1f
	if (parentWorld != null) {
		for (pointIndex in 0 until pointCount) {
			parentWorld.apply(cp[pointIndex * 2], cp[pointIndex * 2 + 1], cp, pointIndex * 2)
		}
	}
	return WarpWorld(cp, warp.columns, warp.rows, warp.isQuadTransform, parentAccY)
}

/**
 * Builds a rotation's world transform: blend its keyform origin/angle/scale (flip snaps to the first
 * corner), compose scale with the parent's accY, and - for a non-root - derive the inherited angle by
 * numerically probing the parent transform.
 *
 * @param Deformer.Rotation rotation    The rotation deformer.
 * @param Function          paramValue  Current value for a given parameter id.
 * @param DeformerWorld?    parentWorld The parent's world transform, or null.
 * @return DeformerWorld? The world transform, or null when hidden.
 */
private fun buildRotationWorld(rotation: Deformer.Rotation, paramValue: (ParameterId) -> Float, parentWorld: DeformerWorld?): DeformerWorld? {
	val grid = rotation.keyforms ?: return null
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	var originX = 0f
	var originY = 0f
	var scaleAccum = 0f
	var angleAccum = 0f
	for (corner in corners) {
		val form = cells[corner.linearIndex]?.form ?: continue
		originX += corner.weight * form.originX
		originY += corner.weight * form.originY
		scaleAccum += corner.weight * form.scale
		angleAccum += corner.weight * form.angle
	}
	val snapForm = cells[corners[0].linearIndex]?.form
	val flipX = snapForm?.flipX ?: false
	val flipY = snapForm?.flipY ?: false
	val angleDegrees = rotation.baseAngle + angleAccum
	val parentAccY = parentWorld?.accY ?: 1f
	val scale = parentAccY * scaleAccum
	val worldOriginX: Float
	val worldOriginY: Float
	val worldAngle: Float
	if (parentWorld == null) {
		worldOriginX = originX
		worldOriginY = originY
		worldAngle = angleDegrees
	} else {
		// Probe the parent's local rotation at the origin: displace along -Y and read the resulting
		// direction. -10 step for a rotation parent, -0.1 for a warp (degenerate steps shrink ×0.1).
		val disp = if (parentWorld is RotationWorld) -10f else -0.1f
		val scratch = FloatArray(2)
		parentWorld.apply(originX, originY, scratch, 0)
		val worldPosX = scratch[0]
		val worldPosY = scratch[1]
		var dx = 0f
		var dy = 0f
		var probeScale = 1f
		var iteration = 0
		while (iteration < 10) {
			parentWorld.apply(originX, originY + disp * probeScale, scratch, 0)
			dx = scratch[0] - worldPosX
			dy = scratch[1] - worldPosY
			if (dx != 0f || dy != 0f) {
				break
			}
			parentWorld.apply(originX, originY - disp * probeScale, scratch, 0)
			dx = worldPosX - scratch[0]
			dy = worldPosY - scratch[1]
			if (dx != 0f || dy != 0f) {
				break
			}
			probeScale *= 0.1f
			iteration++
		}
		var inherited = atan2(disp.toDouble(), 0.0).toFloat() - atan2(dy.toDouble(), dx.toDouble()).toFloat()
		while (inherited > PI_F) {
			inherited -= 2f * PI_F
		}
		while (inherited < -PI_F) {
			inherited += 2f * PI_F
		}
		worldOriginX = worldPosX
		worldOriginY = worldPosY
		worldAngle = angleDegrees - inherited * 180f / PI_F
	}
	return RotationWorld(rotationXform(worldAngle, scale, flipX, flipY, worldOriginX, worldOriginY), scale)
}

/**
 * Deforms a deformer-parented mesh: blend its local vertices, push each through the parent deformer's
 * world transform, then negate Y (reverse-coordinate). Returns null when the mesh is hidden.
 *
 * @param KeyformGrid    grid       The mesh's keyform grid.
 * @param FloatArray     base       The mesh's rest-pose positions (interleaved x,y).
 * @param DeformerWorld  parent     The mesh's parent deformer's world transform.
 * @param Function       paramValue Current value for a given parameter id.
 * @return FloatArray? World positions (interleaved x,y), or null when hidden.
 */
internal fun deformMeshThroughParent(grid: KeyformGrid<MeshForm>, base: FloatArray, parent: DeformerWorld, paramValue: (ParameterId) -> Float): FloatArray? {
	val local = sampleMeshLocal(grid, base, paramValue) ?: return null
	val world = FloatArray(local.size)
	val vertexCount = local.size / 2
	for (vertexIndex in 0 until vertexCount) {
		parent.apply(local[vertexIndex * 2], local[vertexIndex * 2 + 1], world, vertexIndex * 2)
		world[vertexIndex * 2 + 1] = -world[vertexIndex * 2 + 1]
	}
	return world
}

/**
 * Deforms a mesh to world positions from precomputed corners: blend `base + Σ wᵢ·Δᵢ`, push each vertex
 * through [parent] (or pass through when null - a direct, deformer-less mesh), then negate Y. This is the
 * corners-first union of [deformMeshThroughParent] and `evalDirectMeshWorld`, fed by `preparePose`'s
 * backend-neutral corner set; output is identical to the parameter-driven path.
 *
 * @param KeyformGrid        grid    The mesh's keyform grid.
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights.
 * @param DeformerWorld?     parent  The mesh's parent deformer world transform, or null when direct.
 * @return FloatArray World positions (interleaved x,y).
 */
internal fun deformMeshWorldFromCorners(
	grid: KeyformGrid<MeshForm>,
	base: FloatArray,
	corners: List<WeightedCell>,
	parent: DeformerWorld?,
): FloatArray {
	val local = blendLocalFromCorners(grid, base, corners)
	val world = FloatArray(local.size)
	val vertexCount = local.size / 2
	for (vertexIndex in 0 until vertexCount) {
		if (parent != null) {
			parent.apply(local[vertexIndex * 2], local[vertexIndex * 2 + 1], world, vertexIndex * 2)
		} else {
			world[vertexIndex * 2] = local[vertexIndex * 2]
			world[vertexIndex * 2 + 1] = local[vertexIndex * 2 + 1]
		}
		world[vertexIndex * 2 + 1] = -world[vertexIndex * 2 + 1]
	}
	return world
}
