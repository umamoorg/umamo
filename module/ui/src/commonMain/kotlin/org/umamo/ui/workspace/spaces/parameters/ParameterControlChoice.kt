package org.umamo.ui.workspace.spaces.parameters

import org.umamo.edit.ParameterControl
import org.umamo.runtime.model.Parameter
import org.umamo.ui.settings.RepeatControlSetting

/** The control a linked pair's island shows. */
internal enum class PairControl {
	/** The 2D pad, which drives both axes at once. */
	Pad,

	/** A captioned dial per axis, each driving its own. */
	Dials,
}

/**
 * The control a single parameter shows: the one the parameter was set to, whether or not it repeats, else the dial
 * when it repeats and the rigger prefers dials, and the slider otherwise.
 *
 * @param Parameter parameter The parameter.
 * @param RepeatControlSetting preference The control the rigger prefers for a repeating parameter.
 * @param ParameterControl? override The control the parameter was set to, or null to follow the preference.
 * @return ParameterControl The control.
 */
internal fun singleControlOf(parameter: Parameter, preference: RepeatControlSetting, override: ParameterControl?): ParameterControl =
	controlOf(parameter.repeat, preference, override)

/**
 * The control a linked pair shows: the one the pair was set to, whether or not a member repeats, else a dial per
 * axis when either member repeats and the rigger prefers dials, and the pad otherwise.
 *
 * @param Parameter horizontal The pair's upper (X) member.
 * @param Parameter vertical The pair's lower (Y) member.
 * @param RepeatControlSetting preference The control the rigger prefers for a repeating parameter.
 * @param ParameterControl? override The control the pair was set to, by its horizontal member, or null to follow the
 *   preference.
 * @return PairControl The control.
 */
internal fun pairControlOf(horizontal: Parameter, vertical: Parameter, preference: RepeatControlSetting, override: ParameterControl?): PairControl =
	when (controlOf(horizontal.repeat || vertical.repeat, preference, override)) {
		ParameterControl.Slider -> PairControl.Pad
		ParameterControl.Dial -> PairControl.Dials
	}

/**
 * The rule a row and a pair both choose by: the control that was picked, else the preferred one where something
 * repeats, else the slider.
 *
 * @param Boolean repeats Whether the parameter repeats; for a pair, whether either member does.
 * @param RepeatControlSetting preference The control the rigger prefers for a repeating parameter.
 * @param ParameterControl? override The control that was picked, or null to follow the preference.
 * @return ParameterControl The control, a pair reading its slider as the pad and its dial as a dial per axis.
 */
private fun controlOf(repeats: Boolean, preference: RepeatControlSetting, override: ParameterControl?): ParameterControl =
	override ?: if (repeats) preference.control() else ParameterControl.Slider

/**
 * The control this preference names, in the vocabulary a parameter's own choice uses.
 *
 * @return ParameterControl The control.
 */
private fun RepeatControlSetting.control(): ParameterControl =
	when (this) {
		RepeatControlSetting.Dial -> ParameterControl.Dial
		RepeatControlSetting.Slider -> ParameterControl.Slider
	}