package org.umamo.edit

import org.umamo.runtime.eval.colorAt
import org.umamo.runtime.eval.contributesAt
import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.eval.multiplierAt
import org.umamo.runtime.eval.rotationFormAt
import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.eval.warpControlPointsAt
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.keyform.keyIndexAt
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

/*
 * What deleting a parameter does to the blend shapes that name it, and the rest-pose questions the delete
 * asks of each grid, track, and binding as it scrubs them.  A binding names its driving parameter and each
 * weight limit names its constraint parameter; a delete that left either behind would write an id the model
 * no longer has into every file saved after it.  The scrub keeps the rest pose exact wherever the model can
 * say so: the evaluator reads a missing parameter as 0, the neutral key, and the grids collapse to the slice
 * at the parameter's default, so the scrub reads each binding at that same default.  The delete itself is
 * parameterDeletionOf (ParameterCrudEdits.kt); it answers the rest-pose question in the same walk, so the
 * owners it reports can never disagree with the model it produces.
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
 * An owner's bindings with a parameter scrubbed out, and whether the scrub moved the rest pose.
 *
 * @property List    bindings  The scrubbed bindings - the same list instance when none named the parameter.
 * @property Boolean movesRest Whether some binding's scrub is not exact at the default pose.
 */
internal class ScrubbedBindings<TForm : Any>(val bindings: List<BlendShapeBinding<TForm>>, val movesRest: Boolean)

/**
 * These bindings with [deleted] scrubbed out of them ([blendBindingScrubOf]), or this same list when none
 * names it, with whether any scrub was inexact at the default pose - decided from the one classification of
 * each binding, so the delete and its report read the same answer.
 *
 * @param Parameter deleted   The parameter being deleted.
 * @param Function  defaultOf The default value per parameter id, the deleted one included.
 * @param Function  scalerOf  Builds the owner's form scaler, asked for at most once and only when some binding
 *   bakes a cap into its forms, since it samples the owner's grids at the default pose.
 * @return ScrubbedBindings The scrubbed bindings and whether the rest pose moved.
 */
internal fun <TForm : Any> List<BlendShapeBinding<TForm>>.scrubbedOf(
	deleted: Parameter,
	defaultOf: (ParameterId) -> Float,
	scalerOf: () -> (TForm, Float) -> TForm,
): ScrubbedBindings<TForm> {
	if (none { binding -> binding.parameterId == deleted.id || binding.limits.any { limit -> limit.parameterId == deleted.id } }) {
		return ScrubbedBindings(this, movesRest = false)
	}
	val scaler by lazy(scalerOf)
	var movesRest = false
	val bindings =
		mapNotNull { binding ->
			when (val scrub = blendBindingScrubOf(binding, deleted, defaultOf)) {
				BlendBindingScrub.Unchanged -> binding
				is BlendBindingScrub.Drop -> {
					if (!scrub.restPreserved) {
						movesRest = true
					}
					null
				}
				is BlendBindingScrub.DropLimits -> {
					if (!scrub.restPreserved) {
						movesRest = true
					}
					binding.copy(limits = binding.limits.filterNot { limit -> limit.parameterId == deleted.id })
				}
				is BlendBindingScrub.ScaleForms ->
					binding.copy(
						limits = emptyList(),
						forms = binding.forms.map { form -> form?.let { present -> scaler(present, scrub.factor) } },
					)
			}
		}
	return ScrubbedBindings(bindings, movesRest)
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
 * Whether collapsing [parameterId]'s axis out of [grid] at [keepValue] changes the grid's value at the default
 * pose.  It does when the default sits on none of the axis's keys - the collapse keeps the nearest key's slice
 * rather than the look at the default - and, for a sole-axis grid whose kept key has no cell (a sparse grid, as
 * a CMO3 import leaves one whose form guid did not resolve), when [missingKeptCellMoves]: the collapse then drops
 * the track or the grid, and what a missing cell evaluated to (nothing - a zero contribution) gives way to the
 * owner's static, or to no grid at all.  A grid with more axes keeps that cell missing, so its value at the
 * default is the same nothing before and after.
 *
 * @param KeyformGrid? grid                 The grid, or null.
 * @param ParameterId  parameterId          The parameter being deleted.
 * @param Float        keepValue            Its default.
 * @param Boolean      missingKeptCellMoves Whether a sole-axis grid losing its (absent) kept cell counts as a
 *   change.  False for a drawable's geometry, where zero deltas and no grid both draw the base mesh.
 * @return Boolean True when the collapse changes the grid's value at the default pose.
 */
internal fun collapseMovesRest(grid: KeyformGrid<*>?, parameterId: ParameterId, keepValue: Float, missingKeptCellMoves: Boolean = true): Boolean {
	val axisIndex = grid?.axisIndexOf(parameterId) ?: return false
	if (axisIndex < 0) {
		return false
	}
	val keepIndex = grid.keyIndexAt(parameterId, keepValue)
	if (keepIndex < 0) {
		return true
	}
	if (!missingKeptCellMoves || grid.axes.size > 1) {
		return false
	}
	return grid.cells.none { cell -> cell.coordinate.getOrNull(axisIndex) == keepIndex }
}

/**
 * Whether collapsing [parameterId] out of any of these tracks changes its value at the default pose
 * ([collapseMovesRest]).
 *
 * @param ChannelGrids channels    The owner's tracks.
 * @param ParameterId  parameterId The parameter being deleted.
 * @param Float        keepValue   Its default.
 * @return Boolean True when some track's value at the default pose changes.
 */
internal fun collapseMovesRest(channels: ChannelGrids, parameterId: ParameterId, keepValue: Float): Boolean =
	channels.gridsByChannel.values.any { grid -> collapseMovesRest(grid, parameterId, keepValue) }

/**
 * The owners whose look at the default pose deleting parameter [id] changes: the delete's own answer
 * ([parameterDeletionOf]), so this can never disagree with the model the delete produces.  A caller that
 * also wants that model takes the deletion itself rather than asking twice.  Empty for an unknown parameter.
 *
 * @param ParameterId id The parameter to delete.
 * @return List The owners whose rest pose changes, in model order: drawables, deformers, parts, then glues.
 */
fun PuppetModel.ownersWhoseRestChangesOnDeleting(id: ParameterId): List<KeyformOwner> = parameterDeletionOf(id)?.restChangedOwners.orEmpty()