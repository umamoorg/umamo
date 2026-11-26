package org.umamo.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isFocused
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.jetbrains.compose.resources.getString
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.settings_colors_lattice_back_facing_opacity
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.ViewportColorSettings
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Colors page's Back-Facing Opacity row: it shows the stored opacity as a whole percent, and a typed percent is
 * stored as 0 to 1.
 */
@OptIn(ExperimentalTestApi::class)
class BackFacingOpacitySettingTest {
	@Test
	fun theRowShowsAndWritesTheOpacityAsAPercent() =
		runComposeUiTest {
			val settings = bundledSettings()
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalSettings provides settings) {
						Column(modifier = Modifier.size(width = 1000.dp, height = 4000.dp)) {
							ColorsSection(PreferencesFolds(settings))
						}
					}
				}
			}
			waitForIdle()
			onNodeWithText(getString(Res.string.settings_colors_lattice_back_facing_opacity), useUnmergedTree = true).assertExists()

			onNodeWithText("30", useUnmergedTree = true).performClick()
			waitForIdle()
			onNode(hasSetTextAction() and isFocused()).performTextReplacement("55")
			onNode(hasSetTextAction() and isFocused()).performKeyInput { pressKey(Key.Enter) }
			waitForIdle()

			assertEquals(0.55, settings.getDouble(ViewportColorSettings.LATTICE_BACK_FACING_OPACITY_KEY))
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