package org.umamo.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.KeymapCategory
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.kit.dialog.ConfirmDialog
import org.umamo.ui.kit.textentry.KeyCaptureController
import org.umamo.ui.kit.textentry.LocalKeyCapture
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.resources.cmd_preferences
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.shell.ShellModalState
import org.umamo.ui.workspace.shell.handleModalKeyLadder
import org.umamo.ui.workspace.shell.toShellKeyStroke
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Drives the keybindings page's search with real input under the shell's real modal ladder: a typed query opens a
 * folded card and hides what does not match, and the record button looks a pressed chord up without the ladder
 * taking the press for Preferences.
 *
 * Two commands sit on two cards: Grab Vertices under Transform, bound to Ctrl+S, and Settings under Edit, unbound.
 */
@OptIn(ExperimentalTestApi::class)
class KeybindingsSearchUiTest {
	/**
	 * Mounts the page under a root that routes keys through the modal ladder, with Preferences marked open.
	 *
	 * @param ComposeUiTest     test      The running UI test.
	 * @param ShellOverlayState overlays  The shell's overlay state.
	 * @param KeyCaptureController keyCapture The capture controller the ladder and the page share.
	 * @param Function          beforeMount Prepares the settings before the page reads them (a fold, say).
	 * @return Settings The settings the page reads and writes.
	 */
	private fun mount(
		test: ComposeUiTest,
		overlays: ShellOverlayState,
		keyCapture: KeyCaptureController = KeyCaptureController(),
		beforeMount: (Settings) -> Unit = {},
	): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())
		val settings = Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), runBlocking { defaultSettingsJson() })
		beforeMount(settings)
		val registry = CommandRegistry()
		registry.registerAll(
			listOf(
				Command("test.grab", title = Res.string.cmd_mesh_grab, keymapCategory = KeymapCategory.Transform) {},
				Command("test.settings", title = Res.string.cmd_preferences, keymapCategory = KeymapCategory.Edit) {},
				Command("document.confirm", title = null) { argument -> (argument as? ConfirmRequest)?.let { request -> overlays.pendingConfirm = request } },
			),
		)
		val keymap = Keymap(mapOf(parseKeyChord("primary+KeyS")!! to "test.grab"))
		val rootFocus = FocusRequester()
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalSettings provides settings, LocalCommands provides registry, LocalKeymap provides keymap, LocalKeyCapture provides keyCapture) {
					Box(
						modifier =
							Modifier
								.fillMaxSize()
								.testTag("root")
								.focusRequester(rootFocus)
								.focusable()
								.onPreviewKeyEvent { event ->
									handleModalKeyLadder(event.toShellKeyStroke(), ShellModalState(overlays = overlays, keyCapture = keyCapture, commandRegistry = registry, keymap = keymap))
								},
					) {
						if (overlays.settingsVisible) {
							KeybindingsEditor(rememberPreferencesFolds())
						}

						overlays.pendingConfirm?.let {
							ConfirmDialog(
								message = "reassign?",
								onConfirm = { overlays.confirmPending() },
								onCancel = { overlays.cancelPending() },
								confirmLabel = "Reassign",
								cancelLabel = "Cancel",
							)
						}
					}
				}
			}
		}
		test.waitForIdle()
		rootFocus.requestFocus()
		test.waitForIdle()

		return settings
	}

	/**
	 * Presses Ctrl+S.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun pressControlS(test: ComposeUiTest) {
		test.onNodeWithTag("root").performKeyInput {
			keyDown(Key.CtrlLeft)
			keyDown(Key.S)
			keyUp(Key.S)
			keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
	}

	/** A typed query forces the folded card it matches open and hides every row that does not match. */
	@Test
	fun aQueryOpensAFoldedCardAndHidesTheRest() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			mount(this, overlays) { settings -> PreferencesFolds(settings).toggle(keybindingFoldId(KeymapCategory.Transform)) }

			onNodeWithText("Grab Vertices", useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText("Settings", useUnmergedTree = true).assertExists()

			onNode(hasSetTextAction()).performTextInput("grab")
			waitForIdle()

			onNodeWithText("Grab Vertices", useUnmergedTree = true).assertExists()
			onNodeWithText("Settings", useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText("Edit", useUnmergedTree = true).assertDoesNotExist()

			onNode(hasSetTextAction()).performTextReplacement("")
			waitForIdle()

			onNodeWithText("Grab Vertices", useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText("Settings", useUnmergedTree = true).assertExists()
		}
	}

	/** A typed shortcut finds the command bound to it. */
	@Test
	fun aQueryMatchesAShownShortcut() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			mount(this, overlays)

			onNode(hasSetTextAction()).performTextInput("ctrl+s")
			waitForIdle()

			onNodeWithText("Grab Vertices", useUnmergedTree = true).assertExists()
			onNodeWithText("Settings", useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** The record button takes the next press as the chord to look up, and the page narrows to what it runs. */
	@Test
	fun aRecordedChordNarrowsToTheCommandItRuns() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val keyCapture = KeyCaptureController()
			mount(this, overlays, keyCapture)

			onNodeWithContentDescription("Search by pressing a shortcut", useUnmergedTree = true).performClick()
			waitForIdle()
			assertTrue(keyCapture.active, "the record button announces its capture to the ladder")

			pressControlS(this)

			assertFalse(keyCapture.active, "a recorded chord ends the capture")
			assertTrue(overlays.settingsVisible)
			onNodeWithText("Grab Vertices", useUnmergedTree = true).assertExists()
			onNodeWithText("Settings", useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** Escape while recording cancels the recording, not Preferences, and leaves the list as it was. */
	@Test
	fun escapeWhileRecordingCancelsItAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val keyCapture = KeyCaptureController()
			mount(this, overlays, keyCapture)

			onNodeWithContentDescription("Search by pressing a shortcut", useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()

			assertFalse(keyCapture.active)
			assertTrue(overlays.settingsVisible, "Escape cancels the recording, not Preferences")
			onNodeWithText("Grab Vertices", useUnmergedTree = true).assertExists()
			onNodeWithText("Settings", useUnmergedTree = true).assertExists()
		}
	}

	/** Arming a row's chip ends a recording, so the next press rebinds the row rather than searching. */
	@Test
	fun armingAChipEndsARecording() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			mount(this, overlays)

			onNodeWithContentDescription("Search by pressing a shortcut", useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithText("Unbound", useUnmergedTree = true).performClick()
			waitForIdle()

			pressControlS(this)

			assertNotNull(overlays.pendingConfirm, "the chip took the press, and Grab Vertices already holds it")
			onNodeWithText("Settings", useUnmergedTree = true).assertExists()
		}
	}
}