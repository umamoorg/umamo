package org.umamo.edit.seed

import org.umamo.runtime.model.Parameter

/**
 * The parameter set a new model is seeded with.
 *
 * An enum rather than a free list so the choice persists as one settings value and a later template
 * (a quadruped, a prop) is one more entry.  [key] is the stored value; the entry name is Umamo's, not
 * a format's.  The template is data only: whoever creates the model (an artwork import, a blank
 * document) resolves it to [parameters] and seeds those.
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
		 * setting never leaves a new model without a template.
		 *
		 * @param String? key The stored settings value.
		 * @return ParameterTemplate The template.
		 */
		fun fromKey(key: String?): ParameterTemplate = entries.firstOrNull { template -> template.key == key } ?: Default
	}
}