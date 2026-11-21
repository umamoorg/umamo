package org.umamo.ui.workspace.spaces.parameters

import org.umamo.edit.ParameterControl
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.settings.RepeatControlSetting
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which control a row shows: a control the parameter or pair was set to wins, whether or not it repeats;
 * otherwise a parameter that does not repeat gets the slider and a pair with no repeating member the pad, and the
 * preference decides the rest, the dial choice giving a pair a dial per axis and the slider choice its pad.
 */
class ParameterControlChoiceTest {
	/**
	 * A parameter over -1..1.
	 *
	 * @param String raw The parameter's id.
	 * @param Boolean repeat Whether it wraps past its limits.
	 * @return Parameter The parameter.
	 */
	private fun parameter(raw: String, repeat: Boolean): Parameter = Parameter(ParameterId(raw), raw, min = -1f, max = 1f, default = 0f, repeat = repeat)

	/** A parameter that does not repeat shows the slider whatever the preference. */
	@Test
	fun aParameterThatDoesNotRepeatAlwaysShowsTheSlider() {
		val clamping = parameter("ParamAngleZ", repeat = false)

		for (preference in RepeatControlSetting.entries) {
			assertEquals(ParameterControl.Slider, singleControlOf(clamping, preference, override = null), preference.key)
		}
	}

	/** A repeating parameter shows the control the preference names. */
	@Test
	fun aRepeatingParameterFollowsThePreference() {
		val repeating = parameter("ParamDrip", repeat = true)

		assertEquals(ParameterControl.Dial, singleControlOf(repeating, RepeatControlSetting.Dial, override = null))
		assertEquals(ParameterControl.Slider, singleControlOf(repeating, RepeatControlSetting.Slider, override = null))
	}

	/** A pair with no repeating member shows the pad whatever the preference. */
	@Test
	fun aPairWithNoRepeatingMemberAlwaysShowsThePad() {
		val horizontal = parameter("ParamAngleX", repeat = false)
		val vertical = parameter("ParamAngleY", repeat = false)

		for (preference in RepeatControlSetting.entries) {
			assertEquals(PairControl.Pad, pairControlOf(horizontal, vertical, preference, override = null), preference.key)
		}
	}

	/** A pair with either member repeating, or both, shows a dial per axis under the dial choice and its pad under the slider choice. */
	@Test
	fun aPairWithARepeatingMemberFollowsThePreference() {
		val members =
			listOf(
				"upper" to (parameter("ParamAngleX", repeat = true) to parameter("ParamAngleY", repeat = false)),
				"lower" to (parameter("ParamAngleX", repeat = false) to parameter("ParamAngleY", repeat = true)),
				"both" to (parameter("ParamAngleX", repeat = true) to parameter("ParamAngleY", repeat = true)),
			)

		for ((repeatingMember, pair) in members) {
			val (horizontal, vertical) = pair
			assertEquals(PairControl.Dials, pairControlOf(horizontal, vertical, RepeatControlSetting.Dial, override = null), "$repeatingMember under the dial choice")
			assertEquals(PairControl.Pad, pairControlOf(horizontal, vertical, RepeatControlSetting.Slider, override = null), "$repeatingMember under the slider choice")
		}
	}

	/** A parameter's own control beats the preference, and holds on a parameter that does not repeat. */
	@Test
	fun aParametersOwnControlWinsWhetherOrNotItRepeats() {
		for (repeat in listOf(true, false)) {
			val parameter = parameter("ParamDrip", repeat)

			for (preference in RepeatControlSetting.entries) {
				assertEquals(ParameterControl.Dial, singleControlOf(parameter, preference, ParameterControl.Dial), "repeat=$repeat under ${preference.key}")
				assertEquals(ParameterControl.Slider, singleControlOf(parameter, preference, ParameterControl.Slider), "repeat=$repeat under ${preference.key}")
			}
		}
	}

	/** A pair's own control beats the preference, Slider meaning its pad, and holds on a pair with no repeating member. */
	@Test
	fun aPairsOwnControlWinsWhetherOrNotAMemberRepeats() {
		for (repeat in listOf(true, false)) {
			val horizontal = parameter("ParamAngleX", repeat)
			val vertical = parameter("ParamAngleY", repeat = false)

			for (preference in RepeatControlSetting.entries) {
				assertEquals(PairControl.Dials, pairControlOf(horizontal, vertical, preference, ParameterControl.Dial), "repeat=$repeat under ${preference.key}")
				assertEquals(PairControl.Pad, pairControlOf(horizontal, vertical, preference, ParameterControl.Slider), "repeat=$repeat under ${preference.key}")
			}
		}
	}
}