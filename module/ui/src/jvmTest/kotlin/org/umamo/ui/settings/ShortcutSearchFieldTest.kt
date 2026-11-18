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
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTextInput
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
import org.umamo.ui.kit.textentry.KeyCaptureController
import org.umamo.ui.kit.textentry.LocalKeyCapture
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.shell.ShellModalState
import org.umamo.ui.workspace.shell.handleModalKeyLadder
import org.umamo.ui.workspace.shell.toShellKeyStroke
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Drives the shortcut panel's search field under the shell's real modal ladder: the field is the first text
 * field the settings window has had, and a key that belongs to the field must not reach the keymap.
 */
@OptIn(ExperimentalTestApi::class)
class ShortcutSearchFieldTest {
	private val defaultJson = """{"input":{"keybinding":{"preset":"default","overrides":{}}}}"""

	/**
	 * Mounts the panel under a root that routes keys through the modal ladder, with Preferences open.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ShellOverlayState overlays The shell's overlay state.
	 * @param MutableList strokes Receives each key stroke the root sees, for the failure message.
	 * @return Settings The settings the panel reads.
	 */
	private fun mount(test: ComposeUiTest, overlays: ShellOverlayState, strokes: MutableList<String>): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())
		val settings = Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), defaultJson)
		val registry = CommandRegistry()
		registry.registerAll(listOf(Command("test.bound", title = Res.string.cmd_mesh_grab) {}))
		val keymap = Keymap(emptyMap())
		val rootFocus = FocusRequester()
		val keyCapture = KeyCaptureController()
		val shortcutEditor = ShortcutEditorController()

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
									strokes += "${event.key} ${event.type}"
									handleModalKeyLadder(
										event.toShellKeyStroke(),
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
						LaunchedEffect(Unit) {
							rootFocus.requestFocus()
						}

						if (overlays.settingsVisible) {
							KeybindingsEditor()
						}
					}
				}
			}
		}

		return settings
	}

	/**
	 * Mounts the whole settings window under a root that routes keys through the modal ladder.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ShellOverlayState overlays The shell's overlay state.
	 * @param MutableList strokes Receives each key stroke the root sees, for the failure message.
	 */
	private fun mountWindow(test: ComposeUiTest, overlays: ShellOverlayState, strokes: MutableList<String>) {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())
		val settings = Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), defaultJson)
		val registry = CommandRegistry()
		registry.registerAll(listOf(Command("test.bound", title = Res.string.cmd_mesh_grab) {}))
		val keymap = Keymap(emptyMap())
		val keyCapture = KeyCaptureController()
		val shortcutEditor = ShortcutEditorController()

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
								.onPreviewKeyEvent { event ->
									strokes += "${event.key} ${event.type}"
									handleModalKeyLadder(
										event.toShellKeyStroke(),
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
						SettingsWindow(onDismiss = {})
					}
				}
			}
		}
	}

	/** The search field's value, read from the field's own node. */
	private fun ComposeUiTest.queryOf(): String {
		val editable = onNode(hasSetTextAction()).fetchSemanticsNode().config[SemanticsProperties.EditableText]

		return editable.text
	}

	/**
	 * The find shortcut hands the keyboard to the search field.
	 *
	 * The shortcut is routed by the settings window, not by the panel: a key event only reaches the handlers
	 * on the focused node's ancestors, and the focus is rarely inside the panel.  So the test mounts the
	 * window, picks the keybindings category - whose row takes the focus - and presses the shortcut.
	 */
	@Test
	fun theFindShortcutFocusesTheSearchField() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			mountWindow(this, overlays, strokes)

			onNodeWithText("Keybindings").performClick()
			waitForIdle()

			onNodeWithText("Keybindings").performKeyInput {
				keyDown(Key.CtrlLeft)
				keyDown(Key.F)
				keyUp(Key.F)
				keyUp(Key.CtrlLeft)
			}
			waitForIdle()

			onNode(hasSetTextAction()).assertIsFocused()
		}
	}

	/**
	 * A space pressed while the search field has focus is not taken by the keymap, so the palette does not
	 * open over Preferences.  The field's own text arrives through the platform's input method, which is
	 * what carries it in the real application; what the ladder must not do is claim the key itself.
	 */
	@Test
	fun aSpaceTypedInTheSearchIsTypedAndNotTakenByTheKeymap() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			mount(this, overlays, strokes)

			onNode(hasSetTextAction()).performClick()
			waitForIdle()

			onNode(hasSetTextAction()).performClick()
			waitForIdle()
			// A real key, so the shell's preview handler sees it before the field does.
			onNode(hasSetTextAction()).performKeyInput {
				keyDown(Key.Spacebar)
				keyUp(Key.Spacebar)
			}
			waitForIdle()

			assertTrue(overlays.settingsVisible, "Preferences stays open; strokes=$strokes")
			assertFalse(overlays.paletteVisible, "and the palette does not open over it; strokes=$strokes")
		}
	}

	/**
	 * A letter pressed while the search field has focus is not taken by the keymap either.
	 */
	@Test
	fun aLetterTypedInTheSearchIsTypedAndNotTakenByTheKeymap() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val strokes = mutableListOf<String>()
			mount(this, overlays, strokes)

			onNode(hasSetTextAction()).performClick()
			waitForIdle()
			onNode(hasSetTextAction()).performKeyInput {
				keyDown(Key.A)
				keyUp(Key.A)
			}
			waitForIdle()

			assertTrue(overlays.settingsVisible, "Preferences stays open; strokes=$strokes")
			assertFalse(overlays.paletteVisible, "and the palette does not open over it; strokes=$strokes")
		}
	}
}