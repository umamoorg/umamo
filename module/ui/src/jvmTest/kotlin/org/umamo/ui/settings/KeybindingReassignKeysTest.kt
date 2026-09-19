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
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.kit.ConfirmDialog
import org.umamo.ui.kit.KeyCaptureController
import org.umamo.ui.kit.LocalKeyCapture
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.resources.cmd_preferences
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ShellModalState
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.handleModalKeyLadder
import org.umamo.ui.workspace.toShellKeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the keybindings editor with real key events under the shell's real modal ladder, the way it sits inside
 * Preferences: the ladder is the root's preview handler, so it sees every key before the editor does.
 */
@OptIn(ExperimentalTestApi::class)
class KeybindingReassignKeysTest {
	private val defaultJson = """{"input":{"keybinding":{"preset":"default","overrides":{}}}}"""

	/**
	 * Mounts the editor under a root that routes keys through the modal ladder, with Preferences marked open.
	 *
	 * @param ComposeUiTest   test     The running UI test.
	 * @param ShellOverlayState overlays The shell's overlay state.
	 * @param MutableList     strokes  Receives each key stroke the root sees, for the failure message.
	 * @return Settings The settings the editor writes its rebinds into.
	 */
	private fun mount(test: ComposeUiTest, overlays: ShellOverlayState, strokes: MutableList<String>): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())
		val settings = Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), defaultJson)
		val registry = CommandRegistry()
		registry.registerAll(
			listOf(
				Command("test.bound", title = Res.string.cmd_mesh_grab) {},
				Command("test.unbound", title = Res.string.cmd_preferences) {},
				Command("document.confirm", title = null) { argument -> (argument as? ConfirmRequest)?.let { request -> overlays.pendingConfirm = request } },
			),
		)
		val keymap = Keymap(mapOf(parseKeyChord("primary+KeyS")!! to "test.bound"))
		val rootFocus = FocusRequester()
		// One controller shared by the ladder and the editor, as the shell wires it.
		val keyCapture = KeyCaptureController()
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
									val stroke = event.toShellKeyStroke()
									strokes += "${stroke.key} down=${stroke.isDown} confirm=${overlays.pendingConfirm != null}"
									handleModalKeyLadder(stroke, ShellModalState(overlays = overlays, keyCapture = keyCapture, commandRegistry = registry, keymap = keymap))
								},
					) {
						if (overlays.settingsVisible) {
							KeybindingsEditor()
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
		return settings
	}

	/**
	 * Arms the unbound command's chip and presses Ctrl+S, which the other command already holds, so the reassign
	 * prompt is up.
	 *
	 * @param ComposeUiTest     test     The running UI test.
	 * @param ShellOverlayState overlays The shell's overlay state.
	 * @param List              strokes  The strokes seen so far, for the failure message.
	 */
	private fun raiseTheReassignPrompt(test: ComposeUiTest, overlays: ShellOverlayState, strokes: List<String>) {
		test.onNodeWithText("Unbound").performClick()
		test.waitForIdle()
		test.onNodeWithTag("root").performKeyInput {
			keyDown(Key.CtrlLeft)
			keyDown(Key.S)
			keyUp(Key.S)
			keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
		assertNotNull(overlays.pendingConfirm, "the taken chord raises the reassign prompt; strokes: $strokes")
	}

	/**
	 * Enter on the reassign prompt reassigns the chord and leaves Preferences open.  The prompt only hears Enter
	 * at all because the chip keeps its focus node once the capture ends: with focus on nothing, no key reaches
	 * the shell.
	 */
	@Test
	fun enterOnTheReassignPromptReassignsAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)
			raiseTheReassignPrompt(this, overlays, strokes)

			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Enter)
				keyUp(Key.Enter)
			}
			waitForIdle()

			assertNull(overlays.pendingConfirm, "Enter answers the prompt; strokes: $strokes")
			assertTrue(overlays.settingsVisible, "and Preferences stays open; strokes: $strokes")
			assertEquals("test.unbound", loadKeymap(settings).commandFor(parseKeyChord("primary+KeyS")!!), "with the chord reassigned")
		}
	}

	/** Escape on the reassign prompt cancels the prompt alone: the confirm outranks the overlay it was raised over. */
	@Test
	fun escapeOnTheReassignPromptCancelsItAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)
			raiseTheReassignPrompt(this, overlays, strokes)

			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()

			assertNull(overlays.pendingConfirm, "Escape cancels the prompt; strokes: $strokes")
			assertTrue(overlays.settingsVisible, "and Preferences stays open; strokes: $strokes")
			assertNotEquals("test.unbound", loadKeymap(settings).commandFor(parseKeyChord("primary+KeyS")!!), "with nothing rebound: no override was written")
		}
	}

	/**
	 * Escape while the chip is waiting for a key cancels the capture, not Preferences.  The shell's root handler
	 * previews every key before the chip does, so without the capture announced to it the overlay arm takes the
	 * Escape and closes Preferences behind the chip.
	 */
	@Test
	fun escapeWhileCapturingCancelsTheCaptureAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			mount(this, overlays, strokes)

			onNodeWithText("Unbound").performClick()
			waitForIdle()
			onNodeWithText("Press a shortcut…").assertExists()
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()

			assertTrue(overlays.settingsVisible, "Escape cancels the capture, not Preferences; strokes: $strokes")
			onNodeWithText("Unbound").assertExists()
			onNodeWithText("Press a shortcut…").assertDoesNotExist()

			// With the capture over, Escape is the overlay's again.
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()
			assertFalse(overlays.settingsVisible, "a second Escape closes Preferences; strokes: $strokes")
		}
	}
}