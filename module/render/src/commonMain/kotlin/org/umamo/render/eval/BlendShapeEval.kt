package org.umamo.render.eval

import org.umamo.runtime.eval.blendScalarsFromCorners
import org.umamo.runtime.eval.gridCorners
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RotationForm

/*
 * Blend-shape ("MorphTarget") evaluation: the additive pass applied on top of the multilinear
 * keyform-grid blend.
 *
 *  - A binding's weight is a LINEAR interpolation between the two keys bracketing the driving
 *    parameter's value; the neutral key (at the parameter default) contributes zero.
 *  - Out-of-range driving values SATURATE - the end key's full delta is held (Umamo C++ Runtime
 *    verdict, Model A's ParamEyeSize matrix; a competing hide-on-out-of-range hypothesis was
 *    overruled).
 *  - The bracket has NO epsilon, unlike the grid path's EPS_KEY snapping - mirrored from the
 *    reference runtime for ULP parity.
 *  - The weight is multiplied by the MINIMUM over the binding's limit curves (end-clamped
 *    piecewise-linear over OTHER parameters); Model B-measured: a half-ramp scales deltas by
 *    exactly 0.5000.
 *  - Deltas are relative to the object's grid form INTERPOLATED AT THE DEFAULT POSE, not the rest
 *    shape (refuted 16/16 on Model A). Forms with a null entry (an inserted neutral) never reach
 *    the contribution list.
 */

/** One active key of a binding at the current pose: the key's form index and its net weight. */
internal class ActiveBlendKey(val keyIndex: Int, val weight: Float)

/**
 * Resolves which of [binding]'s keys contribute at [value], with the net weight of each
 * (bracket interpolation x [limitMultiplier]). At most two entries; EMPTY at the neutral value,
 * so the default pose stays arithmetic-free. Out-of-range values saturate to the end key.
 *
 * @param BlendShapeBinding binding        The binding to resolve.
 * @param Float             value          The driving parameter's current value.
 * @param Float             limitMultiplier The binding's min-combined limit multiplier (0..1).
 * @return List<ActiveBlendKey> The active keys with nonzero weight.
 */
internal fun activeBlendKeys(
	binding: BlendShapeBinding<*>,
	value: Float,
	limitMultiplier: Float,
): List<ActiveBlendKey> {
	if (limitMultiplier <= 0f) {
		return emptyList()
	}
	val keys = binding.keys
	if (keys.size < 2) {
		// A lone key is necessarily the neutral (ingest always inserts it): nothing to add.
		return emptyList()
	}
	// Saturating bracket, no epsilon: grid = last key <= value, clamped to the ends.
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
	val active = ArrayList<ActiveBlendKey>(2)
	val lowerWeight = (1f - fraction) * limitMultiplier
	if (lowerKeyIndex != binding.neutralIndex && lowerWeight > 0f) {
		active.add(ActiveBlendKey(lowerKeyIndex, lowerWeight))
	}
	val upperWeight = fraction * limitMultiplier
	if (fraction > 0f && lowerKeyIndex + 1 != binding.neutralIndex && upperWeight > 0f) {
		active.add(ActiveBlendKey(lowerKeyIndex + 1, upperWeight))
	}
	return active
}

/**
 * The binding's blend-weight limit multiplier at the current pose: the MINIMUM over its limit
 * curves, each an end-clamped piecewise-linear (value, weight) map over another parameter.
 * 1 when the binding has no limits.
 *
 * @param List     limits     The binding's limit curves.
 * @param Function paramValue Current value per parameter id.
 * @return Float The multiplier in [0..1] (whatever the curves yield).
 */
internal fun limitMultiplier(limits: List<BlendWeightLimit>, paramValue: (ParameterId) -> Float): Float {
	var multiplier = 1f
	for (limit in limits) {
		val points = limit.points
		if (points.isEmpty()) {
			continue
		}
		val value = paramValue(limit.parameterId)
		val capped =
			when {
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
		if (capped < multiplier) {
			multiplier = capped
		}
	}
	return multiplier
}

/** One active mesh blend contribution: the form, its net weight, and its (binding, key) identity. */
internal class MeshBlendContribution(
	val bindingIndex: Int,
	val keyIndex: Int,
	val form: MeshForm,
	val weight: Float,
)

/**
 * A mesh drawable's resolved blend state at one pose: the active contributions plus the shared
 * delta reference (the grid form at the DEFAULT pose, as deltas vs the rest mesh - static per
 * drawable, null when the drawable is ungridded so the reference is zero).
 */
internal class MeshBlendState(
	val contributions: List<MeshBlendContribution>,
	val referenceDeltas: FloatArray?,
	val referenceDrawOrder: Float,
	val referenceOpacity: Float,
)

/**
 * Resolves [drawable]'s blend-shape state at the current pose, or null when it has no bindings
 * (the caller then takes the exact pre-blend-shape code path).
 *
 * @param Drawable drawable     The drawable.
 * @param Function paramValue   Current value per parameter id.
 * @param Function defaultValue Default value per parameter id (the neutral pose).
 * @return MeshBlendState? The resolved state, or null when binding-free.
 */
internal fun meshBlendState(
	drawable: Drawable,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
): MeshBlendState? {
	if (drawable.blendShapes.isEmpty()) {
		return null
	}
	val contributions = ArrayList<MeshBlendContribution>()
	for (bindingIndex in drawable.blendShapes.indices) {
		val binding = drawable.blendShapes[bindingIndex]
		val active =
			activeBlendKeys(binding, paramValue(binding.parameterId), limitMultiplier(binding.limits, paramValue))
		for (key in active) {
			val form = binding.forms[key.keyIndex] ?: continue
			contributions.add(MeshBlendContribution(bindingIndex, key.keyIndex, form, key.weight))
		}
	}
	// The reference is the grid form at the DEFAULT pose (E5): deltas vs the rest mesh plus the
	// grid's scalar channels there. An ungridded drawable's reference is the rest state itself.
	val grid = drawable.keyforms
	val defaultCorners = grid?.let { gridCorners(it, defaultValue) }
	val referenceScalars =
		if (grid != null && defaultCorners != null) {
			blendScalarsFromCorners(grid, defaultCorners)
		} else {
			null
		}
	return MeshBlendState(
		contributions = contributions,
		referenceDeltas = meshGridDefaultDeltas(drawable, defaultValue),
		referenceDrawOrder = referenceScalars?.drawOrder ?: CUBISM_DEFAULT_DRAW_ORDER,
		referenceOpacity = referenceScalars?.opacity ?: 1f,
	)
}

/**
 * The summed weighted control-point delta of [warp]'s blend bindings at the current pose, or null
 * when nothing contributes. Deltas are relative to the lattice's grid form at the DEFAULT pose
 * (E5); WarpForm control points are absolute, so the reference is subtracted here.
 *
 * @param Deformer.Warp warp        The warp deformer.
 * @param Function      paramValue   Current value per parameter id.
 * @param Function      defaultValue Default value per parameter id.
 * @return FloatArray? Interleaved control-point deltas to ADD to the grid-blended lattice, or null.
 */
internal fun warpBlendDeltas(
	warp: Deformer.Warp,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
): FloatArray? {
	if (warp.blendShapes.isEmpty()) {
		return null
	}
	var deltas: FloatArray? = null
	var reference: FloatArray? = null
	for (binding in warp.blendShapes) {
		val active =
			activeBlendKeys(binding, paramValue(binding.parameterId), limitMultiplier(binding.limits, paramValue))
		for (key in active) {
			val form = binding.forms[key.keyIndex] ?: continue
			if (reference == null) {
				reference = warpControlPointsAt(warp.keyforms, defaultValue) ?: FloatArray(form.controlPoints.size)
			}
			val target = deltas ?: FloatArray(form.controlPoints.size).also { deltas = it }
			for (componentIndex in target.indices) {
				target[componentIndex] += key.weight * (form.controlPoints[componentIndex] - reference[componentIndex])
			}
		}
	}
	return deltas
}

/** A rotation deformer's summed weighted blend deltas (the reflect flags are not blendable). */
internal class RotationBlendDeltas(
	val originX: Float,
	val originY: Float,
	val angle: Float,
	val scale: Float,
)

/**
 * The summed weighted affine deltas of [rotation]'s blend bindings at the current pose, or null
 * when nothing contributes. Deltas are relative to the rotation's grid form at the DEFAULT pose
 * (E5); RotationForm fields are absolute, so the reference is subtracted here.
 *
 * @param Deformer.Rotation rotation     The rotation deformer.
 * @param Function          paramValue   Current value per parameter id.
 * @param Function          defaultValue Default value per parameter id.
 * @return RotationBlendDeltas? The deltas to ADD to the grid-blended transform, or null.
 */
internal fun rotationBlendDeltas(
	rotation: Deformer.Rotation,
	paramValue: (ParameterId) -> Float,
	defaultValue: (ParameterId) -> Float,
): RotationBlendDeltas? {
	if (rotation.blendShapes.isEmpty()) {
		return null
	}
	var originX = 0f
	var originY = 0f
	var angle = 0f
	var scale = 0f
	var contributed = false
	var reference: RotationForm? = null
	for (binding in rotation.blendShapes) {
		val active =
			activeBlendKeys(binding, paramValue(binding.parameterId), limitMultiplier(binding.limits, paramValue))
		for (key in active) {
			val form = binding.forms[key.keyIndex] ?: continue
			if (reference == null) {
				reference = rotationFormAt(rotation.keyforms, defaultValue)
					?: RotationForm(0f, 0f, 0f, 1f, flipX = false, flipY = false)
			}
			originX += key.weight * (form.originX - reference.originX)
			originY += key.weight * (form.originY - reference.originY)
			angle += key.weight * (form.angle - reference.angle)
			scale += key.weight * (form.scale - reference.scale)
			contributed = true
		}
	}
	return if (contributed) RotationBlendDeltas(originX, originY, angle, scale) else null
}
