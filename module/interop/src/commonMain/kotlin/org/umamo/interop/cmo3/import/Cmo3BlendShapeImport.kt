package org.umamo.interop.cmo3.import

import org.umamo.format.cmo3.model.gen.KeyFormMorphTarget
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraint
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.ParameterId

/*
 * The blend-shape reader of the CMO3 import, shared by the deformer and drawable producers.
 */

/**
 * Builds a source's blend-shape bindings from its morph-target set: one binding per driving
 * parameter, keys sorted with the neutral key inserted at value 0.
 *
 * CMO3: ACParameterControllableSource field keyformMorphTargetSet - a KeyFormMorphTargetSet
 * whose _morphTargets each bind one form (keyformGuid, from the source's shared keyforms pool)
 * at one keyValue on one driving parameter (parameterGuid).  CMO3 stores no morph target at
 * value 0 in the usual case; the baked MOC3 records carry sorted(keys + 0) with the neutral at
 * value 0, so ingest inserts it here with a null form (corpus-verified rule - see
 * MorphTargetJoinProbeTest).  The editBaseParameterMap field is ignored: it is null in every
 * corpus instance, its semantics unresolved.
 *
 * @param Any?     morphTargetSet    The source's `keyformMorphTargetSet`.
 * @param Any?     forms             The source's `keyforms` collection (the form objects).
 * @param Map      paramIdByUuid     uuid → ParameterId, from pass 1.
 * @param Function formPayload       Builds the typed form payload from a raw form object.
 * @return List The bindings (empty when the source carries no morph targets).
 */
internal fun <TForm : Any> blendShapeBindingsOf(
	morphTargetSet: Any?,
	forms: Any?,
	paramIdByUuid: Map<String, ParameterId>,
	formPayload: (Any) -> TForm?,
): List<BlendShapeBinding<TForm>> {
	val set = morphTargetSet as? KeyFormMorphTargetSet ?: return emptyList()
	val targets = elementsOf(set._morphTargets).filterIsInstance<KeyFormMorphTarget>()

	if (targets.isEmpty()) {
		return emptyList()
	}

	val formByUuid = formPool(forms)
	val constraints =
		elementsOf((set.blendWeightConstraintSet as? MorphTargetBlendWeightConstraintSet)?._constraints)
			.filterIsInstance<MorphTargetBlendWeightConstraint>()

	return targets.groupBy { uuidOf(it.parameterGuid) }.mapNotNull { (parameterUuid, group) ->
		val parameterId = parameterUuid?.let(paramIdByUuid::get) ?: return@mapNotNull null
		val neutralValue = 0f
		val payloadByKey =
			group.mapNotNull { target ->
				val form = formByUuid[uuidOf(target.keyformGuid)] ?: return@mapNotNull null
				val payload = formPayload(form) ?: return@mapNotNull null
				target.keyValue to payload
			}.toMap()

		if (payloadByKey.isEmpty()) {
			return@mapNotNull null
		}

		val keys = (payloadByKey.keys + neutralValue).sorted()
		BlendShapeBinding(
			parameterId = parameterId,
			keys = keys.toFloatArray(),
			neutralIndex = keys.indexOf(neutralValue),
			forms = keys.map { key -> payloadByKey[key] },
			limits = blendWeightLimitsOf(constraints, parameterUuid, paramIdByUuid),
		)
	}
}

/**
 * Groups a morph-target set's flat constraint records into per-parameter limit curves for one
 * driving parameter.
 *
 * CMO3: MorphTargetBlendWeightConstraint - (morphTargetParameterGuid, constraintParameterGuid,
 * constraintParameterValue, blendWeight); one curve point per record, grouped by the
 * constraint parameter, sorted by its value.
 *
 * @param List collection    The set's constraint records.
 * @param String morphTargetParameterUuid The driving parameter whose limits are wanted.
 * @param Map  paramIdByUuid uuid → ParameterId, from pass 1.
 * @return List The limit curves (empty when the set has none for this parameter).
 */
private fun blendWeightLimitsOf(
	collection: List<MorphTargetBlendWeightConstraint>,
	morphTargetParameterUuid: String,
	paramIdByUuid: Map<String, ParameterId>,
): List<BlendWeightLimit> =
	collection
		.filter { uuidOf(it.morphTargetParameterGuid) == morphTargetParameterUuid }
		.groupBy { uuidOf(it.constraintParameterGuid) }
		.mapNotNull { (constraintUuid, records) ->
			val parameterId = constraintUuid?.let(paramIdByUuid::get) ?: return@mapNotNull null
			BlendWeightLimit(
				parameterId = parameterId,
				points =
					records
						.sortedBy { it.constraintParameterValue }
						.map { BlendWeightLimitPoint(it.constraintParameterValue, it.blendWeight) },
			)
		}