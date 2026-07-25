package org.umamo.runtime.keyform

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.EPS_SPAN
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs

/*
 * The keyform-grid authoring algebra: bind an entity to a parameter, insert / remove a key, write a cell,
 * capture a form at the current pose, and refine a grid onto a wider key set for export.
 *
 * Two invariants run through all of it, both forced by how the evaluator brackets a pose (see
 * runtime.eval.bindBracket):
 *
 *   1. An axis must SPAN its parameter's range.  A pose outside an axis's keys makes bindBracket return
 *      null, which hides the entity outright for a geometry grid and drops a channel to its static value.
 *      So seeding always reaches min and max, and "arbitrary key positions" means arbitrary INTERIOR keys.
 *   2. An axis must keep at least TWO keys.  A single-key axis resolves only within EPS_KEY of that key
 *      and is null everywhere else, so removing down to one key is a vanishing trap - removal collapses
 *      the axis instead.
 *
 * A new axis is always APPENDED, never spliced in parameter order: the cell linear index folds axis 0 as
 * the fastest-varying, so appending leaves every existing cell's index (and therefore the GPU delta
 * texture's column layout) untouched, while an insert in the middle renumbers all of them.
 *
 * Sparse grids - which Cmo3Import can produce when a cell's form guid does not resolve - are refused
 * rather than repaired: every op below returns its receiver unchanged instead of inventing cells.
 *
 * キーフォーム格子の編集代数。軸は必ずパラメータ範囲を張り、キーは2本以上を保ち、新軸は末尾に追加する。
 */

/** How [withAxisSeeded] lays out the keys of a newly bound axis. */
enum class AxisSeedPolicy {
	/**
	 * Keys at the parameter's minimum, default, and maximum (deduplicated) - what Cubism seeds, and the
	 * only layout that both spans the range and puts a key at the neutral pose.
	 */
	MinDefaultMax,

	/**
	 * Keys at the parameter's minimum, default, maximum, AND the current scrub value, so the very next
	 * capture writes a cell without reshaping the grid again.
	 */
	MinDefaultMaxAndScrub,
}

/** What [withKeyInserted] does with a key that falls outside the axis's existing key span. */
enum class OutOfSpanKeyPolicy {
	/**
	 * Widen the axis, replicating the nearest end slice into the new one.  Constant extrapolation, never
	 * linear: extending a trend past its last authored key would invent motion the rigger never made.
	 */
	Extend,

	/** Leave the grid alone. */
	Reject,
}

/**
 * This grid with a new axis for [parameter] appended, every existing cell replicated across its keys.
 *
 * Binding an entity to a parameter for the first time.  A null receiver seeds a brand new grid holding
 * [currentForm] at every key; an existing grid is replicated once per new key, so the entity looks exactly
 * as it did before at every pose - binding alone never changes the render, only the capture that follows
 * does.
 *
 * Returns the receiver unchanged when the grid already keys [parameter], or when the parameter's range
 * cannot support two distinct keys (a min == max parameter is not animatable, and a one-key axis would
 * hide the entity).
 *
 * @param Parameter parameter The parameter to bind to; its min / default / max define the seeded keys.
 * @param TForm currentForm The form to hold at every seeded key (the entity's present look).
 * @param Float scrubValue The current pose value on this parameter, seeded as a key under
 *   [AxisSeedPolicy.MinDefaultMaxAndScrub].
 * @param AxisSeedPolicy policy Which keys to seed.
 * @return KeyformGrid The grid with the axis appended, or the receiver when nothing could be seeded.
 */
fun <TForm> KeyformGrid<TForm>?.withAxisSeeded(
	parameter: Parameter,
	currentForm: TForm,
	scrubValue: Float = parameter.default,
	policy: AxisSeedPolicy = AxisSeedPolicy.MinDefaultMax,
): KeyformGrid<TForm>? {
	if (this != null && axisIndexOf(parameter.id) >= 0) {
		return this
	}
	if (this != null && !isDense) {
		return this
	}
	val seedCandidates =
		when (policy) {
			AxisSeedPolicy.MinDefaultMax -> floatArrayOf(parameter.min, parameter.default, parameter.max)
			AxisSeedPolicy.MinDefaultMaxAndScrub -> floatArrayOf(parameter.min, parameter.default, parameter.max, scrubValue)
		}
	val seedKeys = distinctSortedKeys(seedCandidates)
	if (seedKeys.size < 2) {
		return this
	}
	val newAxis = KeyformAxis(parameter.id, seedKeys)
	if (this == null) {
		val cells = seedKeys.indices.map { keyIndex -> KeyformCell(intArrayOf(keyIndex), currentForm) }
		return KeyformGrid(listOf(newAxis), cells)
	}
	// Appended, so every existing coordinate keeps its meaning and simply gains a trailing component.
	val replicated =
		buildList {
			for (keyIndex in seedKeys.indices) {
				for (cell in cells) {
					add(KeyformCell(cell.coordinate + keyIndex, cell.form))
				}
			}
		}
	return KeyformGrid(axes + newAxis, replicated)
}

/**
 * This grid with a new key at [keyValue] on [parameterId]'s axis, filling the whole new slice.
 *
 * Inserting a key adds an entire hyperplane of cells, not one cell: on a grid keyed over two parameters,
 * a new key on the first axis needs one new cell for every key of the second.  Only the interior cells can
 * be derived, by blending the two neighbouring slices with [interpolator] - which is exactly the operation
 * the export refinement performs, so the two share this code rather than growing a second copy.
 *
 * Returns the receiver unchanged when the axis is absent, the grid is sparse, [keyValue] is within EPS_KEY
 * of an existing key, the insertion would leave a span below EPS_SPAN (a span that narrow evaluates as a
 * step rather than a blend), or the key falls outside the span under [OutOfSpanKeyPolicy.Reject].
 *
 * @param ParameterId parameterId The axis to insert into.
 * @param Float keyValue The parameter value of the new key.
 * @param FormInterpolator interpolator Blends the neighbouring slices into the new one.
 * @param OutOfSpanKeyPolicy outOfSpan How to treat a key beyond the axis's current ends.
 * @return KeyformGrid The grid with the key inserted, or the receiver when the insert was refused.
 */
fun <TForm> KeyformGrid<TForm>.withKeyInserted(
	parameterId: ParameterId,
	keyValue: Float,
	interpolator: FormInterpolator<TForm>,
	outOfSpan: OutOfSpanKeyPolicy = OutOfSpanKeyPolicy.Extend,
): KeyformGrid<TForm> {
	val axisIndex = axisIndexOf(parameterId)
	if (axisIndex < 0 || !isDense) {
		return this
	}
	val oldKeys = axes[axisIndex].keys
	if (oldKeys.isEmpty()) {
		return this
	}
	if (oldKeys.any { existing -> abs(existing - keyValue) < EPS_KEY }) {
		return this
	}
	// The insert position in the NEW key array: the count of existing keys below the new value.
	val insertPosition = oldKeys.count { existing -> existing < keyValue }
	val isBelowSpan = insertPosition == 0
	val isAboveSpan = insertPosition == oldKeys.size
	if ((isBelowSpan || isAboveSpan) && outOfSpan == OutOfSpanKeyPolicy.Reject) {
		return this
	}
	if (!spansStayResolvable(oldKeys, keyValue, insertPosition)) {
		return this
	}
	val newKeys = FloatArray(oldKeys.size + 1)
	oldKeys.copyInto(newKeys, destinationOffset = 0, startIndex = 0, endIndex = insertPosition)
	newKeys[insertPosition] = keyValue
	oldKeys.copyInto(newKeys, destinationOffset = insertPosition + 1, startIndex = insertPosition, endIndex = oldKeys.size)

	val formByOldIndex = cells.associate { cell -> linearIndexOf(cell.coordinate) to cell.form }
	val newAxes = axes.toMutableList()
	newAxes[axisIndex] = KeyformAxis(parameterId, newKeys)
	val newGridShape = KeyformGrid(newAxes.toList(), emptyList<KeyformCell<TForm>>())

	// The source slices for the new one: the two it sits between, or the single nearest end when extending.
	val lowerSourceKeyIndex = if (isBelowSpan) 0 else insertPosition - 1
	val upperSourceKeyIndex = if (isAboveSpan) oldKeys.size - 1 else insertPosition
	val blendFraction =
		if (isBelowSpan || isAboveSpan) {
			// Constant extrapolation - both source indices are the same slice, so the fraction is inert.
			0f
		} else {
			val span = oldKeys[upperSourceKeyIndex] - oldKeys[lowerSourceKeyIndex]
			if (span >= EPS_SPAN) (keyValue - oldKeys[lowerSourceKeyIndex]) / span else 0f
		}

	val newCells = ArrayList<KeyformCell<TForm>>(cells.size + cells.size / maxOf(1, oldKeys.size) + 1)
	// Existing cells keep their forms; those at or past the insert shift one key index up.
	for (cell in cells) {
		val shifted = cell.coordinate.copyOf()
		if (shifted[axisIndex] >= insertPosition) {
			shifted[axisIndex] = shifted[axisIndex] + 1
		}
		newCells.add(KeyformCell(shifted, cell.form))
	}
	// One new cell per combination of the OTHER axes, enumerated from any single existing slice.
	for (cell in cells) {
		if (cell.coordinate[axisIndex] != 0) {
			continue
		}
		val lowerCoordinate = cell.coordinate.copyOf().also { it[axisIndex] = lowerSourceKeyIndex }
		val upperCoordinate = cell.coordinate.copyOf().also { it[axisIndex] = upperSourceKeyIndex }
		val lowerForm = formByOldIndex[linearIndexOf(lowerCoordinate)] ?: continue
		val upperForm = formByOldIndex[linearIndexOf(upperCoordinate)] ?: continue
		val insertedForm =
			if (lowerSourceKeyIndex == upperSourceKeyIndex) {
				lowerForm
			} else {
				interpolator.interpolate(lowerForm, upperForm, blendFraction)
			}
		val insertedCoordinate = cell.coordinate.copyOf().also { it[axisIndex] = insertPosition }
		newCells.add(KeyformCell(insertedCoordinate, insertedForm))
	}
	return KeyformGrid(newGridShape.axes, newCells)
}

/**
 * This grid with key [keyIndex] removed from [parameterId]'s axis, or the axis collapsed entirely when
 * too few keys would remain.
 *
 * Dropping below [collapseBelowKeyCount] keys collapses the axis (via [withAxisCollapsed]) rather than
 * leaving a stub: a single-key axis resolves only within EPS_KEY of its key and hides the entity
 * everywhere else, so "remove the second-to-last key" would otherwise make the entity vanish.  Collapsing
 * the last remaining axis returns null - the entity becomes unkeyed.
 *
 * @param ParameterId parameterId The axis to remove a key from.
 * @param Int keyIndex The key's index on that axis.
 * @param Int collapseBelowKeyCount The key count below which the axis collapses instead; two by default,
 *   which the exporter can lower when it deliberately wants a degenerate axis.
 * @return KeyformGrid The reduced grid, the receiver when the removal was refused, or null when the entity
 *   is left with no axes at all.
 */
fun <TForm> KeyformGrid<TForm>.withKeyRemoved(
	parameterId: ParameterId,
	keyIndex: Int,
	collapseBelowKeyCount: Int = 2,
): KeyformGrid<TForm>? {
	val axisIndex = axisIndexOf(parameterId)
	if (axisIndex < 0 || !isDense) {
		return this
	}
	val oldKeys = axes[axisIndex].keys
	if (keyIndex < 0 || keyIndex >= oldKeys.size) {
		return this
	}
	if (oldKeys.size - 1 < collapseBelowKeyCount) {
		// Keep whichever key survives the removal, so the collapsed slice is the one the rigger kept.
		val survivingKeyIndex = if (keyIndex == 0) minOf(1, oldKeys.size - 1) else keyIndex - 1
		return withAxisCollapsed(parameterId, oldKeys[survivingKeyIndex])
	}
	val newKeys = FloatArray(oldKeys.size - 1)
	oldKeys.copyInto(newKeys, destinationOffset = 0, startIndex = 0, endIndex = keyIndex)
	oldKeys.copyInto(newKeys, destinationOffset = keyIndex, startIndex = keyIndex + 1, endIndex = oldKeys.size)
	val newAxes = axes.toMutableList()
	newAxes[axisIndex] = KeyformAxis(parameterId, newKeys)
	val newCells =
		cells.mapNotNull { cell ->
			val keyOnAxis = cell.coordinate[axisIndex]
			when {
				keyOnAxis == keyIndex -> null
				keyOnAxis > keyIndex -> KeyformCell(cell.coordinate.copyOf().also { it[axisIndex] = keyOnAxis - 1 }, cell.form)
				else -> cell
			}
		}
	return KeyformGrid(newAxes.toList(), newCells)
}

/**
 * This grid with the cell at [coordinate] holding [form], appending the cell when it is absent.
 *
 * The write half of a capture: the shape work is already done by the time this runs, so it never changes
 * the axes.  A coordinate whose length does not match the axis count is refused.
 *
 * @param IntArray coordinate The target cell's key index per axis.
 * @param TForm form The form to store there.
 * @return KeyformGrid The grid with the cell written, or the receiver when the coordinate does not fit.
 */
fun <TForm> KeyformGrid<TForm>.withCellWritten(coordinate: IntArray, form: TForm): KeyformGrid<TForm> {
	if (coordinate.size != axes.size) {
		return this
	}
	val targetIndex = linearIndexOf(coordinate)
	var replaced = false
	val newCells =
		cells.map { cell ->
			if (!replaced && linearIndexOf(cell.coordinate) == targetIndex) {
				replaced = true
				KeyformCell(coordinate.copyOf(), form)
			} else {
				cell
			}
		}
	return if (replaced) {
		KeyformGrid(axes, newCells)
	} else {
		KeyformGrid(axes, newCells + KeyformCell(coordinate.copyOf(), form))
	}
}

/**
 * This grid with [form] captured at [pose] - the keyform-insertion operation behind Blender-style `I`.
 *
 * Inserts a key on every axis whose pose value does not already land on one (each insert filling its whole
 * slice by interpolation, so the entity's look is unchanged everywhere else), then writes [form] into the
 * single cell the pose now sits exactly on.  Because the capture always lands on a key, the write is a
 * plain assignment - there is never a blend to invert.
 *
 * Returns the receiver when the grid is sparse, or when an axis's pose value cannot be turned into a key
 * (out of span under a rejecting policy, or too close to an existing key to be distinguishable).
 *
 * @param Function pose The current value per parameter id.
 * @param TForm form The form to capture.
 * @param FormInterpolator interpolator Fills each newly inserted slice.
 * @return KeyformGrid The grid with the capture written.
 */
fun <TForm> KeyformGrid<TForm>.withFormCaptured(
	pose: (ParameterId) -> Float,
	form: TForm,
	interpolator: FormInterpolator<TForm>,
): KeyformGrid<TForm> {
	if (!isDense || axes.isEmpty()) {
		return this
	}
	var working = this
	for (axis in axes) {
		val poseValue = pose(axis.parameterId)
		val currentKeys = working.axes[working.axisIndexOf(axis.parameterId)].keys
		if (currentKeys.any { existing -> abs(existing - poseValue) < EPS_KEY }) {
			continue
		}
		working = working.withKeyInserted(axis.parameterId, poseValue, interpolator)
	}
	val coordinate = IntArray(working.axes.size)
	for (axisIndex in working.axes.indices) {
		val axis = working.axes[axisIndex]
		val poseValue = pose(axis.parameterId)
		val keyIndex = axis.keys.indexOfFirst { existing -> abs(existing - poseValue) < EPS_KEY }
		if (keyIndex < 0) {
			// An axis refused its key (a degenerate span, say); writing a neighbouring cell would silently
			// retarget the capture, so leave the grid untouched instead.
			return this
		}
		coordinate[axisIndex] = keyIndex
	}
	return working.withCellWritten(coordinate, form)
}

/**
 * This grid refined onto a wider key set, for lowering a rig to a format that shares key positions.
 *
 * SPAN-CLAMPED on purpose.  For an axis this grid already has, only union keys strictly inside its own
 * first..last span are inserted; the span itself is never widened.  Widening it would resurrect a drawable
 * that its author deliberately keyed to vanish outside a narrow range - the toggle-part pattern - which is
 * a rig-altering change, not a rounding one.  For a parameter this grid does NOT key, a new axis is
 * appended spanning the union plus the parameter's own min and max, with the whole grid replicated across
 * it: the channel is constant along that axis, so the widening carries no motion and cannot mislead.
 *
 * Refining is the exact inverse of compaction: both go through [interpolator], so a key dropped because it
 * was bit-equal to its interpolated neighbours is restored bit-equal here.
 *
 * @param Map unionKeysByParameter The key positions to refine onto, per parameter.
 * @param Map parameterRanges Each parameter's min..max, used only when appending an axis this grid lacks.
 * @param FormInterpolator interpolator Blends each inserted slice.
 * @return KeyformGrid The refined grid, or the receiver when it is sparse.
 */
fun <TForm> KeyformGrid<TForm>.refinedToUnion(
	unionKeysByParameter: Map<ParameterId, FloatArray>,
	parameterRanges: Map<ParameterId, ClosedFloatingPointRange<Float>>,
	interpolator: FormInterpolator<TForm>,
): KeyformGrid<TForm> {
	if (!isDense) {
		return this
	}
	var working = this
	for ((parameterId, unionKeys) in unionKeysByParameter) {
		val axisIndex = working.axisIndexOf(parameterId)
		if (axisIndex >= 0) {
			val ownKeys = working.axes[axisIndex].keys
			if (ownKeys.isEmpty()) {
				continue
			}
			val spanStart = ownKeys.first()
			val spanEnd = ownKeys.last()
			for (unionKey in unionKeys.sortedArray()) {
				if (unionKey > spanStart && unionKey < spanEnd) {
					working = working.withKeyInserted(parameterId, unionKey, interpolator, OutOfSpanKeyPolicy.Reject)
				}
			}
		} else {
			val range = parameterRanges[parameterId] ?: continue
			val appendedKeys = distinctSortedKeys(unionKeys + range.start + range.endInclusive)
			if (appendedKeys.size < 2) {
				continue
			}
			val newAxis = KeyformAxis(parameterId, appendedKeys)
			val replicated =
				buildList {
					for (keyIndex in appendedKeys.indices) {
						for (cell in working.cells) {
							add(KeyformCell(cell.coordinate + keyIndex, cell.form))
						}
					}
				}
			working = KeyformGrid(working.axes + newAxis, replicated)
		}
	}
	return working
}

/**
 * The given key candidates sorted ascending with near-duplicates (within EPS_KEY) merged.
 *
 * Merging on the evaluator's own snap tolerance rather than exact equality: two keys closer than EPS_KEY
 * are indistinguishable to bindBracket, so keeping both would build an axis with a span the evaluator
 * treats as a step.
 *
 * @param FloatArray candidates The raw key values, in any order.
 * @return FloatArray The sorted, de-duplicated keys.
 */
private fun distinctSortedKeys(candidates: FloatArray): FloatArray {
	val sorted = candidates.sortedArray()
	val kept = ArrayList<Float>(sorted.size)
	for (candidate in sorted) {
		if (kept.isEmpty() || abs(kept.last() - candidate) >= EPS_KEY) {
			kept.add(candidate)
		}
	}
	return kept.toFloatArray()
}

/**
 * Whether inserting [keyValue] at [insertPosition] leaves every span on the axis wide enough to evaluate.
 *
 * A span below EPS_SPAN yields blend fraction 0 in bindBracket, so the lower key holds for the whole span
 * and the upper key becomes unreachable - a grid the evaluator cannot resolve as authored.
 *
 * @param FloatArray existingKeys The axis's current keys, ascending.
 * @param Float keyValue The candidate key value.
 * @param Int insertPosition The index the key would take in the new array.
 * @return Boolean True when both neighbouring spans stay at or above EPS_SPAN.
 */
private fun spansStayResolvable(existingKeys: FloatArray, keyValue: Float, insertPosition: Int): Boolean {
	if (insertPosition > 0 && keyValue - existingKeys[insertPosition - 1] < EPS_SPAN) {
		return false
	}
	if (insertPosition < existingKeys.size && existingKeys[insertPosition] - keyValue < EPS_SPAN) {
		return false
	}
	return true
}
