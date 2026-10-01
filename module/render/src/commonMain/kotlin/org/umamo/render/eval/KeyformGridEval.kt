package org.umamo.render.eval

import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.ParameterId

/*
 * Grid sampling over the runtime's shared multilinear corner selection. The corner selection,
 * bracket, and pose-sampling primitives (bindBracket / gridCorners / cellsByLinearIndex /
 * plus the blend-shape default-pose reference helpers) live in
 * org.umamo.runtime.eval (KeyformGridSampling.kt) so Moc3Import shares them - see the note there.
 * This file keeps the render-side blends built on top of them.
 */

/**
 * Samples an art-mesh keyform grid into local (parent-deformer-space) vertex positions:
 * `Σ wᵢ·(base + Δᵢ)` (interleaved x,y), the Umamo C++ Runtime's absolute-keyform blend (see
 * [meshLocalComponent]). Returns null when the mesh is hidden at these parameters.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions.
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? The local vertex positions, or null when hidden.
 */
internal fun sampleMeshLocal(
	grid: KeyformGrid<MeshDeltaForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val corners = gridCorners(grid, paramValue) ?: return null
	return blendLocalFromCorners(grid, base, corners)
}

/**
 * Blends a mesh's local positions from precomputed corners: `base + Σ wᵢ·Δᵢ`. Splitting corner selection
 * out of [sampleMeshLocal] lets `preparePose` compute the weights once - backend-neutrally - and feed both
 * the CPU apply path and the GPU shader the same corner set.
 *
 * A null [grid] is an UNKEYED drawable, not an error: it contributes no deltas, so the result is the rest
 * mesh.  An unkeyed drawable is a normal state - a freshly created one, or one whose last keyform axis was
 * just removed - and it has to render, or the rigger cannot see the thing they are about to key.
 *
 * @param KeyformGrid?       grid    The mesh's keyform grid, or null when the drawable is unkeyed.
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights (from [gridCorners]).
 * @return FloatArray The blended local positions (interleaved x,y).
 */
internal fun blendLocalFromCorners(grid: KeyformGrid<MeshDeltaForm>?, base: FloatArray, corners: List<WeightedCell>): FloatArray =
	blendedMeshLocal(grid, base, corners, null)

/**
 * A mesh's local positions at a pose - the keyform blend plus the blend shapes - accumulated in double and
 * rounded once per component (see [meshLocalComponent]).
 *
 * @param KeyformGrid?       grid    The mesh's keyform grid, or null when the drawable is unkeyed.
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights.
 * @param MeshBlendState?    blend   The drawable's resolved blend-shape state, or null when binding-free.
 * @return FloatArray The local positions (interleaved x,y), [base]'s length.
 */
internal fun blendedMeshLocal(
	grid: KeyformGrid<MeshDeltaForm>?,
	base: FloatArray,
	corners: List<WeightedCell>,
	blend: MeshBlendState?,
): FloatArray {
	if (grid == null && blend == null) {
		return base.copyOf()
	}
	val resolved = grid?.let { keyedGrid -> resolveMeshCorners(cellsByLinearIndex(keyedGrid), corners) }
	return FloatArray(base.size) { componentIndex -> meshLocalComponent(base, componentIndex, resolved, blend) }
}

/**
 * A pose's keyform corners resolved once to their cells' deltas, so a per-component blend reads arrays rather
 * than the cell map.
 *
 * @property Array<DoubleArray?> deltas    Each corner's cell deltas, null for a missing cell (the rest mesh).
 * @property FloatArray          weights   Each corner's weight.
 * @property Double              weightSum The weights' sum, missing cells included.
 */
internal class ResolvedMeshCorners(
	val deltas: Array<DoubleArray?>,
	val weights: FloatArray,
	val weightSum: Double,
)

/**
 * Resolves [corners] against a grid's [cells].
 *
 * @param Map<Int, KeyformCell<MeshDeltaForm>> cells   The grid's cells by linear index.
 * @param List<WeightedCell>                    corners The active corners.
 * @return ResolvedMeshCorners The resolved corners.
 */
internal fun resolveMeshCorners(cells: Map<Int, KeyformCell<MeshDeltaForm>>, corners: List<WeightedCell>): ResolvedMeshCorners {
	var weightSum = 0.0
	val weights = FloatArray(corners.size)
	val deltas =
		Array(corners.size) { cornerIndex ->
			val corner = corners[cornerIndex]
			weights[cornerIndex] = corner.weight
			weightSum += corner.weight
			cells[corner.linearIndex]?.form?.positionDeltas
		}
	return ResolvedMeshCorners(deltas, weights, weightSum)
}

/**
 * One component of a mesh's local position at a pose, in double, rounded once.
 *
 * The keyform blend is `base·Σw + Σ wᵢ·Δᵢ`, which is `Σ wᵢ·(base + Δᵢ)` - the weighted sum of the absolute
 * keyforms a Cubism runtime computes - rather than `base + Σ wᵢ·Δᵢ`.  The two agree only when the float
 * corner weights sum to exactly 1, and a deformer child's base is the canvas-space mesh while its keyforms are
 * in the parent's space, so `base·(1 − Σw)` would put the canvas magnitude's rounding back into a keyform a
 * thousandth of its size.  A missing cell, or a delta array too short to reach [componentIndex], is the rest
 * mesh: its weight still multiplies the base.  The blend shapes then add each contribution's form minus the
 * grid-at-default reference.  The CPU evaluator and the posed bounds both call this, so they cannot disagree.
 *
 * @param FloatArray           base           The mesh's rest-pose positions.
 * @param Int                  componentIndex Which component.
 * @param ResolvedMeshCorners? corners        The resolved keyform corners, or null for an unkeyed mesh (its base).
 * @param MeshBlendState?      blend          The drawable's resolved blend-shape state, or null.
 * @return Float The component.
 */
internal fun meshLocalComponent(base: FloatArray, componentIndex: Int, corners: ResolvedMeshCorners?, blend: MeshBlendState?): Float {
	val baseValue = base[componentIndex].toDouble()
	var local = baseValue
	if (corners != null) {
		var weighted = 0.0
		for (cornerIndex in corners.weights.indices) {
			val deltas = corners.deltas[cornerIndex] ?: continue
			if (componentIndex < deltas.size) {
				weighted += corners.weights[cornerIndex] * deltas[componentIndex]
			}
		}
		local = baseValue * corners.weightSum + weighted
	}
	if (blend != null) {
		val reference = blend.referenceDeltas?.getOrNull(componentIndex) ?: 0.0
		for (contribution in blend.contributions) {
			val deltas = contribution.form.positionDeltas
			if (componentIndex < deltas.size) {
				local += contribution.weight * (deltas[componentIndex] - reference)
			}
		}
	}
	return local.toFloat()
}

/**
 * An isolated part's pose-blended composite channels - what the renderer applies when it
 * composites the part's subtree layer back into the scene.
 *
 * @property Float    opacity       The composite opacity (0..1).
 * @property ColorRgb multiplyColor The composite multiply color.
 * @property ColorRgb screenColor   The composite screen color.
 */
internal class PartRenderState(
	val opacity: Float,
	val multiplyColor: ColorRgb,
	val screenColor: ColorRgb,
)

/**
 * Evaluates a direct (deformer-less) art mesh into world positions: the keyform-blended local
 * vertices with the Y component negated (`vp = (x, −y)`; only Y flips). This matches the CMO3/MOC3
 * world-space convention, which is Y-down relative to the local mesh space Umamo blends in - required for
 * preview parity with the official Cubism Editor. Returns null when the mesh is hidden at these
 * parameters. Deformer-parented meshes go through the cascade instead, which applies the same negation
 * after composing through the parent transform.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions (interleaved x,y).
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? World positions (interleaved x,y), or null when hidden.
 */
internal fun evalDirectMeshWorld(
	grid: KeyformGrid<MeshDeltaForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val world = sampleMeshLocal(grid, base, paramValue) ?: return null
	var yIndex = 1
	while (yIndex < world.size) {
		world[yIndex] = -world[yIndex]
		yIndex += 2
	}
	return world
}