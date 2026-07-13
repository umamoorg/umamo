package org.umamo.render.eval

import org.umamo.runtime.model.GlueForm
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PartDrawOrderForm

// Bracket tolerances match the Umamo C++ Runtime, needed for ULP-parity with the differential-oracle test.
// EN: a value within EPS_KEY of a key snaps to it; a key span below EPS_SPAN contributes no fraction.
private const val EPS_KEY = 0.001f
private const val EPS_SPAN = 0.0015f

// The Umamo C++ Runtime caps the multilinear corner set at 16 (kbCorners `maxc`); past that an axis snaps to its
// lower key instead of splitting. Replicated for fidelity (matters only for >4 fractional axes).
private const val MAX_CORNERS = 16

/**
 * One axis's bracket of a parameter value: the lower key [index] and the [fraction] toward `index+1`
 * (0 when snapped exactly onto a key).
 */
internal data class AxisBracket(val index: Int, val fraction: Float)

/**
 * A grid cell selected by the current parameters, addressed by its stride-folded [linearIndex], with
 * its multilinear [weight].
 */
internal data class WeightedCell(val linearIndex: Int, val weight: Float)

/** The per-pose scalar attributes blended from an art-mesh keyform grid: render order + opacity. */
internal data class MeshScalars(val drawOrder: Float, val opacity: Float)

/**
 * Brackets [value] against an axis's sorted [keys].  Returns null when the value is out of range
 * (the entity auto-hides / freezes), otherwise the lower key index and the blend fraction toward
 * the next key (0 when snapped onto a key).
 *
 * @param FloatArray keys  The axis's key positions (ascending parameter values).
 * @param Float      value The current parameter value on this axis.
 * @return AxisBracket? The bracket, or null when out of range.
 */
internal fun bindBracket(keys: FloatArray, value: Float): AxisBracket? {
	val keyCount = keys.size
	if (keyCount <= 1) {
		if (keyCount == 0) {
			return AxisBracket(0, 0f)
		}
		return if (value > keys[0] - EPS_KEY && value < keys[0] + EPS_KEY) AxisBracket(0, 0f) else null
	}
	if (value < keys[0] - EPS_KEY || value >= keys[keyCount - 1] + EPS_KEY) {
		return null
	}
	if (value < keys[0] + EPS_KEY) {
		return AxisBracket(0, 0f)
	}
	var hi = 1
	while (hi < keyCount && keys[hi] + EPS_KEY <= value) {
		hi++
	}
	if (value <= keys[hi] - EPS_KEY) {
		val span = keys[hi] - keys[hi - 1]
		val fraction = if (span >= EPS_SPAN) (value - keys[hi - 1]) / span else 0f
		return AxisBracket(hi - 1, fraction)
	}
	return AxisBracket(hi, 0f)
}

/**
 * The multilinear corner set for [grid] at the current parameters, or null when any controlling axis
 * is out of range (the entity is frozen).  Each fractional axis doubles the corner
 * list (weights ×= frac / 1−frac) and folds a stride-based linear cell index, matching how
 * [cellsByLinearIndex] folds each [KeyformCell.coordinate].
 *
 * @param KeyformGrid grid       The entity's keyform grid (only its axes are read).
 * @param Function    paramValue Current value for a given parameter id.
 * @return List<WeightedCell>? The weighted corner cells, or null when hidden.
 */
internal fun gridCorners(grid: KeyformGrid<*>, paramValue: (ParameterId) -> Float): List<WeightedCell>? {
	var corners = mutableListOf(WeightedCell(0, 1f))
	var stride = 1
	for (axis in grid.axes) {
		val bracket = bindBracket(axis.keys, paramValue(axis.parameterId)) ?: return null
		val split = bracket.fraction > 0f && corners.size * 2 <= MAX_CORNERS
		if (split) {
			val next = ArrayList<WeightedCell>(corners.size * 2)
			for (corner in corners) {
				next.add(
					WeightedCell(
						corner.linearIndex + bracket.index * stride,
						corner.weight * (1f - bracket.fraction),
					),
				)
				next.add(
					WeightedCell(
						corner.linearIndex + (bracket.index + 1) * stride,
						corner.weight * bracket.fraction,
					),
				)
			}
			corners = next
		} else {
			// Snap (on a key, or over the corner budget): every corner takes the lower key, weight kept.
			for (cornerIndex in corners.indices) {
				val corner = corners[cornerIndex]
				corners[cornerIndex] = WeightedCell(corner.linearIndex + bracket.index * stride, corner.weight)
			}
		}
		stride *= axis.keys.size
	}
	return corners
}

/**
 * Indexes a grid's cells by their stride-folded linear index (axis `a`'s stride = Π key counts of the
 * earlier axes), so a [WeightedCell.linearIndex] from [gridCorners] resolves to the matching cell.
 *
 * @param KeyformGrid grid The grid to index.
 * @return Map<Int, KeyformCell> linear index → cell.
 */
internal fun <TForm> cellsByLinearIndex(grid: KeyformGrid<TForm>): Map<Int, KeyformCell<TForm>> {
	val strides = IntArray(grid.axes.size)
	var stride = 1
	for (axisIndex in grid.axes.indices) {
		strides[axisIndex] = stride
		stride *= grid.axes[axisIndex].keys.size
	}
	val byIndex = HashMap<Int, KeyformCell<TForm>>(grid.cells.size)
	for (cell in grid.cells) {
		var linearIndex = 0
		val axisCount = minOf(cell.coordinate.size, strides.size)
		for (axisIndex in 0 until axisCount) {
			linearIndex += cell.coordinate[axisIndex] * strides[axisIndex]
		}
		byIndex[linearIndex] = cell
	}
	return byIndex
}

/**
 * Samples an art-mesh keyform grid into local (parent-deformer-space) vertex positions:
 * `base + Σ wᵢ·Δᵢ` (interleaved x,y). Because the corner weights sum to 1, this equals the
 * Umamo C++ Runtime's absolute-keyform blend. Returns null when the mesh is hidden at
 * these parameters.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param FloatArray  base       The mesh's rest-pose positions.
 * @param Function    paramValue Current value for a given parameter id.
 * @return FloatArray? The local vertex positions, or null when hidden.
 */
internal fun sampleMeshLocal(
	grid: KeyformGrid<MeshForm>,
	base: FloatArray,
	paramValue: (ParameterId) -> Float,
): FloatArray? {
	val corners = gridCorners(grid, paramValue) ?: return null
	return blendLocalFromCorners(grid, base, corners)
}

/**
 * Blends a mesh's local positions from precomputed corners: `base + Σ wᵢ·Δᵢ`. Splitting corner selection
 * out of [sampleMeshLocal] lets `preparePose` compute the weights once - backend-neutrally - and feed both
 * the CPU apply path and (later) the GPU shader the same corner set.
 *
 * @param KeyformGrid        grid    The mesh's keyform grid (source of the cells' deltas).
 * @param FloatArray         base    The mesh's rest-pose positions (interleaved x,y).
 * @param List<WeightedCell> corners The active keyform corners + weights (from [gridCorners]).
 * @return FloatArray The blended local positions (interleaved x,y).
 */
internal fun blendLocalFromCorners(grid: KeyformGrid<MeshForm>, base: FloatArray, corners: List<WeightedCell>): FloatArray {
	val cells = cellsByLinearIndex(grid)
	val out = base.copyOf()
	for (corner in corners) {
		val delta = cells[corner.linearIndex]?.form?.positionDeltas ?: continue
		val count = minOf(out.size, delta.size)
		for (coordIndex in 0 until count) {
			out[coordIndex] += corner.weight * delta[coordIndex]
		}
	}
	return out
}

/**
 * Blends an art-mesh keyform grid's scalar attributes (draw order, opacity) at the current parameters,
 * reusing the same multilinear corner weights as [sampleMeshLocal] (so the scalars track the geometry).
 * Returns null when the mesh is hidden - the same out-of-range condition that hides the geometry.
 *
 * @param KeyformGrid grid       The mesh's keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return MeshScalars? The blended draw order + opacity, or null when hidden.
 */
internal fun sampleMeshScalars(
	grid: KeyformGrid<MeshForm>,
	paramValue: (ParameterId) -> Float,
): MeshScalars? {
	val corners = gridCorners(grid, paramValue) ?: return null
	return blendScalarsFromCorners(grid, corners)
}

/**
 * Blends an art mesh's scalar attributes (draw order, opacity) from precomputed corners - the corners-first
 * counterpart of [sampleMeshScalars], so `preparePose` reuses the geometry's corner set.
 *
 * @param KeyformGrid        grid    The mesh's keyform grid.
 * @param List<WeightedCell> corners The active keyform corners + weights.
 * @return MeshScalars The blended draw order + opacity.
 */
internal fun blendScalarsFromCorners(grid: KeyformGrid<MeshForm>, corners: List<WeightedCell>): MeshScalars {
	val cells = cellsByLinearIndex(grid)
	var drawOrder = 0f
	var opacity = 0f
	for (corner in corners) {
		val form = cells[corner.linearIndex]?.form ?: continue
		drawOrder += corner.weight * form.drawOrder
		opacity += corner.weight * form.opacity
	}
	return MeshScalars(drawOrder, opacity)
}

/**
 * Blends a glue's weld intensity over its keyform grid at the current parameters. Returns 1 (full weld)
 * when the controlling axis is out of range - constant single-key glues always blend to their cell value.
 *
 * @param KeyformGrid grid       The glue's intensity keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return Float The blended weld intensity.
 */
internal fun sampleGlueIntensity(grid: KeyformGrid<GlueForm>, paramValue: (ParameterId) -> Float): Float {
	val corners = gridCorners(grid, paramValue) ?: return 1f
	val cells = cellsByLinearIndex(grid)
	var intensity = 0f
	for (corner in corners) {
		intensity += corner.weight * (cells[corner.linearIndex]?.form?.intensity ?: 0f)
	}
	return intensity
}

/**
 * Blends a draw-order group part's draw order over its keyform grid at the current parameters - the
 * animated part draw order for a parameter-driven group part. Returns null when the
 * controlling axis is out of range, so the caller falls back to the part's static draw order.
 *
 * @param KeyformGrid grid       The group part's draw-order keyform grid.
 * @param Function    paramValue Current value for a given parameter id.
 * @return Float? The blended part draw order, or null when out of range.
 */
internal fun samplePartDrawOrder(grid: KeyformGrid<PartDrawOrderForm>, paramValue: (ParameterId) -> Float): Float? {
	val corners = gridCorners(grid, paramValue) ?: return null
	val cells = cellsByLinearIndex(grid)
	var drawOrder = 0f
	for (corner in corners) {
		drawOrder += corner.weight * (cells[corner.linearIndex]?.form?.drawOrder ?: 0f)
	}
	return drawOrder
}

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
	grid: KeyformGrid<MeshForm>,
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
