package org.umamo.ui.workspace.shell

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.FilePicker
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.defaultSettingsJson
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Keeps the keyboard alive across what can take focus away from the shell root while Preferences is open,
 * driven through the settings-backed shell with real clicks and real key events: Escape has to close
 * Preferences whatever the rigger picked or typed in it first.
 */
@OptIn(ExperimentalTestApi::class)
class ShellFocusReclaimTest {
	/** Nothing picked: Escape closes Preferences. */
	@Test
	fun escapeClosesPreferences() =
		runWithDefaultLocaleRestored {
			val registry = CommandRegistry()
			mount(this, registry)
			openPreferences(this, registry)
			pressEscape(this)
			assertEquals(0, onAllNodesWithText(SETTINGS_TITLE_ENGLISH).fetchSemanticsNodes().size, "Escape has to close Preferences")
		}

	/** A theme picked from its dropdown: Escape still closes Preferences. */
	@Test
	fun escapeClosesPreferencesAfterPickingATheme() =
		runWithDefaultLocaleRestored {
			val registry = CommandRegistry()
			mount(this, registry)
			openPreferences(this, registry)
			onNodeWithContentDescription(THEME_DARK_ENGLISH).performClick()
			waitForIdle()
			onNodeWithText(THEME_LIGHT_ENGLISH).performClick()
			waitForIdle()
			pressEscape(this)
			assertEquals(0, onAllNodesWithText(SETTINGS_TITLE_ENGLISH).fetchSemanticsNodes().size, "Escape has to close Preferences")
		}

	/** A language picked from its dropdown, which rebuilds everything under the locale: Escape still closes Preferences. */
	@Test
	fun escapeClosesPreferencesAfterPickingALanguage() =
		runWithDefaultLocaleRestored {
			val registry = CommandRegistry()
			mount(this, registry)
			openPreferences(this, registry)
			onNodeWithContentDescription(LANGUAGE_ENGLISH).performClick()
			waitForIdle()
			onNodeWithText(LANGUAGE_JAPANESE).performClick()
			waitForIdle()
			assertEquals(1, onAllNodesWithText(SETTINGS_TITLE_JAPANESE).fetchSemanticsNodes().size, "the pick has to switch the language with Preferences still open")
			pressEscape(this)
			assertEquals(0, onAllNodesWithText(SETTINGS_TITLE_JAPANESE).fetchSemanticsNodes().size, "Escape has to close Preferences")
		}

	/**
	 * A language picked while a field in Preferences is being edited: the switch rebuilds the field, and the
	 * focus it held with it, yet Escape still closes Preferences.
	 */
	@Test
	fun escapeClosesPreferencesAfterPickingALanguageMidEdit() =
		runWithDefaultLocaleRestored {
			val registry = CommandRegistry()
			mount(this, registry)
			openPreferences(this, registry)
			tapHistoryStepsField(this)
			onNodeWithContentDescription(LANGUAGE_ENGLISH).performClick()
			waitForIdle()
			onNodeWithText(LANGUAGE_JAPANESE).performClick()
			waitForIdle()
			assertEquals(1, onAllNodesWithText(SETTINGS_TITLE_JAPANESE).fetchSemanticsNodes().size, "the pick has to switch the language with Preferences still open")
			pressEscape(this)
			assertEquals(0, onAllNodesWithText(SETTINGS_TITLE_JAPANESE).fetchSemanticsNodes().size, "Escape has to close Preferences")
		}

	/**
	 * A value typed into a Preferences field and committed with Enter, which confirms by clearing the field's
	 * focus with Preferences still open: Escape still closes Preferences.
	 */
	@Test
	fun escapeClosesPreferencesAfterAnEnterCommit() =
		runWithDefaultLocaleRestored {
			val registry = CommandRegistry()
			mount(this, registry)
			openPreferences(this, registry)
			tapHistoryStepsField(this)
			onNode(hasSetTextAction()).performTextReplacement(HISTORY_STEPS_TYPED)
			onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			assertEquals(1, onAllNodesWithText(HISTORY_STEPS_TYPED, useUnmergedTree = true).fetchSemanticsNodes().size, "Enter has to commit the typed value")
			pressEscape(this)
			assertEquals(0, onAllNodesWithText(SETTINGS_TITLE_ENGLISH).fetchSemanticsNodes().size, "Escape has to close Preferences")
		}

	/** Enter on a Preferences field's unchanged value, which commits nothing but clears its focus the same way: Escape still closes Preferences. */
	@Test
	fun escapeClosesPreferencesAfterAnUnchangedEnter() =
		runWithDefaultLocaleRestored {
			val registry = CommandRegistry()
			mount(this, registry)
			openPreferences(this, registry)
			tapHistoryStepsField(this)
			onNode(hasSetTextAction()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()
			assertEquals(0, onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "Enter has to close the field")
			pressEscape(this)
			assertEquals(0, onAllNodesWithText(SETTINGS_TITLE_ENGLISH).fetchSemanticsNodes().size, "Escape has to close Preferences")
		}

	/**
	 * Runs a UI test and puts the process-wide default locale back afterwards, since a language switch sets it.
	 *
	 * @param Function block The test body.
	 */
	private fun runWithDefaultLocaleRestored(block: suspend ComposeUiTest.() -> Unit) {
		val defaultLocale = Locale.getDefault()
		try {
			runComposeUiTest { block() }
		} finally {
			Locale.setDefault(defaultLocale)
		}
	}

	/**
	 * Mounts the settings-backed shell over the bundled default settings and lets the root take focus.
	 *
	 * @param ComposeUiTest   test     The running UI test.
	 * @param CommandRegistry registry The registry the shell registers into.
	 */
	private fun mount(test: ComposeUiTest, registry: CommandRegistry) {
		val settings = bundledSettings()
		test.setContent {
			CompositionLocalProvider(LocalSettings provides settings) {
				PersistentEditorShell(commandRegistry = registry, filePicker = NoFilePicker)
			}
		}
		test.waitForIdle()
	}

	/**
	 * Opens Preferences the way its shortcut does, and checks it opened.
	 *
	 * @param ComposeUiTest   test     The running UI test.
	 * @param CommandRegistry registry The registry the shell registered into.
	 */
	private fun openPreferences(test: ComposeUiTest, registry: CommandRegistry) {
		test.runOnIdle { registry.invoke("edit.preferences") }
		test.waitForIdle()
		assertEquals(1, test.onAllNodesWithText(SETTINGS_TITLE_ENGLISH).fetchSemanticsNodes().size, "Preferences has to open")
	}

	/**
	 * Opens the History Steps field for typing with a tap, not a drag: the field scrubs on a horizontal drag and
	 * opens for typing on a tap.  The value is found in the unmerged tree, since the Preferences card merges its
	 * descendants into itself.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun tapHistoryStepsField(test: ComposeUiTest) {
		test.onNodeWithText(HISTORY_STEPS_VALUE, useUnmergedTree = true).performMouseInput {
			advanceEventTime(GESTURE_GAP_MILLIS)
			moveTo(center)
			press()
			advanceEventTime(GESTURE_STEP_MILLIS)
			release()
		}
		test.waitForIdle()
		assertEquals(1, test.onAllNodes(hasSetTextAction()).fetchSemanticsNodes().size, "the tap has to open the field for editing")
	}

	/**
	 * Presses and releases Escape in the window, where it reaches whichever node holds focus - or nothing.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun pressEscape(test: ComposeUiTest) {
		test.onRoot().performKeyInput { pressKey(Key.Escape) }
		test.waitForIdle()
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

	/** A file picker nothing in these cases opens. */
	private object NoFilePicker : FilePicker {
		override suspend fun openFile(extensions: List<String>): PlatformFile? = null

		override suspend fun saveFile(suggestedName: String, extension: String): PlatformFile? = null
	}

	private companion object {
		/** The Preferences window's title in English and in Japanese. */
		const val SETTINGS_TITLE_ENGLISH = "Settings"
		const val SETTINGS_TITLE_JAPANESE = "設定"

		/** The language dropdown's current value, which doubles as its accessible label, and the option picked. */
		const val LANGUAGE_ENGLISH = "English"
		const val LANGUAGE_JAPANESE = "日本語"

		/** The theme dropdown's current value, which doubles as its accessible label, and the option picked. */
		const val THEME_DARK_ENGLISH = "Dark"
		const val THEME_LIGHT_ENGLISH = "Light"

		/** The History Steps field's value under the bundled defaults, and the one a case types over it. */
		const val HISTORY_STEPS_VALUE = "200"
		const val HISTORY_STEPS_TYPED = "150"

		/** The time between two pointer events of one gesture, and between two separate gestures. */
		const val GESTURE_STEP_MILLIS = 16L
		const val GESTURE_GAP_MILLIS = 1_000L
	}
}