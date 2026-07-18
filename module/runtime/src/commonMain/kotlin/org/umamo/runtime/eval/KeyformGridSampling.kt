package org.umamo.runtime.eval

import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm

/*
 * Pure keyform-grid sampling: the multilinear corner selection and the pose-sampling helpers that
 * both the renderer's evaluator (:render) and the MOC3 import (Moc3Import) must agree on. Hoisted
 * from :render's eval so the import can compute the blend-shape delta reference (the grid form at
 * the DEFAULT pose) with the EXACT same arithmetic the evaluator later subtracts - any divergence
 * between the two would leak into every MOC3-imported blend shape as a residual offset.
 */

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
public data class AxisBracket(val index: Int, val fraction: Float)

/**
 * A grid cell selected by the current parameters, addressed by its stride-folded [linearIndex], with
 * its multilinear [weight].
 */
public data class WeightedCell(val linearIndex: Int, val weight: Float)

/** The per-pose scalar attributes blended from an art-mesh keyform grid: render order + opacity. */
public data class MeshScalars(val drawOrder: Float, val opacity: Float)

/**
 * Brackets [value] against an axis's sorted [keys].  Returns null when the value is out of range
 * (the entity auto-hides / freezes), otherwise the lower key index and the blend fraction toward
 * the next key (0 when snapped onto a key).
 *
 * @param FloatArray keys  The axis's key positions (ascending parameter values).
 * @param Float      value The current parameter value on this axis.
 * @return AxisBracket? The bracket, or null when out of range.
 */
public fun bindBracket(keys: FloatArray, value: Float): AxisBracket? {
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
public fun gridCorners(grid: KeyformGrid<*>, paramValue: (ParameterId) -> Float): List<WeightedCell>? {
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
public fun <TForm> cellsByLinearIndex(grid: KeyformGrid<TForm>): Map<Int, KeyformCell<TForm>> {
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
 * Blends an art mesh's scalar attributes (draw order, opacity) from precomputed corners, so callers
 * can reuse the geometry's corner set (the scalars then track the geometry).
 *
 * @param KeyformGrid        grid    The mesh's keyform grid.
 * @param List<WeightedCell> corners The active keyform corners + weights.
 * @return MeshScalars The blended draw order + opacity.
 */
public fun blendScalarsFromCorners(grid: KeyformGrid<MeshForm>, corners: List<WeightedCell>): MeshScalars {
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
 * The drawable's grid form at the DEFAULT pose as position deltas vs the rest mesh - the shared
 * blend-shape delta reference (E5). Null when the drawable is ungridded or the default pose is out
 * of the grid's range (the reference is then zero). Static per drawable: the CPU pose prep, the
 * GPU delta-texture bake, and the MOC3 import all call this and must agree.
 *
 * @param Drawable drawable     The drawable.
 * @param Function defaultValue Default value per parameter id.
 * @return FloatArray? The interleaved reference deltas, or null.
 */
public fun meshGridDefaultDeltas(drawable: Drawable, defaultValue: (ParameterId) -> Float): FloatArray? {
	val grid = drawable.keyforms ?: return null
	val defaultCorners = gridCorners(grid, defaultValue) ?: return null
	val deltas = FloatArray(grid.cells.firstOrNull()?.form?.positionDeltas?.size ?: 0)
	val byLinearIndex = cellsByLinearIndex(grid)
	for (corner in defaultCorners) {
		val form = byLinearIndex[corner.linearIndex]?.form ?: continue
		for (componentIndex in deltas.indices) {
			deltas[componentIndex] += corner.weight * form.positionDeltas[componentIndex]
		}
	}
	return deltas
}

/**
 * The lattice control points grid-blended at the given pose, or null when the warp is unkeyed or
 * the pose is out of the grid's range.
 *
 * @param KeyformGrid? grid       The warp's keyform grid.
 * @param Function     paramValue Value per parameter id defining the pose.
 * @return FloatArray? The interleaved blended control points, or null.
 */
public fun warpControlPointsAt(grid: KeyformGrid<WarpForm>?, paramValue: (ParameterId) -> Float): FloatArray? {
	if (grid == null) {
		return null
	}
	val corners = gridCorners(grid, paramValue) ?: return null
	val byLinearIndex = cellsByLinearIndex(grid)
	var blended: FloatArray? = null
	for (corner in corners) {
		val form = byLinearIndex[corner.linearIndex]?.form ?: continue
		val target = blended ?: FloatArray(form.controlPoints.size).also { blended = it }
		for (componentIndex in target.indices) {
			target[componentIndex] += corner.weight * form.controlPoints[componentIndex]
		}
	}
	return blended
}

/**
 * The rotation transform grid-blended at the given pose, or null when unkeyed or out of range.
 * The flip flags snap to the first corner, mirroring the base eval.
 *
 * @param KeyformGrid? grid       The rotation's keyform grid.
 * @param Function     paramValue Value per parameter id defining the pose.
 * @return RotationForm? The blended transform, or null.
 */
public fun rotationFormAt(grid: KeyformGrid<RotationForm>?, paramValue: (ParameterId) -> Float): RotationForm? {
	if (grid == null) {
		return null
	}
	val corners = gridCorners(grid, paramValue) ?: return null
	val byLinearIndex = cellsByLinearIndex(grid)
	var originX = 0f
	var originY = 0f
	var angle = 0f
	var scale = 0f
	var first: RotationForm? = null
	for (corner in corners) {
		val form = byLinearIndex[corner.linearIndex]?.form ?: continue
		if (first == null) {
			first = form
		}
		originX += corner.weight * form.originX
		originY += corner.weight * form.originY
		angle += corner.weight * form.angle
		scale += corner.weight * form.scale
	}
	val flipSource = first ?: return null
	return RotationForm(originX, originY, angle, scale, flipSource.flipX, flipSource.flipY)
}
