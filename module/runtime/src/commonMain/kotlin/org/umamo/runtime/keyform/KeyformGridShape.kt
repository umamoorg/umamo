package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/*
 * Pure shape queries and shape edits over a KeyformGrid: strides, the linear cell index, density
 * checks, and axis removal.  Nothing here interpolates a form and nothing here evaluates a pose - the
 * multilinear blend stays in runtime.eval, which this package must never duplicate.
 *
 * This lives in :runtime rather than :edit because the CMO3 / MOC3 importers need it and :runtime
 * cannot depend on :edit.
 *
 * キーフォーム格子の形状に関する純粋な問い合わせと編集（ストライド・線形インデックス・軸の削除）。
 * 補間や姿勢評価は行わない。
 */

/**
 * The number of simultaneously fractional axes past which the evaluator stops splitting corners.
 *
 * KeyformGridSampling caps its corner set at 16 (the Umamo C++ Runtime's kbCorners maxc), so a fifth
 * fractional axis snaps to its lower key rather than doubling the set.  A grid over the budget still
 * evaluates, but inserting a key into one can change which axes snap - so grid-editing callers warn
 * rather than silently reshaping it.
 */
private const val MAX_FRACTIONAL_AXES = 4

/**
 * The grid's linear cell extent: the product of every axis's key count.
 *
 * Zero for a grid carrying an empty axis, which is a malformed grid rather than a rest cell; the
 * delta-texture sizing in :render floors its own copy at 1 because a texture cannot be zero-wide.
 *
 * @return Int The number of cells a dense grid of this shape holds.
 */
val KeyformGrid<*>.cellCount: Int
	get() = axes.fold(1) { count, axis -> count * axis.keys.size }

/**
 * Whether this grid has more fractional axes than the evaluator's corner budget can split.
 *
 * @return Boolean True when an edit to this grid could change which axes the evaluator snaps.
 */
val KeyformGrid<*>.exceedsCornerBudget: Boolean
	get() = axes.size > MAX_FRACTIONAL_AXES

/**
 * This grid's per-axis strides for folding a coordinate into a linear cell index.
 *
 * Axis 0 is the FASTEST varying (stride 1).  This must stay identical to the folding in
 * KeyformGridSampling's cellsByLinearIndex and gridCorners, because the resulting index is the shared
 * contract between a WeightedCell, a stored cell, and the GPU delta texture's column - pinned by
 * KeyformGridShapeTest.
 *
 * @return IntArray One stride per axis, in axis order.
 */
fun KeyformGrid<*>.strides(): IntArray {
	val strides = IntArray(axes.size)
	var stride = 1
	for (axisIndex in axes.indices) {
		strides[axisIndex] = stride
		stride *= axes[axisIndex].keys.size
	}
	return strides
}

/**
 * Folds a per-axis key-index [coordinate] into this grid's linear cell index.
 *
 * A coordinate shorter than the axis list contributes only the axes it covers, matching
 * cellsByLinearIndex's defensive handling of a malformed imported cell.
 *
 * @param IntArray coordinate The key index per axis, in axis order.
 * @return Int The stride-folded linear index.
 */
fun KeyformGrid<*>.linearIndexOf(coordinate: IntArray): Int {
	val strides = strides()
	var linearIndex = 0
	val axisCount = minOf(coordinate.size, strides.size)
	for (axisIndex in 0 until axisCount) {
		linearIndex += coordinate[axisIndex] * strides[axisIndex]
	}
	return linearIndex
}

/**
 * Unfolds a [linearIndex] back into its per-axis key-index coordinate - the inverse of [linearIndexOf]
 * for any index inside [cellCount].
 *
 * @param Int linearIndex The stride-folded cell index.
 * @return IntArray The key index per axis, in axis order.
 */
fun KeyformGrid<*>.coordinateOf(linearIndex: Int): IntArray {
	val coordinate = IntArray(axes.size)
	var remaining = linearIndex
	for (axisIndex in axes.indices) {
		val keyCount = axes[axisIndex].keys.size
		if (keyCount <= 0) {
			continue
		}
		coordinate[axisIndex] = remaining % keyCount
		remaining /= keyCount
	}
	return coordinate
}

/**
 * Whether this grid holds exactly one cell per coordinate in its shape.
 *
 * A CMO3 grid can arrive SPARSE - Cmo3Import drops cells whose form guid does not resolve - and a
 * sparse grid blends toward zero rather than erroring.  Compaction and refine both refuse a sparse
 * grid rather than inventing the missing cells, so they ask this first.
 *
 * @return Boolean True when the grid is dense and every cell index is in range and distinct.
 */
val KeyformGrid<*>.isDense: Boolean
	get() {
		val expectedCellCount = cellCount
		if (expectedCellCount <= 0 || cells.size != expectedCellCount) {
			return false
		}
		val seenIndices = HashSet<Int>(cells.size)
		for (cell in cells) {
			if (cell.coordinate.size != axes.size) {
				return false
			}
			val linearIndex = linearIndexOf(cell.coordinate)
			if (linearIndex < 0 || linearIndex >= expectedCellCount || !seenIndices.add(linearIndex)) {
				return false
			}
		}
		return true
	}

/**
 * The index of the axis keying on [parameterId], or -1 when this grid has no such axis.
 *
 * @param ParameterId parameterId The parameter to look for.
 * @return Int The axis index, or -1 when absent.
 */
fun KeyformGrid<*>.axisIndexOf(parameterId: ParameterId): Int = axes.indexOfFirst { axis -> axis.parameterId == parameterId }

/**
 * This grid with the axis for [parameterId] removed, collapsing that dimension to the key nearest
 * [keepKeyValue]. Returns this same instance when the grid has no such axis (so a caller can skip
 * untouched entities by identity), or null when removing the axis leaves no axes at all (the entity
 * becomes unkeyed). The surviving cells are those whose coordinate on the dropped axis is the kept key
 * index, each re-projected to the N-1 coordinate; the form payloads are carried through untouched.
 *
 * The kept slice is the NEAREST key, not an interpolated one, so collapsing an axis whose kept value
 * falls between keys discards the interpolated pose rather than baking it.
 *
 * @param ParameterId parameterId The axis parameter to remove.
 * @param Float keepKeyValue The parameter value whose nearest key slice survives (the deleted default).
 * @return KeyformGrid The collapsed grid, this if the axis was absent, or null if no axes remain.
 */
fun <TForm> KeyformGrid<TForm>.withAxisCollapsed(parameterId: ParameterId, keepKeyValue: Float): KeyformGrid<TForm>? {
	val axisIndex = axisIndexOf(parameterId)
	if (axisIndex < 0) {
		return this
	}
	val newAxes = axes.filterIndexed { index, _ -> index != axisIndex }
	if (newAxes.isEmpty()) {
		return null
	}
	val keepIndex = nearestKeyIndex(axes[axisIndex].keys, keepKeyValue)
	val newCells =
		cells
			.filter { cell -> cell.coordinate[axisIndex] == keepIndex }
			.map { cell -> KeyformCell(cell.coordinate.withElementRemoved(axisIndex), cell.form) }
	return KeyformGrid(newAxes, newCells)
}

/**
 * The index of the key on [parameterId]'s axis that [value] sits exactly on, or -1.
 *
 * "Exactly" means within the evaluator's own EPS_KEY snap tolerance, so this agrees with what is actually
 * being rendered: if the pose resolves to a key, this finds it.  Authoring ops use it to decide whether an
 * insert lands on an existing key (overwrite) or between two (reshape).
 *
 * @param ParameterId parameterId The axis to look on.
 * @param Float value The pose value.
 * @return Int The key index, or -1 when the axis is absent or no key is in range.
 */
fun KeyformGrid<*>.keyIndexAt(parameterId: ParameterId, value: Float): Int {
	val axisIndex = axisIndexOf(parameterId)
	if (axisIndex < 0) {
		return -1
	}
	return axes[axisIndex].keys.indexOfFirst { key -> abs(key - value) < EPS_KEY }
}

/**
 * Every one of this owner's channel tracks with the axis for [parameterId] collapsed out.
 *
 * The generic counterpart of [withAxisCollapsed] for a whole owner, so deleting a parameter scrubs every
 * track in one walk rather than needing a hand-written case per channel - which is exactly how a glue's
 * intensity grid was missed before.  A track left with no axes is dropped entirely (its owner's static
 * value takes over).  Returns this same instance when no track referenced the parameter.
 *
 * @param ParameterId parameterId The axis parameter to remove from every track.
 * @param Float keepKeyValue The parameter value whose nearest key slice survives.
 * @return ChannelGrids The scrubbed tracks, or this when nothing referenced the parameter.
 */
fun ChannelGrids.withAxisCollapsed(parameterId: ParameterId, keepKeyValue: Float): ChannelGrids {
	var changed = false
	val scrubbed = LinkedHashMap<FormChannel, KeyformGrid<ChannelValue>>(gridsByChannel.size)
	for ((channel, grid) in gridsByChannel) {
		val collapsed = grid.withAxisCollapsed(parameterId, keepKeyValue)
		if (collapsed === grid) {
			scrubbed[channel] = grid
			continue
		}
		changed = true
		if (collapsed != null) {
			scrubbed[channel] = collapsed
		}
	}
	return if (changed) ChannelGrids(scrubbed) else this
}

/**
 * The index of the key in [keys] nearest [value] (0 for an empty axis, defensively).
 *
 * @param FloatArray keys The axis's key positions.
 * @param Float value The parameter value to match.
 * @return Int The nearest key's index.
 */
private fun nearestKeyIndex(keys: FloatArray, value: Float): Int {
	if (keys.isEmpty()) {
		return 0
	}
	var bestIndex = 0
	var bestDistance = abs(keys[0] - value)
	for (keyIndex in 1 until keys.size) {
		val distance = abs(keys[keyIndex] - value)
		if (distance < bestDistance) {
			bestDistance = distance
			bestIndex = keyIndex
		}
	}
	return bestIndex
}

/**
 * A copy of this coordinate array with the element at [removeIndex] dropped (length shrinks by one).
 *
 * @param Int removeIndex The element index to drop.
 * @return IntArray The shortened coordinate.
 */
private fun IntArray.withElementRemoved(removeIndex: Int): IntArray {
	val result = IntArray(size - 1)
	var writeIndex = 0
	for (readIndex in indices) {
		if (readIndex != removeIndex) {
			result[writeIndex] = this[readIndex]
			writeIndex++
		}
	}
	return result
}
