package org.umamo.render.puppet

import org.umamo.render.eval.DeformerWorld
import org.umamo.render.eval.MeshBlendState
import org.umamo.render.eval.meshLocalComponent
import org.umamo.render.eval.resolveMeshCorners
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.MeshDeltaForm
import kotlin.math.max
import kotlin.math.min

/*
 * Exact per-pose drawable world bounds - the screen rectangle the composite scissor is built from.
 *
 * The renderer confines a composite layer's clear, destination snapshot, and composite draw to the
 * isolated subtree's screen rect, so an isolated group costs fill proportional to its own extent
 * rather than the whole viewport.  Conservatism is the whole contract: an undersized bound clips
 * rendered pixels (a head-top or an arm past its warp lattice would vanish), so this evaluates the
 * SAME deform the shader does - `base + Sigma w_i * delta_i`, plus blend-shape deltas, through the
 * parent deformer, then the Y negation - and takes the true AABB of the deformed vertices.  A cheap
 * analytic lattice bound was tried and abandoned: a warp deformer's out-of-lattice extrapolation is
 * piecewise and does not decompose into a conservative closed form, so it undersized at the extremes.
 *
 * The per-vertex walk is allocation-free (a running min/max plus a 2-float transform scratch) and the
 * cells map is precomputed once at upload, so it costs only the composited drawables' vertices per
 * pose - a fraction of a full CPU deform, and dwarfed by the fill it saves.
 */

/** An axis-aligned bounding rectangle in the renderer's post-Y-negation world space. */
internal class PosedAabb(
	val minX: Float,
	val minY: Float,
	val maxX: Float,
	val maxY: Float,
)

/**
 * The exact deformed-world AABB of one drawable at the current pose: the keyform-blended local
 * vertices (plus blend-shape deltas) pushed through [parentWorld] and Y-negated, min/maxed.  Mirrors
 * `deformMeshWorldFromCorners` component for component so the bound can never disagree with the
 * rendered geometry.  Returns null when the mesh is empty.
 *
 * @param FloatArray                     base    The mesh's rest positions, interleaved x,y.
 * @param Map<Int, KeyformCell<MeshDeltaForm>> cells  The grid's cells by linear index (precomputed once).
 * @param List<WeightedCell>             corners The pose's active keyform corners and weights.
 * @param DeformerWorld?                 parentWorld The baked parent transform, or null for a direct mesh.
 * @param MeshBlendState?                blend   The pose's resolved blend-shape state, or null.
 * @return PosedAabb? The world bounds, or null when the mesh has no vertices or a vertex deforms to a
 *   non-finite position (an unusable bound the renderer treats as "no bound", keeping the full-viewport path).
 */
internal fun deformedWorldBounds(
	base: FloatArray,
	cells: Map<Int, KeyformCell<MeshDeltaForm>>,
	corners: List<WeightedCell>,
	parentWorld: DeformerWorld?,
	blend: MeshBlendState?,
): PosedAabb? {
	val vertexCount = base.size / 2
	if (vertexCount == 0) {
		return null
	}
	var minX = Float.POSITIVE_INFINITY
	var minY = Float.POSITIVE_INFINITY
	var maxX = Float.NEGATIVE_INFINITY
	var maxY = Float.NEGATIVE_INFINITY
	val scratch = FloatArray(2)
	// The keyform blend and the blend shapes, through the evaluator's own per-component helper so the bound
	// and the rendered geometry cannot disagree.
	val resolvedCorners = resolveMeshCorners(cells, corners)
	for (vertexIndex in 0 until vertexCount) {
		val componentX = vertexIndex * 2
		val componentY = componentX + 1
		val localX = meshLocalComponent(base, componentX, resolvedCorners, blend)
		val localY = meshLocalComponent(base, componentY, resolvedCorners, blend)
		val worldX: Float
		var worldZ: Float
		if (parentWorld != null) {
			parentWorld.apply(localX, localY, scratch, 0)
			worldX = scratch[0]
			worldZ = scratch[1]
		} else {
			worldX = localX
			worldZ = localY
		}
		worldZ = -worldZ // the renderer's final Y negation
		minX = min(minX, worldX)
		minY = min(minY, worldZ)
		maxX = max(maxX, worldX)
		maxY = max(maxY, worldZ)
	}
	// A non-finite extent (a warp-extrapolated vertex divided by a degenerate cell, or corrupt control
	// points) is NOT a usable bound: publish none, so the renderer keeps the safe full-viewport path
	// rather than deriving a garbage scissor rect that would clip the whole subtree.
	if (!minX.isFinite() || !minY.isFinite() || !maxX.isFinite() || !maxY.isFinite()) {
		return null
	}
	return PosedAabb(minX, minY, maxX, maxY)
}

/** The union of two bounds. */
internal fun unionBounds(first: PosedAabb, second: PosedAabb): PosedAabb =
	PosedAabb(
		min(first.minX, second.minX),
		min(first.minY, second.minY),
		max(first.maxX, second.maxX),
		max(first.maxY, second.maxY),
	)