package org.umamo.ui.settings

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.isEnabled
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.v2.runComposeUiTest
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.jetbrains.compose.resources.stringResource
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.LocalSettings
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.KeyChord
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.addCommandBind
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.action.loadKeymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.action.rememberLiveKeymap
import org.umamo.ui.kit.dialog.ConfirmDialog
import org.umamo.ui.kit.textentry.KeyCaptureController
import org.umamo.ui.kit.textentry.LocalKeyCapture
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.resources.cmd_preferences
import org.umamo.ui.resources.settings_keybindings_add
import org.umamo.ui.resources.settings_keybindings_press_shortcut
import org.umamo.ui.resources.settings_keybindings_remove
import org.umamo.ui.resources.settings_keybindings_unbound
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.shell.ShellModalState
import org.umamo.ui.workspace.shell.handleModalKeyLadder
import org.umamo.ui.workspace.shell.toShellKeyStroke
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

	/** What an unbound command's chip reads, as the running locale renders it. */
	private var unboundLabel = ""

	/** What an armed chip reads, as the running locale renders it. */
	private var pressShortcutLabel = ""

	/** The add button's label, as the running locale renders it. */
	private var addLabel = ""

	/** The remove button's label, as the running locale renders it. */
	private var removeLabel = ""

	/**
	 * Parses a chord spec the test knows is valid.
	 *
	 * @param String spec The chord spec.
	 * @return KeyChord The chord.
	 */
	private fun chord(spec: String): KeyChord = checkNotNull(parseKeyChord(spec)) { "spec $spec parses" }

	/**
	 * Mounts the editor under a root that routes keys through the modal ladder, with Preferences marked open.  The
	 * keymap is the live one over the settings, so an edit shows on the page as it does in the app; the bound command
	 * starts with Ctrl+S, the other with nothing.
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
		addCommandBind(settings, "test.bound", chord("primary+KeyS"))
		val rootFocus = FocusRequester()
		// One controller shared by the ladder and the editor, as the shell wires it.
		val keyCapture = KeyCaptureController()
		test.setContent {
			unboundLabel = stringResource(Res.string.settings_keybindings_unbound)
			pressShortcutLabel = stringResource(Res.string.settings_keybindings_press_shortcut)
			addLabel = stringResource(Res.string.settings_keybindings_add)
			removeLabel = stringResource(Res.string.settings_keybindings_remove)
			val keymap by rememberLiveKeymap(settings)
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
		test.onNodeWithText(unboundLabel).performClick()
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
	 * at all because the page's key sink keeps the keyboard once the capture ends: with focus on nothing, no key
	 * reaches the shell.
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

			onNodeWithText(unboundLabel).performClick()
			waitForIdle()
			onNodeWithText(pressShortcutLabel).assertExists()
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()

			assertTrue(overlays.settingsVisible, "Escape cancels the capture, not Preferences; strokes: $strokes")
			onNodeWithText(unboundLabel).assertExists()
			onNodeWithText(pressShortcutLabel).assertDoesNotExist()

			// With the capture over, Escape is the overlay's again.
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()
			assertFalse(overlays.settingsVisible, "a second Escape closes Preferences; strokes: $strokes")
		}
	}

	/**
	 * A click into the search field while a chip waits for a key ends the capture: the field has the keyboard, so
	 * a capture left armed would hear nothing while the ladder went on standing aside for it, and Escape would do
	 * nothing at all.
	 */
	@Test
	fun aClickIntoTheSearchFieldDisarmsAChip() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			mount(this, overlays, strokes)

			onNodeWithText(unboundLabel).performClick()
			waitForIdle()
			onNodeWithText(pressShortcutLabel).assertExists()

			onNode(hasSetTextAction()).performClick()
			waitForIdle()

			onNodeWithText(pressShortcutLabel).assertDoesNotExist()
			onNodeWithText(unboundLabel).assertExists()

			// With the capture over, Escape is the overlay's again.
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()
			assertFalse(overlays.settingsVisible, "Escape closes Preferences; strokes: $strokes")
		}
	}

	/**
	 * Presses one key, with no modifier.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Key key The key.
	 */
	private fun press(test: ComposeUiTest, key: Key) {
		test.onNodeWithTag("root").performKeyInput {
			keyDown(key)
			keyUp(key)
		}
		test.waitForIdle()
	}

	/**
	 * Clicks the bound command's add button: its rows come first, and an unbound command's add does not apply.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun clickAdd(test: ComposeUiTest) {
		test.onAllNodes(hasContentDescription(addLabel) and isEnabled(), useUnmergedTree = true)[0].performClick()
		test.waitForIdle()
	}

	/** The add button arms a chip under the command's last chord, and a press puts one more chord there. */
	@Test
	fun theAddButtonGivesACommandAnotherChord() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val settings = mount(this, overlays, mutableListOf())

			clickAdd(this)
			onNodeWithText(pressShortcutLabel, useUnmergedTree = true).assertExists()
			press(this, Key.F5)

			assertEquals(listOf(chord("primary+KeyS"), chord("F5")), loadKeymap(settings).chordsFor("test.bound"))
			onNodeWithText(formatAccelerator(chord("primary+KeyS")), useUnmergedTree = true).assertExists()
			onNodeWithText(formatAccelerator(chord("F5")), useUnmergedTree = true).assertExists()
			onNodeWithText(pressShortcutLabel, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** A press on a chord's chip puts the new chord in that chord's place, so the first stays first. */
	@Test
	fun replacingTheFirstChordKeepsItsPlace() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val settings = mount(this, overlays, mutableListOf())
			clickAdd(this)
			press(this, Key.F5)

			onNodeWithText(formatAccelerator(chord("primary+KeyS")), useUnmergedTree = true).performClick()
			waitForIdle()
			press(this, Key.F6)

			assertEquals(listOf(chord("F6"), chord("F5")), loadKeymap(settings).chordsFor("test.bound"))
		}
	}

	/** A chord's remove button takes that chord alone, and the command keeps the rest. */
	@Test
	fun removingOneChordLeavesTheOther() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val settings = mount(this, overlays, mutableListOf())
			clickAdd(this)
			press(this, Key.F5)

			// The bound command's two rows come first; the unbound command's remove button does not apply.
			onAllNodes(hasContentDescription(removeLabel) and isEnabled(), useUnmergedTree = true)[0].performClick()
			waitForIdle()

			assertEquals(listOf(chord("F5")), loadKeymap(settings).chordsFor("test.bound"))
			onNodeWithText(formatAccelerator(chord("primary+KeyS")), useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(formatAccelerator(chord("F5")), useUnmergedTree = true).assertExists()
		}
	}

	/** Escape on an armed add takes its row away and leaves Preferences open and the chords as they were. */
	@Test
	fun escapeOnAnArmedAddRemovesItsRowAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)

			clickAdd(this)
			onNodeWithText(pressShortcutLabel, useUnmergedTree = true).assertExists()
			press(this, Key.Escape)

			assertTrue(overlays.settingsVisible, "Escape cancels the add, not Preferences; strokes: $strokes")
			onNodeWithText(pressShortcutLabel, useUnmergedTree = true).assertDoesNotExist()
			assertEquals(listOf(chord("primary+KeyS")), loadKeymap(settings).chordsFor("test.bound"))
		}
	}

	/**
	 * An add onto a chord another command holds raises the reassign prompt, and Enter on it moves the chord with
	 * Preferences still open.  The armed row leaves the list as the prompt rises, which a chip holding the keyboard
	 * itself would have taken focus along with, and Enter would have reached nothing.
	 */
	@Test
	fun anAddOntoATakenChordRaisesThePromptAndEnterMovesIt() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			val settings = mount(this, overlays, strokes)
			onNodeWithText(unboundLabel).performClick()
			waitForIdle()
			press(this, Key.F7)
			assertEquals(listOf(chord("F7")), loadKeymap(settings).chordsFor("test.unbound"), "fixture: the other command holds F7")

			clickAdd(this)
			press(this, Key.F7)
			assertNotNull(overlays.pendingConfirm, "the taken chord raises the reassign prompt; strokes: $strokes")
			press(this, Key.Enter)

			assertNull(overlays.pendingConfirm, "Enter answers the prompt; strokes: $strokes")
			assertTrue(overlays.settingsVisible, "and Preferences stays open; strokes: $strokes")
			val keymap = loadKeymap(settings)
			assertEquals(listOf(chord("primary+KeyS"), chord("F7")), keymap.chordsFor("test.bound"))
			assertEquals(emptyList(), keymap.chordsFor("test.unbound"))
			onAllNodesWithText(unboundLabel, useUnmergedTree = true).fetchSemanticsNodes().let { nodes -> assertEquals(1, nodes.size, "the other command reads Unbound again") }
		}
	}
}