package org.umamo.interop.cmo3.export

import org.umamo.format.cmo3.model.custom.CFloatColor
import org.umamo.format.cmo3.model.custom.CLabelColor
import org.umamo.format.cmo3.model.gen.CLabelColorType
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_TextureAtlasRegion
import org.umamo.format.cmo3.model.gen.KeyFormMorphTargetSet
import org.umamo.format.cmo3.model.gen.MorphTargetBlendWeightConstraintSet
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.type.CArrayList

/*
 * The small graph nodes an export writes fresh, the same way wherever it needs one: on the blank
 * skeleton, on the shells the structure lowering creates, on the keyform lowering's fresh forms,
 * where the deformer and drawable lowerings move an entity under the root deformer, and on a
 * drawable that samples an atlas page, whether created or packed into a retained graph.
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

/**
 * A drawable's atlas-region input, sampling a page through the page-pixels-to-canvas placement that
 * lays the drawable's texture patch over its canvas mesh.  The editor inverts that placement to draw
 * the mesh over the texture in its atlas and mesh-edit views.
 *
 * CMO3: CTextureInput_TextureAtlasRegion fields textureAtlasGuid / inputImageLocalToCanvasTransform,
 * with the ACTextureInput super's optionalTransformOnCanvas and owner backref.
 *
 * @param CTextureInputExtension extension               The drawable's texture-input extension, the input's owner.
 * @param Any?                   atlasGuid               The page atlas's own guid instance.
 * @param CAffine                inputImageLocalToCanvas The page-pixels-to-canvas placement.
 * @return CTextureInput_TextureAtlasRegion The input.
 */
internal fun atlasRegionInput(
	extension: CTextureInputExtension,
	atlasGuid: Any?,
	inputImageLocalToCanvas: CAffine,
): CTextureInput_TextureAtlasRegion =
	CTextureInput_TextureAtlasRegion().apply {
		optionalTransformOnCanvas = CAffine()
		_owner = extension
		textureAtlasGuid = atlasGuid
		inputImageLocalToCanvasTransform = inputImageLocalToCanvas
	}