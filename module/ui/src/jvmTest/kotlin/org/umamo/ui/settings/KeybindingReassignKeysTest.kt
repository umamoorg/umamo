package org.umamo.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onAllNodesWithContentDescription
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
import org.umamo.ui.action.setPrimaryBinding
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
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Drives the shortcut popup with real key events under the shell's real modal ladder, the way it sits inside
 * Preferences: the ladder is the root's preview handler, so it sees every key before the popup does.
 */
@OptIn(ExperimentalTestApi::class)
class KeybindingReassignKeysTest {
	private val defaultJson = """{"input":{"keybinding":{"preset":"default","overrides":{}}}}"""

	/** The editor's controller, so a test can open the popup without the click that opens it. */
	private lateinit var shortcutEditor: ShortcutEditorController

	/**
	 * Mounts the panel and the popup under a root that routes keys through the modal ladder, with Preferences
	 * marked open.
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
		val keymap = Keymap(mapOf(parseKeyChord("primary+KeyS")!! to "test.bound", parseKeyChord("F5")!! to "test.unbound"))
		val rootFocus = FocusRequester()
		// One controller shared by the ladder and the editor, as the shell wires it.
		val keyCapture = KeyCaptureController()
		val shortcutEditor = ShortcutEditorController()
		this.shortcutEditor = shortcutEditor
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(
					LocalSettings provides settings,
					LocalCommands provides registry,
					LocalKeymap provides keymap,
					LocalKeyCapture provides keyCapture,
					LocalShortcutEditor provides shortcutEditor,
				) {
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
									handleModalKeyLadder(
										stroke,
										ShellModalState(
											overlays = overlays,
											keyCapture = keyCapture,
											commandRegistry = registry,
											keymap = keymap,
											shortcutEditor = shortcutEditor,
										),
									)
								},
					) {
						// The shell root holds the keyboard while nothing else does, so a key reaches the
						// ladder; a control that takes focus (the popup's key view) takes it over.
						LaunchedEffect(Unit) {
							rootFocus.requestFocus()
						}

						if (overlays.settingsVisible) {
							KeybindingsEditor()
							shortcutEditor.request?.let { request ->
								ShortcutBindingPopup(request = request, onDismiss = { shortcutEditor.close() })
							}
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
	 * Clicking a binding cell opens the popup for that cell's slot.
	 */
	@Test
	fun clickingACellOpensThePopup() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			mount(this, overlays, mutableListOf())

			// The bound command's primary cell draws its key caps: "Ctrl" and "S".
			onNodeWithText("S").performClick()
			waitForIdle()

			assertEquals("test.bound", shortcutEditor.request?.commandId)
			assertEquals(ShortcutSlot.Primary, shortcutEditor.request?.slot)
		}
	}

	/**
	 * Escape closes the popup, and leaves Preferences open behind it.
	 */
	@Test
	fun escapeClosesThePopupAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			mount(this, overlays, strokes)
			shortcutEditor.open("test.unbound", ShortcutSlot.Primary)
			waitForIdle()

			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()

			assertNull(shortcutEditor.request, "Escape closes the popup; strokes: $strokes")
			assertTrue(overlays.settingsVisible, "and Preferences stays open; strokes: $strokes")
		}
	}

	/**
	 * Arms the popup's capture and presses Ctrl+S, which the other command already holds, then saves - so
	 * the reassign prompt is up.
	 *
	 * @param ComposeUiTest     test     The running UI test.
	 * @param ShellOverlayState overlays The shell's overlay state.
	 * @param List              strokes  The strokes seen so far, for the failure message.
	 */
	private fun raiseTheReassignPrompt(test: ComposeUiTest, overlays: ShellOverlayState, strokes: List<String>) {
		shortcutEditor.open("test.unbound", ShortcutSlot.Primary)
		test.waitForIdle()

		test.onNodeWithTag("root").performKeyInput {
			keyDown(Key.CtrlLeft)
			keyDown(Key.S)
			keyUp(Key.S)
			keyUp(Key.CtrlLeft)
		}
		test.waitForIdle()
		test.onNodeWithText("Save Changes").performClick()
		test.waitForIdle()
		assertNotNull(overlays.pendingConfirm, "the taken chord raises the reassign prompt; strokes: $strokes")
	}

	/**
	 * Enter on the reassign prompt reassigns the chord and leaves Preferences open.
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

	/**
	 * Escape on the reassign prompt cancels the prompt alone: the confirm outranks the overlay it was raised over.
	 */
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
	 * Saving a chord the command does not hold yet binds it, without a prompt.
	 */
	@Test
	fun savingAnUnheldChordBindsItWithoutAPrompt() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)
			shortcutEditor.open("test.unbound", ShortcutSlot.Primary)
			waitForIdle()

			onNodeWithTag("root").performKeyInput {
				keyDown(Key.F5)
				keyUp(Key.F5)
			}
			waitForIdle()
			onNodeWithText("Save Changes").performClick()
			waitForIdle()

			assertNull(overlays.pendingConfirm, "an unheld chord is not a conflict; strokes: $strokes")
			assertEquals("test.unbound", loadKeymap(settings).commandFor(parseKeyChord("F5")!!), "with the chord saved")
		}
	}

	/**
	 * Reassigning a chord marks the command that lost it, so its row stays red until it is given another
	 * binding - the user can see which setting gave its shortcut away.
	 */
	@Test
	fun theReassignMarksTheCommandThatLostItsBinding() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)

			shortcutEditor.open("test.unbound", ShortcutSlot.Primary)
			waitForIdle()
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.CtrlLeft)
				keyDown(Key.S)
				keyUp(Key.S)
				keyUp(Key.CtrlLeft)
			}
			waitForIdle()
			onNodeWithText("Save Changes").performClick()
			waitForIdle()
			assertNotNull(overlays.pendingConfirm, "the taken chord raises the prompt; strokes=$strokes")

			onNodeWithText("Reassign").performClick()
			waitForIdle()

			assertEquals("test.bound", shortcutEditor.conflicted?.commandId, "the command that lost the chord is marked")
			assertEquals("test.unbound", loadKeymap(settings).commandFor(parseKeyChord("primary+KeyS")!!), "and the chord moved")
		}
	}

	/**
	 * Giving the marked command a binding clears the mark.
	 */
	@Test
	fun savingABindingClearsTheMark() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)

			shortcutEditor.markConflicted("test.bound", ShortcutSlot.Primary)
			shortcutEditor.open("test.bound", ShortcutSlot.Primary)
			waitForIdle()
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.F9)
				keyUp(Key.F9)
			}
			waitForIdle()
			onNodeWithText("Save Changes").performClick()
			waitForIdle()

			assertNull(shortcutEditor.conflicted, "the mark is cleared once the command has a binding again")
			assertEquals("test.bound", loadKeymap(settings).commandFor(parseKeyChord("F9")!!), "and the binding is saved")
		}
	}

	/**
	 * Resetting a slot from its row button puts the preset's chord back.
	 */
	@Test
	fun resettingASlotRestoresThePresetChord() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)
			setPrimaryBinding(settings, "test.unbound", parseKeyChord("F6"))

			// A slot with no binding renders no buttons, so only the two commands that hold a chord have a
			// reset button - the second of them is this command.
			onAllNodesWithContentDescription("Reset this shortcut")[1].performClick()
			waitForIdle()

			assertNull(loadKeymap(settings).slotsFor("test.unbound").primary, "with the slot back at its default")
		}
	}
}