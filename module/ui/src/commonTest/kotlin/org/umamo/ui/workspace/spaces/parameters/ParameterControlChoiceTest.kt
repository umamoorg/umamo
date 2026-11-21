package org.umamo.ui.workspace.spaces.parameters

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.ui.settings.RepeatControlSetting
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which control a row shows: a parameter that does not repeat always gets the slider and a pair with no
 * repeating member the pad, and otherwise the preference decides, the dial choice giving a pair a dial per axis and
 * the slider choice its pad.
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
			assertEquals(SingleControl.Slider, singleControlOf(clamping, preference), preference.key)
		}
	}

	/** A repeating parameter shows the control the preference names. */
	@Test
	fun aRepeatingParameterFollowsThePreference() {
		val repeating = parameter("ParamDrip", repeat = true)

		assertEquals(SingleControl.Dial, singleControlOf(repeating, RepeatControlSetting.Dial))
		assertEquals(SingleControl.Slider, singleControlOf(repeating, RepeatControlSetting.Slider))
	}

	/** A pair with no repeating member shows the pad whatever the preference. */
	@Test
	fun aPairWithNoRepeatingMemberAlwaysShowsThePad() {
		val horizontal = parameter("ParamAngleX", repeat = false)
		val vertical = parameter("ParamAngleY", repeat = false)

		for (preference in RepeatControlSetting.entries) {
			assertEquals(PairControl.Pad, pairControlOf(horizontal, vertical, preference), preference.key)
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
			assertEquals(PairControl.Dials, pairControlOf(horizontal, vertical, RepeatControlSetting.Dial), "$repeatingMember under the dial choice")
			assertEquals(PairControl.Pad, pairControlOf(horizontal, vertical, RepeatControlSetting.Slider), "$repeatingMember under the slider choice")
		}
	}
}