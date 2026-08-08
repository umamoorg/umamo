package org.umamo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import org.umamo.settings.Settings

/**
 * A write-through, reactive binding to one string setting: reading the returned state yields the current
 * value (the user override, else the default), and assigning to it persists immediately via
 * [Settings.setString] - the auto-save the preferences UI is built on, with no commit step.  A change to
 * the same key from elsewhere (another control, a future shortcut) flows back in through the settings
 * `changes` flow, so the binding stays in sync without the read-once-then-collect boilerplate each call
 * site would otherwise repeat (cf. ProvideAppThemeFromSettings, PersistentEditorShell).
 *
 * @param String key     The dotted settings key (e.g. "interface.theme").
 * @param String default The value used when the key is absent (rarely hit; defaultSettings.json seeds most).
 * @return MutableState A state whose getter reads the merged value and whose setter persists to the user layer.
 */
@Composable
fun rememberStringSetting(key: String, default: String): MutableState<String> {
	val settings = LocalSettings.current
	val backing = remember(settings, key) { mutableStateOf(settings.getString(key) ?: default) }
	// Re-read when this key is written from anywhere, so a value changed by another control or command
	// (not just this binding's own setter) reflects here too.
	LaunchedEffect(settings, key) {
		settings.changes.collect { changedKey ->
			if (changedKey == key) {
				backing.value = settings.getString(key) ?: default
			}
		}
	}
	return remember(settings, key, backing) { WriteThroughSettingState(settings, key, backing) }
}

/**
 * The [MutableState] returned by [rememberStringSetting]: reads delegate to [backing] (kept current by
 * the settings `changes` collector), and a write both persists through [Settings.setString] and updates
 * [backing] at once so the new value is visible on the very next read without waiting for the flow to
 * round-trip.
 *
 * @property Settings           settings The settings instance the write persists into.
 * @property String             key      The dotted key being bound.
 * @property MutableState backing  The local state mirroring the merged value.
 */
private class WriteThroughSettingState(
	private val settings: Settings,
	private val key: String,
	private val backing: MutableState<String>,
) : MutableState<String> {
	override var value: String
		get() = backing.value
		set(newValue) {
			// Update the local mirror first so the read is immediate, then persist (which also emits on
			// `changes`; the collector re-reading the now-identical value is a harmless no-op).
			backing.value = newValue
			settings.setString(key, newValue)
		}

	override fun component1(): String = value

	override fun component2(): (String) -> Unit = { newValue -> value = newValue }
}

/**
 * A write-through, reactive binding to one number-valued setting - the [rememberStringSetting]
 * counterpart for numeric keys, with the same auto-save and external-change semantics.
 *
 * @param String key     The dotted settings key (e.g. "viewport.zoomStepPercent").
 * @param Int default The value used when the key is absent (rarely hit; defaultSettings.json seeds most).
 * @return MutableState A state whose getter reads the merged value and whose setter persists to the user layer.
 */
@Composable
fun rememberIntSetting(key: String, default: Int): MutableState<Int> {
	val settings = LocalSettings.current
	val backing = remember(settings, key) { mutableStateOf(settings.getInt(key) ?: default) }
	// Re-read when this key is written from anywhere, so a value changed by another control or command
	// (not just this binding's own setter) reflects here too.
	LaunchedEffect(settings, key) {
		settings.changes.collect { changedKey ->
			if (changedKey == key) {
				backing.value = settings.getInt(key) ?: default
			}
		}
	}
	return remember(settings, key, backing) { WriteThroughIntSettingState(settings, key, backing) }
}

/**
 * The [MutableState] returned by [rememberIntSetting]: the numeric twin of
 * [WriteThroughSettingState], persisting through [Settings.setInt].
 *
 * @property Settings           settings The settings instance the write persists into.
 * @property String             key      The dotted key being bound.
 * @property MutableState backing  The local state mirroring the merged value.
 */
private class WriteThroughIntSettingState(
	private val settings: Settings,
	private val key: String,
	private val backing: MutableState<Int>,
) : MutableState<Int> {
	override var value: Int
		get() = backing.value
		set(newValue) {
			// Update the local mirror first so the read is immediate, then persist (which also emits on
			// `changes`; the collector re-reading the now-identical value is a harmless no-op).
			backing.value = newValue
			settings.setInt(key, newValue)
		}

	override fun component1(): Int = value

	override fun component2(): (Int) -> Unit = { newValue -> value = newValue }
}

/**
 * A write-through, reactive binding to one number-valued setting - the [rememberStringSetting]
 * counterpart for numeric keys, with the same auto-save and external-change semantics.
 *
 * @param String key     The dotted settings key (e.g. "viewport.zoomStepPercent").
 * @param Double default The value used when the key is absent (rarely hit; defaultSettings.json seeds most).
 * @return MutableState A state whose getter reads the merged value and whose setter persists to the user layer.
 */
@Composable
fun rememberDoubleSetting(key: String, default: Double): MutableState<Double> {
	val settings = LocalSettings.current
	val backing = remember(settings, key) { mutableStateOf(settings.getDouble(key) ?: default) }
	// Re-read when this key is written from anywhere, so a value changed by another control or command
	// (not just this binding's own setter) reflects here too.
	LaunchedEffect(settings, key) {
		settings.changes.collect { changedKey ->
			if (changedKey == key) {
				backing.value = settings.getDouble(key) ?: default
			}
		}
	}
	return remember(settings, key, backing) { WriteThroughDoubleSettingState(settings, key, backing) }
}

/**
 * The [MutableState] returned by [rememberDoubleSetting]: the numeric twin of
 * [WriteThroughSettingState], persisting through [Settings.setDouble].
 *
 * @property Settings           settings The settings instance the write persists into.
 * @property String             key      The dotted key being bound.
 * @property MutableState backing  The local state mirroring the merged value.
 */
private class WriteThroughDoubleSettingState(
	private val settings: Settings,
	private val key: String,
	private val backing: MutableState<Double>,
) : MutableState<Double> {
	override var value: Double
		get() = backing.value
		set(newValue) {
			// Update the local mirror first so the read is immediate, then persist (which also emits on
			// `changes`; the collector re-reading the now-identical value is a harmless no-op).
			backing.value = newValue
			settings.setDouble(key, newValue)
		}

	override fun component1(): Double = value

	override fun component2(): (Double) -> Unit = { newValue -> value = newValue }
}

/**
 * A write-through, reactive binding to one boolean-valued setting - the [rememberStringSetting]
 * counterpart for flag keys, with the same auto-save and external-change semantics.
 *
 * @param String key     The dotted settings key (e.g. "viewport.rendering.supersample").
 * @param Boolean default The value used when the key is absent (rarely hit; defaultSettings.json seeds most).
 * @return MutableState A state whose getter reads the merged value and whose setter persists to the user layer.
 */
@Composable
fun rememberBooleanSetting(key: String, default: Boolean): MutableState<Boolean> {
	val settings = LocalSettings.current
	val backing = remember(settings, key) { mutableStateOf(settings.getBoolean(key) ?: default) }
	// Re-read when this key is written from anywhere, so a value changed by another control or command
	// (not just this binding's own setter) reflects here too.
	LaunchedEffect(settings, key) {
		settings.changes.collect { changedKey ->
			if (changedKey == key) {
				backing.value = settings.getBoolean(key) ?: default
			}
		}
	}
	return remember(settings, key, backing) { WriteThroughBooleanSettingState(settings, key, backing) }
}

/**
 * The [MutableState] returned by [rememberBooleanSetting]: the flag twin of
 * [WriteThroughSettingState], persisting through [Settings.setBoolean].
 *
 * @property Settings           settings The settings instance the write persists into.
 * @property String             key      The dotted key being bound.
 * @property MutableState backing  The local state mirroring the merged value.
 */
private class WriteThroughBooleanSettingState(
	private val settings: Settings,
	private val key: String,
	private val backing: MutableState<Boolean>,
) : MutableState<Boolean> {
	override var value: Boolean
		get() = backing.value
		set(newValue) {
			// Update the local mirror first so the read is immediate, then persist (which also emits on
			// `changes`; the collector re-reading the now-identical value is a harmless no-op).
			backing.value = newValue
			settings.setBoolean(key, newValue)
		}

	override fun component1(): Boolean = value

	override fun component2(): (Boolean) -> Unit = { newValue -> value = newValue }
}