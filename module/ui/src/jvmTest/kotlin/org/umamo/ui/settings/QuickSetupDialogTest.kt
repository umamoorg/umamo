package org.umamo.ui.settings

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onFirst
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.jetbrains.compose.resources.stringResource
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.l10n.LOCALE_SETTINGS_KEY
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.quick_setup_continue
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Quick Setup modal under real pointer input: its choices write through to settings as they are picked,
 * and it closes from Continue or the scrim - never from a press on its own card.
 */
@OptIn(ExperimentalTestApi::class)
class QuickSetupDialogTest {
	private val defaultJson = """{"localization":{"locale":"en"},"interface":{"theme":"dark"},"input":{"keybinding":{"preset":"default","overrides":{}}}}"""

	/** Picking a language from the row's dropdown writes it to settings at once, with nothing to commit. */
	@Test
	fun pickingALanguageWritesItThrough() =
		runComposeUiTest {
			val settings = freshSettings()
			mount(this, settings) {}
			onAllNodesWithText("English", useUnmergedTree = true).onFirst().performClick()
			onNodeWithText("日本語", useUnmergedTree = true).performClick()
			waitForIdle()
			assertEquals("ja", settings.getString(LOCALE_SETTINGS_KEY))
		}

	/** Continue closes the modal. */
	@Test
	fun continueDismisses() =
		runComposeUiTest {
			var dismissCount = 0
			var continueLabel = ""
			mount(this, freshSettings(), onContinueLabel = { label -> continueLabel = label }) { dismissCount++ }
			onNodeWithText(continueLabel).performClick()
			waitForIdle()
			assertEquals(1, dismissCount)
		}

	/** A press on the scrim closes it; a press on the card does not. */
	@Test
	fun onlyTheScrimDismisses() =
		runComposeUiTest {
			var dismissCount = 0
			mount(this, freshSettings()) { dismissCount++ }
			// The card is centered at no more than 560dp wide, so the window's middle is card and its corner is scrim.
			onNodeWithTag(HOST_TAG).performMouseInput { click(center) }
			waitForIdle()
			assertEquals(0, dismissCount, "a press on the card is not a dismissal")
			onNodeWithTag(HOST_TAG).performMouseInput { click(Offset(4f, 4f)) }
			waitForIdle()
			assertEquals(1, dismissCount, "a press on the scrim is")
		}

	/**
	 * A wide window caps the card at 560dp: the space beside it inside the 90% band is scrim, not card.  The cap
	 * is the case worth pinning - with the width modifiers in the wrong order the card silently fills 90%.
	 */
	@Test
	fun aWideWindowCapsTheCard() =
		runComposeUiTest {
			var dismissCount = 0
			mount(this, freshSettings(), hostWidth = 900.dp) { dismissCount++ }
			// The 900dp host's 90% band starts at 45dp; the capped 560dp card starts at 170dp.
			onNodeWithTag(HOST_TAG).performMouseInput { click(Offset(200.dp.toPx(), centerY)) }
			waitForIdle()
			assertEquals(0, dismissCount, "inside the capped card")
			onNodeWithTag(HOST_TAG).performMouseInput { click(Offset(100.dp.toPx(), centerY)) }
			waitForIdle()
			assertEquals(1, dismissCount, "beside the capped card, still inside the 90% band, is scrim")
		}

	/** A narrow window gives the card 90% of its width, under the cap. */
	@Test
	fun aNarrowWindowGivesTheCardNinetyPercent() =
		runComposeUiTest {
			var dismissCount = 0
			mount(this, freshSettings(), hostWidth = 400.dp) { dismissCount++ }
			// The 400dp host's card spans 20dp to 380dp.
			onNodeWithTag(HOST_TAG).performMouseInput { click(Offset(30.dp.toPx(), centerY)) }
			waitForIdle()
			assertEquals(0, dismissCount, "the card reaches past the 5% margin")
			onNodeWithTag(HOST_TAG).performMouseInput { click(Offset(10.dp.toPx(), centerY)) }
			waitForIdle()
			assertEquals(1, dismissCount, "and the margin is scrim")
		}

	/**
	 * Settings over an empty in-memory config directory - a first run's.
	 *
	 * @return Settings The loaded settings.
	 */
	private fun freshSettings(): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())
		return Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), defaultJson)
	}

	/**
	 * Mounts the modal over [settings] in a window-sized themed box.
	 *
	 * @param ComposeUiTest test            The running UI test.
	 * @param Settings      settings        The settings the rows write through to.
	 * @param Dp            hostWidth       The stand-in window's width.
	 * @param Function      onContinueLabel Receives the localized Continue label, resolved in composition.
	 * @param Function      onDismiss       The modal's dismissal.
	 */
	private fun mount(
		test: ComposeUiTest,
		settings: Settings,
		hostWidth: Dp = 900.dp,
		onContinueLabel: (String) -> Unit = {},
		onDismiss: () -> Unit,
	) {
		test.setContent {
			onContinueLabel(stringResource(Res.string.quick_setup_continue))
			UmamoTheme {
				CompositionLocalProvider(LocalSettings provides settings) {
					Box(modifier = Modifier.size(width = hostWidth, height = 700.dp).testTag(HOST_TAG)) {
						QuickSetupDialog(onDismiss = onDismiss)
					}
				}
			}
		}
	}

	private companion object {
		const val HOST_TAG = "host"
	}
}