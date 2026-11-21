package org.umamo.ui.workspace.spaces.parameters

import org.umamo.runtime.model.Parameter
import org.umamo.ui.settings.RepeatControlSetting

/** The control a single parameter's island shows. */
internal enum class SingleControl {
	/** The linear slider, which stops at its ends. */
	Slider,

	/** The dial, which loops round its seam. */
	Dial,
}

/** The control a linked pair's island shows. */
internal enum class PairControl {
	/** The 2D pad, which drives both axes at once. */
	Pad,

	/** A captioned dial per axis, each driving its own. */
	Dials,
}

/**
 * The control a single parameter shows: the dial when it repeats and the rigger prefers dials, and the slider
 * otherwise.
 *
 * @param Parameter parameter The parameter.
 * @param RepeatControlSetting preference The control the rigger prefers for a repeating parameter.
 * @return SingleControl The control.
 */
internal fun singleControlOf(parameter: Parameter, preference: RepeatControlSetting): SingleControl {
	if (!parameter.repeat) {
		return SingleControl.Slider
	}

	return when (preference) {
		RepeatControlSetting.Dial -> SingleControl.Dial
		RepeatControlSetting.Slider -> SingleControl.Slider
	}
}

/**
 * The control a linked pair shows: a dial per axis when either member repeats and the rigger prefers dials, and the
 * pad otherwise.
 *
 * @param Parameter horizontal The pair's upper (X) member.
 * @param Parameter vertical The pair's lower (Y) member.
 * @param RepeatControlSetting preference The control the rigger prefers for a repeating parameter.
 * @return PairControl The control.
 */
internal fun pairControlOf(horizontal: Parameter, vertical: Parameter, preference: RepeatControlSetting): PairControl {
	if (!horizontal.repeat && !vertical.repeat) {
		return PairControl.Pad
	}

	return when (preference) {
		RepeatControlSetting.Dial -> PairControl.Dials
		RepeatControlSetting.Slider -> PairControl.Pad
	}
}