package org.umamo.edit

/**
 * The control the Parameters panel shows a parameter with, picked for that parameter alone over the application's
 * preference (docs/format/UMA.md § 7.4, `parameterControls`).  A linked pair takes the choice of its horizontal
 * member, and reads it as the preference does: Slider is the pair's pad, and Dial its two dials.
 */
enum class ParameterControl {
	/** The slider, or a pair's pad. */
	Slider,

	/** The dial, or a pair's two dials. */
	Dial,
}