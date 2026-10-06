package org.umamo.edit

import org.umamo.runtime.eval.EPS_KEY
import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.contributesAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.multiplierAt
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import kotlin.math.abs

/*
 * What deleting a parameter does to the blend shapes that name it.  A binding names its driving parameter and
 * each weight limit names its constraint parameter; a delete that left either behind would write an id the
 * model no longer has into every file saved after it.  The scrub keeps the rest pose exact wherever the
 * model can say so: the evaluator reads a missing parameter as 0, the neutral key, and the grids collapse to
 * the slice at the parameter's default, so the scrub reads each binding at that same default.
 */

/**
 * How a parameter delete treats one blend-shape binding.
 */
internal sealed interface BlendBindingScrub {
	/** The binding names the parameter nowhere and stays as it is. */
	data object Unchanged : BlendBindingScrub

	/**
	 * The binding goes: the parameter drove it, or a limit over the parameter held it at zero there.
	 *
	 * @property Boolean restPreserved Whether the rest pose is the same without it.
	 */
	data class Drop(val restPreserved: Boolean) : BlendBindingScrub

	/**
	 * The limits over the parameter go and the binding keeps the rest.
	 *
	 * @property Boolean restPreserved Whether the rest pose is the same without them.
	 */
	data class DropLimits(val restPreserved: Boolean) : BlendBindingScrub

	/**
	 * The limits over the parameter go, and the constant they held at its default is baked into the forms:
	 * each form moves to `reference + factor * (form - reference)`, which the evaluator's `weight * (form -
	 * reference)` turns into exactly the capped contribution.  Always exact.
	 *
	 * @property Float factor The cap the limits held at the parameter's default, strictly between 0 and 1.
	 */
	data class ScaleForms(val factor: Float) : BlendBindingScrub
}

/**
 * How deleting [deleted] treats [binding].
 *
 * A binding the parameter drives goes, and costs the rest pose only when it contributed there.  A limit
 * over the parameter caps the binding at the constant `c` its curve holds at the parameter's default: a cap
 * of 1 or more changes nothing and the limit goes; a cap of 0 or less silences the binding wherever the
 * parameter sat at its default, so the binding goes; a cap between them on a binding with no other limit is
 * baked into the forms; and with other limits beside it, whose minimum no single constant can stand in for,
 * the limit goes and the rest pose is kept only if those others were already the tighter cap there.
 *
 * @param BlendShapeBinding binding   The binding.
 * @param Parameter         deleted   The parameter being deleted.
 * @param Function          defaultOf The default value per parameter id, the deleted one included.
 * @return BlendBindingScrub The treatment.
 */
internal fun blendBindingScrubOf(
	binding: BlendShapeBinding<*>,
	deleted: Parameter,
	defaultOf: (ParameterId) -> Float,
): BlendBindingScrub {
	if (binding.parameterId == deleted.id) {
		val contributedAtRest = binding.contributesAt(deleted.default) && binding.limits.multiplierAt(defaultOf) > 0f
		return BlendBindingScrub.Drop(restPreserved = !contributedAtRest)
	}
	if (binding.limits.none { limit -> limit.parameterId == deleted.id }) {
		return BlendBindingScrub.Unchanged
	}
	val (overDeleted, others) = binding.limits.partition { limit -> limit.parameterId == deleted.id }
	val deletedCap = overDeleted.multiplierAt(defaultOf)
	return when {
		deletedCap >= 1f -> BlendBindingScrub.DropLimits(restPreserved = true)
		deletedCap <= 0f -> BlendBindingScrub.Drop(restPreserved = true)
		others.isEmpty() -> BlendBindingScrub.ScaleForms(deletedCap)
		else -> {
			val othersCap = others.multiplierAt(defaultOf)
			val contributesAtRest = binding.contributesAt(defaultOf(binding.parameterId))
			BlendBindingScrub.DropLimits(restPreserved = !contributesAtRest || othersCap <= deletedCap)
		}
	}
}

/**
 * These bindings with [deleted] scrubbed out of them ([blendBindingScrubOf]), or this same list when none
 * names it.
 *
 * @param Parameter deleted   The parameter being deleted.
 * @param Function  defaultOf The default value per parameter id, the deleted one included.
 * @param Function  scalerOf  Builds the owner's form scaler, asked for at most once and only when some binding
 *   bakes a cap into its forms, since it samples the owner's grids at the default pose.
 * @return List The scrubbed bindings.
 */
internal fun <TForm : Any> List<BlendShapeBinding<TForm>>.scrubbedOf(
	deleted: Parameter,
	defaultOf: (ParameterId) -> Float,
	scalerOf: () -> (TForm, Float) -> TForm,
): List<BlendShapeBinding<TForm>> {
	if (none { binding -> binding.parameterId == deleted.id || binding.limits.any { limit -> limit.parameterId == deleted.id } }) {
		return this
	}
	val scaler by lazy(scalerOf)
	return mapNotNull { binding ->
		when (val scrub = blendBindingScrubOf(binding, deleted, defaultOf)) {
			BlendBindingScrub.Unchanged -> binding
			is BlendBindingScrub.Drop -> null
			is BlendBindingScrub.DropLimits -> binding.copy(limits = binding.limits.filterNot { limit -> limit.parameterId == deleted.id })
			is BlendBindingScrub.ScaleForms ->
				binding.copy(
					limits = emptyList(),
					forms = binding.forms.map { form -> form?.let { present -> scaler(present, scrub.factor) } },
				)
		}
	}
}

/**
 * [value] moved toward [reference] so its distance from it is [factor] of what it was.
 *
 * @param Float reference The reference value.
 * @param Float value     The value.
 * @param Float factor    The share of the distance kept.
 * @return Float The scaled value.
 */
private fun scaledAround(reference: Float, value: Float, factor: Float): Float = reference + factor * (value - reference)

/**
 * [value] moved toward [reference] per channel, as [scaledAround] moves a scalar.
 *
 * @param ColorRgb reference The reference color.
 * @param ColorRgb value     The color.
 * @param Float    factor    The share of the distance kept.
 * @return ColorRgb The scaled color.
 */
private fun scaledColorAround(reference: ColorRgb, value: ColorRgb, factor: Float): ColorRgb =
	ColorRgb(
		scaledAround(reference.red, value.red, factor),
		scaledAround(reference.green, value.green, factor),
		scaledAround(reference.blue, value.blue, factor),
	)

/**
 * The scaler for this drawable's mesh blend forms: each field around the drawable's own value at the default
 * pose, the reference the evaluator and the MOC3 export subtract (`meshBlendState`).
 *
 * @param Function defaultOf The default value per parameter id.
 * @return Function The scaler.
 */
internal fun Drawable.meshFormScaler(defaultOf: (ParameterId) -> Float): (MeshForm, Float) -> MeshForm {
	val referenceDeltas = meshGridDefaultDeltas(this, defaultOf)
	val referenceDrawOrder = channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawOrder, defaultOf)
	val referenceOpacity = channelGrids.scalarAt(FormChannel.OPACITY, opacity, defaultOf)
	val referenceMultiply = channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, multiplyColor, defaultOf)
	val referenceScreen = channelGrids.colorAt(FormChannel.SCREEN_COLOR, screenColor, defaultOf)
	return { form, factor ->
		MeshForm(
			positionDeltas = FloatArray(form.positionDeltas.size) { componentIndex -> scaledAround(referenceDeltas?.getOrNull(componentIndex) ?: 0f, form.positionDeltas[componentIndex], factor) },
			drawOrder = scaledAround(referenceDrawOrder, form.drawOrder, factor),
			opacity = scaledAround(referenceOpacity, form.opacity, factor),
			multiplyColor = scaledColorAround(referenceMultiply, form.multiplyColor, factor),
			screenColor = scaledColorAround(referenceScreen, form.screenColor, factor),
		)
	}
}

/**
 * The scaler for this warp's blend forms: the lattice and the channels around their values at the default
 * pose (`warpBlendDeltas`, `deformerBlendChannelDeltas`).
 *
 * @param Function defaultOf The default value per parameter id.
 * @return Function The scaler.
 */
internal fun Deformer.Warp.warpFormScaler(defaultOf: (ParameterId) -> Float): (WarpForm, Float) -> WarpForm {
	val referencePoints = warpControlPointsAt(geometryGrid, defaultOf)
	val referenceOpacity = channelGrids.scalarAt(FormChannel.OPACITY, opacity, defaultOf)
	val referenceMultiply = channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, multiplyColor, defaultOf)
	val referenceScreen = channelGrids.colorAt(FormChannel.SCREEN_COLOR, screenColor, defaultOf)
	return { form, factor ->
		WarpForm(
			controlPoints = FloatArray(form.controlPoints.size) { componentIndex -> scaledAround(referencePoints?.getOrNull(componentIndex) ?: 0f, form.controlPoints[componentIndex], factor) },
			opacity = scaledAround(referenceOpacity, form.opacity, factor),
			multiplyColor = scaledColorAround(referenceMultiply, form.multiplyColor, factor),
			screenColor = scaledColorAround(referenceScreen, form.screenColor, factor),
		)
	}
}

/**
 * The scaler for this rotation's blend forms: the pivot and the channels around their values at the default
 * pose (`rotationBlendDeltas`).  The flips are flags that never blend, so they stay as they are.
 *
 * @param Function defaultOf The default value per parameter id.
 * @return Function The scaler.
 */
internal fun Deformer.Rotation.rotationFormScaler(defaultOf: (ParameterId) -> Float): (RotationForm, Float) -> RotationForm {
	val referencePivot = rotationFormAt(geometryGrid, defaultOf) ?: RotationPivotForm(0f, 0f, 0f, 1f)
	val referenceOpacity = channelGrids.scalarAt(FormChannel.OPACITY, opacity, defaultOf)
	val referenceMultiply = channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, multiplyColor, defaultOf)
	val referenceScreen = channelGrids.colorAt(FormChannel.SCREEN_COLOR, screenColor, defaultOf)
	return { form, factor ->
		RotationForm(
			originX = scaledAround(referencePivot.originX, form.originX, factor),
			originY = scaledAround(referencePivot.originY, form.originY, factor),
			angle = scaledAround(referencePivot.angle, form.angle, factor),
			scale = scaledAround(referencePivot.scale, form.scale, factor),
			flipX = form.flipX,
			flipY = form.flipY,
			opacity = scaledAround(referenceOpacity, form.opacity, factor),
			multiplyColor = scaledColorAround(referenceMultiply, form.multiplyColor, factor),
			screenColor = scaledColorAround(referenceScreen, form.screenColor, factor),
		)
	}
}

/**
 * The scaler for this part's blend forms: each channel around the part's own value at the default pose
 * (`partBlendDrawOrderDelta` for the draw order, the one channel a part's blend drives).
 *
 * @param Function defaultOf The default value per parameter id.
 * @return Function The scaler.
 */
internal fun Part.partFormScaler(defaultOf: (ParameterId) -> Float): (PartForm, Float) -> PartForm {
	val referenceDrawOrder = channelGrids.scalarAt(FormChannel.DRAW_ORDER, drawOrder.toFloat(), defaultOf)
	val referenceOpacity = channelGrids.scalarAt(FormChannel.OPACITY, composite.opacity, defaultOf)
	val referenceMultiply = channelGrids.colorAt(FormChannel.MULTIPLY_COLOR, composite.multiplyColor, defaultOf)
	val referenceScreen = channelGrids.colorAt(FormChannel.SCREEN_COLOR, composite.screenColor, defaultOf)
	return { form, factor ->
		PartForm(
			drawOrder = scaledAround(referenceDrawOrder, form.drawOrder, factor),
			opacity = scaledAround(referenceOpacity, form.opacity, factor),
			multiplyColor = scaledColorAround(referenceMultiply, form.multiplyColor, factor),
			screenColor = scaledColorAround(referenceScreen, form.screenColor, factor),
		)
	}
}

/**
 * The default value per parameter id of this model, 0 for an id it lacks (as the evaluator reads one).
 *
 * @return Function The lookup.
 */
internal fun PuppetModel.defaultValueLookup(): (ParameterId) -> Float {
	val defaultById = parameters.associate { parameter -> parameter.id to parameter.default }
	return { parameterId -> defaultById[parameterId] ?: 0f }
}

/**
 * Whether collapsing [parameterId]'s axis out of [grid] at [keepValue] loses an interpolated slice: the
 * grid keys on the parameter and [keepValue] sits on none of its keys, so the collapse keeps the nearest
 * key's slice rather than the look at the default.
 *
 * @param KeyformGrid? grid        The grid, or null.
 * @param ParameterId  parameterId The parameter being deleted.
 * @param Float        keepValue   Its default.
 * @return Boolean True when the collapse changes the grid's value at the default pose.
 */
private fun collapseMovesRest(grid: KeyformGrid<*>?, parameterId: ParameterId, keepValue: Float): Boolean {
	val axisIndex = grid?.axisIndexOf(parameterId) ?: return false
	if (axisIndex < 0) {
		return false
	}
	return grid.axes[axisIndex].keys.none { key -> abs(key - keepValue) < EPS_KEY }
}

/**
 * Whether collapsing [parameterId] out of any of these tracks loses an interpolated slice ([collapseMovesRest]).
 *
 * @param ChannelGrids channels    The owner's tracks.
 * @param ParameterId  parameterId The parameter being deleted.
 * @param Float        keepValue   Its default.
 * @return Boolean True when some track's value at the default pose changes.
 */
private fun collapseMovesRest(channels: ChannelGrids, parameterId: ParameterId, keepValue: Float): Boolean =
	channels.gridsByChannel.values.any { grid -> collapseMovesRest(grid, parameterId, keepValue) }

/**
 * Whether scrubbing [deleted] out of these bindings changes the rest pose.
 *
 * @param List      bindings  The owner's bindings.
 * @param Parameter deleted   The parameter being deleted.
 * @param Function  defaultOf The default value per parameter id, the deleted one included.
 * @return Boolean True when some binding's scrub is not exact at rest.
 */
private fun blendScrubMovesRest(bindings: List<BlendShapeBinding<*>>, deleted: Parameter, defaultOf: (ParameterId) -> Float): Boolean =
	bindings.any { binding ->
		when (val scrub = blendBindingScrubOf(binding, deleted, defaultOf)) {
			BlendBindingScrub.Unchanged, is BlendBindingScrub.ScaleForms -> false
			is BlendBindingScrub.Drop -> !scrub.restPreserved
			is BlendBindingScrub.DropLimits -> !scrub.restPreserved
		}
	}

/**
 * The owners whose look at the default pose deleting parameter [id] changes: a grid or track that keys on
 * it with its default on none of its keys (the collapse keeps the nearest key's slice rather than the look
 * at the default), or a blend shape the scrub cannot keep exact ([blendBindingScrubOf]).  Everything
 * else a delete does leaves the rest pose as it was.  Empty for an unknown parameter.
 *
 * @param ParameterId id The parameter to delete.
 * @return List The owners whose rest pose changes, in model order: drawables, deformers, parts, then glues.
 */
fun PuppetModel.ownersWhoseRestChangesOnDeleting(id: ParameterId): List<KeyformOwner> {
	val deleted = parameters.firstOrNull { parameter -> parameter.id == id } ?: return emptyList()
	val keepValue = deleted.default
	val defaultOf = defaultValueLookup()
	val owners = ArrayList<KeyformOwner>()
	for (drawable in drawables) {
		if (collapseMovesRest(drawable.geometryGrid, id, keepValue) || collapseMovesRest(drawable.channelGrids, id, keepValue) || blendScrubMovesRest(drawable.blendShapes, deleted, defaultOf)) {
			owners.add(KeyformOwner.Drawable(drawable.id))
		}
	}
	for (deformer in deformers) {
		val geometryMoves =
			when (deformer) {
				is Deformer.Warp -> collapseMovesRest(deformer.geometryGrid, id, keepValue) || blendScrubMovesRest(deformer.blendShapes, deleted, defaultOf)
				is Deformer.Rotation -> collapseMovesRest(deformer.geometryGrid, id, keepValue) || blendScrubMovesRest(deformer.blendShapes, deleted, defaultOf)
			}
		if (geometryMoves || collapseMovesRest(deformer.channelGrids, id, keepValue)) {
			owners.add(KeyformOwner.Deformer(deformer.id))
		}
	}
	for (part in parts) {
		if (collapseMovesRest(part.channelGrids, id, keepValue) || blendScrubMovesRest(part.blendShapes, deleted, defaultOf)) {
			owners.add(KeyformOwner.Part(part.id))
		}
	}
	for (glue in glues) {
		if (collapseMovesRest(glue.channelGrids, id, keepValue)) {
			owners.add(KeyformOwner.Glue(glue.meshA, glue.meshB))
		}
	}
	return owners
}