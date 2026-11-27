package org.umamo.ui.settings

/**
 * The settings keys and bundled defaults for the Parameters panel.  The same split as the viewport's keys object: the
 * panel and the preferences row both read these constants, so neither duplicates the key literal.
 */
internal object ParametersSettings {
	const val REPEAT_CONTROL_KEY = "interface.parameters.repeatControl"

	/** The fallback for a missing or unknown value, kept in lockstep with defaultSettings.json. */
	val REPEAT_CONTROL_DEFAULT: RepeatControlSetting = RepeatControlSetting.Dial
}

/**
 * The control a repeating parameter shows, stored as its key.  A pair with a repeating member follows the same
 * choice: the dial preference gives it a dial per axis, and the slider preference gives it its pad.
 */
internal enum class RepeatControlSetting(val key: String) {
	/** A dial, which loops as the value does. */
	Dial("dial"),

	/** The slider, and for a pair the pad. */
	Slider("slider"),
	;

	companion object {
		/**
		 * The choice a stored key names, or the bundled default for an unknown or missing key.
		 *
		 * @param String? key The stored key.
		 * @return RepeatControlSetting The choice.
		 */
		fun fromKey(key: String?): RepeatControlSetting = entries.firstOrNull { setting -> setting.key == key } ?: ParametersSettings.REPEAT_CONTROL_DEFAULT
	}
}