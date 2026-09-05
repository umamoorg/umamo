package org.umamo.interop.art

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId

/**
 * The parameter set an artwork import seeds a new model with.
 *
 * An enum rather than a free list so the choice persists as one settings value and a later template
 * (a quadruped, a prop) is one more entry.  [key] is the stored value; the entry name is Umamo's, not
 * a format's.
 *
 * @property String key The value the settings key `import.parameterTemplate` stores.
 */
enum class ParameterTemplate(val key: String) {
	/** No parameters; the rigger creates every axis by hand. */
	None("none"),

	/** The standard humanoid set every Live2D-compatible runtime and application expects. */
	Humanoid("humanoid"),
	;

	/** The parameters the template seeds, in the order the panel lists them. */
	val parameters: List<Parameter>
		get() =
			when (this) {
				None -> emptyList()
				Humanoid -> HumanoidParameters.list
			}

	companion object {
		/** The template that seeds a new model when nothing else is configured. */
		val Default: ParameterTemplate = Humanoid

		/**
		 * Resolves a stored key, falling back to [Default] for an unknown or absent one so a stale
		 * setting never leaves an import without a template.
		 *
		 * @param String? key The stored settings value.
		 * @return ParameterTemplate The template.
		 */
		fun fromKey(key: String?): ParameterTemplate = entries.firstOrNull { template -> template.key == key } ?: Default
	}
}

/**
 * The humanoid parameter set: the ids, ranges, and defaults of Live2D's published Standard Parameter
 * List, which every Live2D-compatible runtime, tracker, and application keys on.  The ids are
 * format-level identifiers and stay verbatim; the display names are English document data the
 * rigger may rename.
 */
object HumanoidParameters {
	/**
	 * One standard parameter.
	 *
	 * @param String id      The format-level id, e.g. `ParamAngleX`.
	 * @param String name    The English display name.
	 * @param Float  min     The axis minimum.
	 * @param Float  max     The axis maximum.
	 * @param Float  default The rest value.
	 * @return Parameter The parameter.
	 */
	private fun standard(id: String, name: String, min: Float, max: Float, default: Float = 0f): Parameter =
		Parameter(id = ParameterId(id), name = name, min = min, max = max, default = default)

	/** The set, in the order the official parameter palette lists it. */
	val list: List<Parameter> =
		listOf(
			standard("ParamAngleX", "Angle X", -30f, 30f),
			standard("ParamAngleY", "Angle Y", -30f, 30f),
			standard("ParamAngleZ", "Angle Z", -30f, 30f),
			standard("ParamEyeLOpen", "Eye L Open", 0f, 1f, default = 1f),
			standard("ParamEyeLSmile", "Eye L Smile", 0f, 1f),
			standard("ParamEyeROpen", "Eye R Open", 0f, 1f, default = 1f),
			standard("ParamEyeRSmile", "Eye R Smile", 0f, 1f),
			standard("ParamEyeBallX", "Eyeball X", -1f, 1f),
			standard("ParamEyeBallY", "Eyeball Y", -1f, 1f),
			standard("ParamBrowLY", "Brow L Y", -1f, 1f),
			standard("ParamBrowRY", "Brow R Y", -1f, 1f),
			standard("ParamBrowLX", "Brow L X", -1f, 1f),
			standard("ParamBrowRX", "Brow R X", -1f, 1f),
			standard("ParamBrowLAngle", "Brow L Angle", -1f, 1f),
			standard("ParamBrowRAngle", "Brow R Angle", -1f, 1f),
			standard("ParamBrowLForm", "Brow L Form", -1f, 1f),
			standard("ParamBrowRForm", "Brow R Form", -1f, 1f),
			standard("ParamMouthForm", "Mouth Form", -1f, 1f),
			standard("ParamMouthOpenY", "Mouth Open", 0f, 1f),
			standard("ParamCheek", "Cheek", 0f, 1f),
			standard("ParamBodyAngleX", "Body Rotation X", -10f, 10f),
			standard("ParamBodyAngleY", "Body Rotation Y", -10f, 10f),
			standard("ParamBodyAngleZ", "Body Rotation Z", -10f, 10f),
			standard("ParamBreath", "Breath", 0f, 1f),
			standard("ParamArmLA", "Arm L A", -10f, 10f),
			standard("ParamArmRA", "Arm R A", -10f, 10f),
			standard("ParamArmLB", "Arm L B", -10f, 10f),
			standard("ParamArmRB", "Arm R B", -10f, 10f),
			standard("ParamHandL", "Hand L", -10f, 10f),
			standard("ParamHandR", "Hand R", -10f, 10f),
			standard("ParamHairFront", "Hair Move Front", -1f, 1f),
			standard("ParamHairSide", "Hair Move Side", -1f, 1f),
			standard("ParamHairBack", "Hair Move Back", -1f, 1f),
			standard("ParamHairFluffy", "Hair Fluffy", 0f, 1f),
			standard("ParamShoulderY", "Shoulder Y", -10f, 10f),
			standard("ParamBustX", "Bust X", -1f, 1f),
			standard("ParamBustY", "Bust Y", -1f, 1f),
			standard("ParamBaseX", "Base X", -30f, 30f),
			standard("ParamBaseY", "Base Y", -30f, 30f),
		)
}