package org.umamo.runtime.eval

import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.ParameterId

/*
 * Pure pose queries over blend-shape bindings, shared by the evaluator in :render and by the edits in :edit
 * that must know what a binding does at a pose without evaluating a rig.  The arithmetic is the evaluator's
 * own, so an edit that reasons about a limit's value reaches exactly the number the renderer multiplies by.
 */

/**
 * The weight cap this limit curve gives at [value] of its constraint parameter: end-clamped and
 * piecewise-linear between its points.
 *
 * @param Float value The constraint parameter's value.
 * @return Float The cap, or 1 when the curve has no points (no cap).
 */
fun BlendWeightLimit.weightAt(value: Float): Float {
	if (points.isEmpty()) {
		return 1f
	}
	return when {
		value <= points.first().value -> points.first().weight
		value >= points.last().value -> points.last().weight
		else -> {
			var lowerPointIndex = 0
			while (lowerPointIndex + 1 < points.size && points[lowerPointIndex + 1].value <= value) {
				lowerPointIndex++
			}
			val lower = points[lowerPointIndex]
			val upper = points[lowerPointIndex + 1]
			val span = upper.value - lower.value
			if (span > 0f) {
				val fraction = (value - lower.value) / span
				lower.weight + fraction * (upper.weight - lower.weight)
			} else {
				lower.weight
			}
		}
	}
}

/**
 * A binding's limit multiplier at a pose: the MINIMUM over these limit curves, each read at its own
 * constraint parameter's value, and 1 when none caps it (no limits, or only empty curves).
 *
 * @param Function paramValue The value per parameter id defining the pose.
 * @return Float The multiplier, at most 1.
 */
fun List<BlendWeightLimit>.multiplierAt(paramValue: (ParameterId) -> Float): Float {
	var multiplier = 1f
	for (limit in this) {
		if (limit.points.isEmpty()) {
			continue
		}
		val capped = limit.weightAt(paramValue(limit.parameterId))
		if (capped < multiplier) {
			multiplier = capped
		}
	}
	return multiplier
}

/**
 * Whether this binding adds anything when its driving parameter sits at [value], limits aside: some
 * non-neutral key takes a nonzero share of the bracket around [value].  Out-of-range values saturate to
 * the end key, as the evaluator's bracket does.
 *
 * @param Float value The driving parameter's value.
 * @return Boolean True when a non-neutral key contributes at [value].
 */
fun BlendShapeBinding<*>.contributesAt(value: Float): Boolean {
	if (keys.size < 2) {
		return false
	}
	var lowerKeyIndex = 0
	while (lowerKeyIndex + 1 < keys.size && keys[lowerKeyIndex + 1] <= value) {
		lowerKeyIndex++
	}
	val span = if (lowerKeyIndex + 1 < keys.size) keys[lowerKeyIndex + 1] - keys[lowerKeyIndex] else 0f
	val fraction =
		when {
			value <= keys[0] -> 0f
			lowerKeyIndex + 1 >= keys.size -> 0f
			span > 0f -> (value - keys[lowerKeyIndex]) / span
			else -> 0f
		}
	val lowerContributes = lowerKeyIndex != neutralIndex && 1f - fraction > 0f && forms.getOrNull(lowerKeyIndex) != null
	val upperContributes = fraction > 0f && lowerKeyIndex + 1 != neutralIndex && forms.getOrNull(lowerKeyIndex + 1) != null
	return lowerContributes || upperContributes
}