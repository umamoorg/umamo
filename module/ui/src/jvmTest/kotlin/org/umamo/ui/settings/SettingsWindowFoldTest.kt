package org.umamo.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.jetbrains.compose.resources.stringResource
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_category_viewport
import org.umamo.ui.resources.settings_interface_theme
import org.umamo.ui.resources.settings_section_display
import org.umamo.ui.resources.settings_viewport_show_by_default
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The preferences window's cards under real pointer input: a header click folds its card, the fold is written to
 * settings at once, and it is still folded when the window opens again.
 *
 * Everything inside the window is looked up in the unmerged tree: the card's click-swallowing clickable merges its
 * descendants, so a merged lookup resolves to the whole card.
 */
@OptIn(ExperimentalTestApi::class)
class SettingsWindowFoldTest {
	/** Folding the Interface page's Display card hides its rows, persists, and survives closing and reopening. */
	@Test
	fun aFoldedCardStaysFoldedAcrossReopening() =
		runComposeUiTest {
			val settings = bundledSettings()
			val labels = mutableMapOf<String, String>()
			var open by mutableStateOf(true)
			setContent {
				labels["display"] = stringResource(Res.string.settings_section_display)
				labels["theme"] = stringResource(Res.string.settings_interface_theme)
				UmamoTheme {
					CompositionLocalProvider(LocalSettings provides settings) {
						Box(modifier = Modifier.size(width = 1000.dp, height = 800.dp)) {
							if (open) {
								SettingsWindow(onDismiss = {})
							}
						}
					}
				}
			}
			waitForIdle()
			onNodeWithText(labels.getValue("theme"), useUnmergedTree = true).assertExists()

			onNodeWithText(labels.getValue("display"), useUnmergedTree = true).performClick()
			waitForIdle()

			onNodeWithText(labels.getValue("theme"), useUnmergedTree = true).assertDoesNotExist()
			assertEquals(setOf(PreferencesSection.InterfaceDisplay.id), collapsedSectionIdsOf(settings))

			open = false
			waitForIdle()
			open = true
			waitForIdle()

			onNodeWithText(labels.getValue("theme"), useUnmergedTree = true).assertDoesNotExist()

			onNodeWithText(labels.getValue("display"), useUnmergedTree = true).performClick()
			waitForIdle()

			onNodeWithText(labels.getValue("theme"), useUnmergedTree = true).assertExists()
			assertEquals(emptySet(), collapsedSectionIdsOf(settings))
		}

	/**
	 * Two pages' cards sharing a title fold apart: the Interface page's Display card folded leaves the Viewport
	 * page's Display card open.
	 */
	@Test
	fun cardsSharingATitleFoldApart() =
		runComposeUiTest {
			val settings = bundledSettings()
			val labels = mutableMapOf<String, String>()
			setContent {
				labels["display"] = stringResource(Res.string.settings_section_display)
				labels["viewport"] = stringResource(Res.string.settings_category_viewport)
				labels["showByDefault"] = stringResource(Res.string.settings_viewport_show_by_default)
				UmamoTheme {
					CompositionLocalProvider(LocalSettings provides settings) {
						Box(modifier = Modifier.size(width = 1000.dp, height = 800.dp)) {
							SettingsWindow(onDismiss = {})
						}
					}
				}
			}
			waitForIdle()
			onNodeWithText(labels.getValue("display"), useUnmergedTree = true).performClick()
			waitForIdle()

			onNodeWithText(labels.getValue("viewport"), useUnmergedTree = true).performClick()
			waitForIdle()

			onNodeWithText(labels.getValue("showByDefault"), useUnmergedTree = true).assertExists()
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