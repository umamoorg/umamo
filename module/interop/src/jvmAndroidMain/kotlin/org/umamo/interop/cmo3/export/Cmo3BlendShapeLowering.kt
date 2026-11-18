package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.gen.ACForm
import org.umamo.format.cmo3.model.gen.KeyFormMorphTarget
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraint
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.format.cmo3.model.gen.MorphTargetEditBaseParameterMap
import org.umamo.format.cmo3.type.CArrayList
import org.umamo.interop.ExportEntityCategory
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.cmo3.elementsOf
import org.umamo.interop.cmo3.uuidOf
import org.umamo.runtime.model.BlendShapeBinding

/**
 * The blend shapes of a keyed owner: its morph-target set (KeyFormMorphTargetSet and its weight
 * constraints) rebuilt from the edited bindings, or the forms the set already references when they
 * did not change.
 */
internal class Cmo3BlendShapeLowering(
	context: Cmo3ExportContext,
	private val index: Cmo3GraphIndex,
	private val notices: Cmo3ExportNotices,
) {
	private val editor = context.editor

	/**
	 * The morph-target form objects the current morph set references, for pool assembly.
	 *
	 * @param Any? morphSetField The owner's morph-target set field, as stored.
	 * @param Any? formsField The owner's keyforms pool field, as stored.
	 * @return List<ACForm> The referenced forms, in morph-target order.
	 */
	fun existingMorphForms(morphSetField: Any?, formsField: Any?): List<ACForm> {
		val set = morphSetField as? KeyFormMorphTargetSet ?: return emptyList()
		val formByUuid =
			elementsOf(formsField).filterIsInstance<ACForm>()
				.associateBy { form -> uuidOf(form.guid) }

		return elementsOf(set._morphTargets).filterIsInstance<KeyFormMorphTarget>()
			.mapNotNull { record -> formByUuid[uuidOf(record.keyformGuid)] }
	}

	/**
	 * Rebuilds an owner's morph-target set from the edited blend-shape bindings, reusing records
	 * and forms matched by (parameter, key value).  The inserted neutral key (null form at value 0)
	 * is dropped again - CMO3 stores no record for it.
	 *
	 * @param Any      ownerSource The source object owning the morph target set.
	 * @param String   subject     The owner's id for notices.
	 * @param Any?     currentSet  The current keyformMorphTargetSet, or null when absent.
	 * @param Function assignSet   Assigns a freshly created morph target set.
	 * @param Any?     formsField  The owner's keyforms pool, for resolving forms already on disk.
	 * @param List     bindings    The edited blend-shape bindings to rebuild from.
	 * @param Function writeForm   Writes or creates the form for one binding's payload.
	 * @return List? The morph form objects for the pool, or null when a parameter is unresolvable.
	 */
	fun <TForm : Any> rebuildMorphTargets(
		ownerSource: Any,
		subject: String,
		currentSet: Any?,
		assignSet: (Any?) -> Unit,
		formsField: Any?,
		bindings: List<BlendShapeBinding<TForm>>,
		writeForm: (existing: Any?, payload: TForm) -> ACForm,
	): List<ACForm>? {
		val set = currentSet as? KeyFormMorphTargetSet

		if (bindings.isEmpty()) {
			if (set != null) {
				// CMO3: KeyFormMorphTargetSet field _morphTargets / MorphTargetBlendWeightConstraintSet
				// field _constraints - carray_list in every corpus model.
				editor.writeGraphList(set, "KeyFormMorphTargetSet", "_morphTargets", "blendWeightConstraintSet", set._morphTargets, emptyList(), GraphListKind.CArrayList) {
					set._morphTargets = it
				}
				(set.blendWeightConstraintSet as? MorphTargetBlendWeightConstraintSet)?.let { constraintSet ->
					editor.writeGraphList(constraintSet, "MorphTargetBlendWeightConstraintSet", "_constraints", null, constraintSet._constraints, emptyList(), GraphListKind.CArrayList) {
						constraintSet._constraints = it
					}
				}
			}

			return emptyList()
		}

		val targetSet =
			set
				?: KeyFormMorphTargetSet().also {
					it.blendWeightConstraintSet = MorphTargetBlendWeightConstraintSet().apply { _constraints = CArrayList<Any?>() }
					assignSet(it)
					editor.ensureChildSlot(ownerSource, "ACParameterControllableSource", "keyformMorphTargetSet", "_extensions")
				}
		val formByUuid =
			elementsOf(formsField).filterIsInstance<ACForm>()
				.associateBy { form -> uuidOf(form.guid) }
		val existingRecords =
			elementsOf(targetSet._morphTargets).filterIsInstance<KeyFormMorphTarget>()
				.associateBy { record -> "${uuidOf(record.parameterGuid)}@${record.keyValue.toRawBits()}" }
		val records = ArrayList<Any?>()
		val morphForms = ArrayList<ACForm>()
		val constraints = ArrayList<Any?>()

		for ((parameterId, keys, _, forms, limits) in bindings) {
			val parameterSource = index.parameterByIdStr[parameterId.raw]

			if (parameterSource == null) {
				notices.unsupported(
					ExportEntityCategory.Keyform,
					subject,
					ExportNoticeReason.BlendShapeParameterHasNoSource(parameterId.raw),
				)

				return null
			}

			val parameterUuid = uuidOf(parameterSource.guid)

			for (keyIndex in keys.indices) {
				val payload = forms[keyIndex] ?: continue
				val keyValue = keys[keyIndex]
				val existingRecord = existingRecords["$parameterUuid@${keyValue.toRawBits()}"]
				val existingForm = existingRecord?.let { record -> formByUuid[uuidOf(record.keyformGuid)] }
				val form = writeForm(existingForm, payload)
				morphForms.add(form)
				val record =
					existingRecord
						?: KeyFormMorphTarget().apply {
							parameterGuid = parameterSource.guid
							owner = ownerSource
							// CMO3: KeyFormMorphTarget field editBaseParameterMap - corpus records
							// carry the wrapper with a null baseParameterMap, never a bare null.
							editBaseParameterMap =
								MorphTargetEditBaseParameterMap()
						}
				record.keyValue = keyValue
				record.keyformGuid = form.guid
				records.add(record)
			}

			for ((parameterId1, points) in limits) {
				val constraintSource = index.parameterByIdStr[parameterId1.raw] ?: continue

				for ((value, weight) in points) {
					constraints.add(
						MorphTargetBlendWeightConstraint().apply {
							morphTargetParameterGuid = parameterSource.guid
							constraintParameterGuid = constraintSource.guid
							constraintParameterValue = value
							blendWeight = weight
						},
					)
				}
			}
		}

		// CMO3: KeyFormMorphTargetSet field _morphTargets / MorphTargetBlendWeightConstraintSet
		// field _constraints - carray_list in every corpus model.
		editor.writeGraphList(targetSet, "KeyFormMorphTargetSet", "_morphTargets", "blendWeightConstraintSet", targetSet._morphTargets, records, GraphListKind.CArrayList) {
			targetSet._morphTargets = it
		}
		val constraintSet =
			targetSet.blendWeightConstraintSet as? MorphTargetBlendWeightConstraintSet
				?: MorphTargetBlendWeightConstraintSet().also {
					targetSet.blendWeightConstraintSet = it
					editor.ensureChildSlot(targetSet, "KeyFormMorphTargetSet", "blendWeightConstraintSet")
				}
		editor.writeGraphList(constraintSet, "MorphTargetBlendWeightConstraintSet", "_constraints", null, constraintSet._constraints, constraints, GraphListKind.CArrayList) {
			constraintSet._constraints = it
		}

		return morphForms
	}
}