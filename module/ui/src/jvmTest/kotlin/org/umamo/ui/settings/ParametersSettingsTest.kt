package org.umamo.ui.settings

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.defaultSettingsJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the bundled default of the Parameters panel's settings to the Kotlin-side fallback, so a fresh install and a
 * panel with no settings behind it show a repeating parameter the same control.
 */
class ParametersSettingsTest {
	/** The bundled value names the fallback by its key, so a typo in defaultSettings.json cannot fall back unseen. */
	@Test
	fun theBundledRepeatControlMatchesTheFallback() {
		val settings = bundledSettings()

		assertEquals(ParametersSettings.REPEAT_CONTROL_DEFAULT.key, settings.getString(ParametersSettings.REPEAT_CONTROL_KEY))
		assertEquals(RepeatControlSetting.Dial, ParametersSettings.REPEAT_CONTROL_DEFAULT, "the dial stays the default")
	}

	/** Each choice reads back from its own key, and a key nothing knows reads as the default. */
	@Test
	fun aStoredKeyNamesItsChoice() {
		for (setting in RepeatControlSetting.entries) {
			assertEquals(setting, RepeatControlSetting.fromKey(setting.key), setting.key)
		}

		assertEquals(ParametersSettings.REPEAT_CONTROL_DEFAULT, RepeatControlSetting.fromKey(null), "a missing key")
		assertEquals(ParametersSettings.REPEAT_CONTROL_DEFAULT, RepeatControlSetting.fromKey("knob"), "a key from a newer build")
	}

	/**
	 * Settings over the bundled defaults and an empty in-memory config directory - a first run's.
	 *
	 * @return Settings The loaded settings.
	 */
	private fun bundledSettings(): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())

		return Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), runBlocking { defaultSettingsJson() })
	}
}