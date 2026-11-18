package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CLabelColor
import org.umamo.format.cmo3.model.gen.CLabelColorType
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.type.CArrayList

/*
 * The small graph nodes an export writes fresh, the same way wherever it needs one: on the blank
 * skeleton, on the shells the structure lowering creates, on the keyform lowering's fresh forms, and
 * where the deformer and drawable lowerings move an entity under the root deformer.
 */

/**
 * The editor's root-deformer marker uuid.
 *
 * CMO3: CDeformerGuid uuid + note "ROOT" - the same fixed uuid in every corpus file, written
 * as an affecter's or root-level deformer's targetDeformerGuid.
 */
internal const val ROOT_DEFORMER_SENTINEL_UUID: String = "71fae776-e218-4aee-873e-78e8ac0cb48a"

/**
 * A fresh root-deformer sentinel guid (see ROOT_DEFORMER_SENTINEL_UUID).
 *
 * @return Guid The sentinel CDeformerGuid.
 */
internal fun rootDeformerSentinel(): Guid =
	Guid("CDeformerGuid").apply {
		uuid = ROOT_DEFORMER_SENTINEL_UUID
		note = "ROOT"
	}

/**
 * An empty morph-target set (empty targets + empty constraint set).
 *
 * @return Any The fresh KeyFormMorphTargetSet.
 */
internal fun emptyMorphTargetSet(): Any =
	KeyFormMorphTargetSet().apply {
		_morphTargets = CArrayList<Any?>()
		blendWeightConstraintSet =
			MorphTargetBlendWeightConstraintSet().apply { _constraints = CArrayList<Any?>() }
	}

/**
 * The identity multiply tint (white, opaque).
 *
 * @return CFloatColor The fresh color.
 */
internal fun identityMultiplyColor(): CFloatColor =
	CFloatColor().apply {
		red = 1f
		green = 1f
		blue = 1f
		alpha = 1f
	}

/**
 * The identity screen tint (black, opaque).
 *
 * @return CFloatColor The fresh color.
 */
internal fun identityScreenColor(): CFloatColor =
	CFloatColor().apply { alpha = 1f }

/**
 * The default label color (uncustomized, UNDEFINED type).
 *
 * @return CLabelColor The fresh label color.
 */
internal fun undefinedLabelColor(): CLabelColor =
	CLabelColor().apply {
		// CMO3: CLabelColor attr customizedColorInt (-1 = none) + CLabelColorType labelType.
		customizedColorInt = -1
		labelType = CLabelColorType.UNDEFINED
	}