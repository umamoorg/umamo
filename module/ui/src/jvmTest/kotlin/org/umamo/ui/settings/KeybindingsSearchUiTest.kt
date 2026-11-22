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
import org.jetbrains.compose.resources.stringResource
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
import org.umamo.ui.resources.settings_keybindings_category_edit
import org.umamo.ui.resources.settings_keybindings_press_shortcut
import org.umamo.ui.resources.settings_keybindings_search_record
import org.umamo.ui.resources.settings_keybindings_unbound
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
 * Two commands sit on two cards: the grab command under Transform, bound to Ctrl+S, and the settings command under
 * Edit, unbound.  Every lookup goes by the string the page renders, read in composition, so a reworded string never
 * breaks one.
 */
@OptIn(ExperimentalTestApi::class)
class KeybindingsSearchUiTest {
	/**
	 * The page's text as the running locale renders it.
	 *
	 * @property String grab The bound command's title.
	 * @property String settings The unbound command's title.
	 * @property String editHeading The heading of the unbound command's card.
	 * @property String unbound What an unbound command's chip reads.
	 * @property String record The record button's label.
	 * @property String pressShortcut What an armed chip, and the cover over a recording search, reads.
	 */
	private class PageLabels(
		val grab: String,
		val settings: String,
		val editHeading: String,
		val unbound: String,
		val record: String,
		val pressShortcut: String,
	)

	/**
	 * Mounts the page under a root that routes keys through the modal ladder, with Preferences marked open.
	 *
	 * @param ComposeUiTest     test      The running UI test.
	 * @param ShellOverlayState overlays  The shell's overlay state.
	 * @param KeyCaptureController keyCapture The capture controller the ladder and the page share.
	 * @param Function          beforeMount Prepares the settings before the page reads them (a fold, say).
	 * @return PageLabels The page's text, for the lookups.
	 */
	private fun mount(
		test: ComposeUiTest,
		overlays: ShellOverlayState,
		keyCapture: KeyCaptureController = KeyCaptureController(),
		beforeMount: (Settings) -> Unit = {},
	): PageLabels {
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
		var labels: PageLabels? = null
		test.setContent {
			labels =
				PageLabels(
					grab = stringResource(Res.string.cmd_mesh_grab),
					settings = stringResource(Res.string.cmd_preferences),
					editHeading = stringResource(Res.string.settings_keybindings_category_edit),
					unbound = stringResource(Res.string.settings_keybindings_unbound),
					record = stringResource(Res.string.settings_keybindings_search_record),
					pressShortcut = stringResource(Res.string.settings_keybindings_press_shortcut),
				)
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

		return checkNotNull(labels) { "the page composed" }
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
			val labels = mount(this, overlays) { settings -> PreferencesFolds(settings).toggle(keybindingFoldId(KeymapCategory.Transform)) }

			onNodeWithText(labels.grab, useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertExists()

			onNode(hasSetTextAction()).performTextInput("grab")
			waitForIdle()

			onNodeWithText(labels.grab, useUnmergedTree = true).assertExists()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(labels.editHeading, useUnmergedTree = true).assertDoesNotExist()

			onNode(hasSetTextAction()).performTextReplacement("")
			waitForIdle()

			onNodeWithText(labels.grab, useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertExists()
		}
	}

	/** A typed shortcut finds the command bound to it. */
	@Test
	fun aQueryMatchesAShownShortcut() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val labels = mount(this, overlays)

			onNode(hasSetTextAction()).performTextInput("ctrl+s")
			waitForIdle()

			onNodeWithText(labels.grab, useUnmergedTree = true).assertExists()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** The record button takes the next press as the chord to look up, and the page narrows to what it runs. */
	@Test
	fun aRecordedChordNarrowsToTheCommandItRuns() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val keyCapture = KeyCaptureController()
			val labels = mount(this, overlays, keyCapture)

			onNodeWithContentDescription(labels.record, useUnmergedTree = true).performClick()
			waitForIdle()
			assertTrue(keyCapture.active, "the record button announces its capture to the ladder")

			pressControlS(this)

			assertFalse(keyCapture.active, "a recorded chord ends the capture")
			assertTrue(overlays.settingsVisible)
			onNodeWithText(labels.grab, useUnmergedTree = true).assertExists()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/**
	 * While it records, the search field is covered whole by the armed face, and a click on that cover reaches
	 * nothing: the recording stays armed and the next press is still the chord looked up.  A click that got through
	 * to the field would take the keyboard there and strand the recording.
	 */
	@Test
	fun aClickOnTheRecordingCoverKeepsRecording() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val keyCapture = KeyCaptureController()
			val labels = mount(this, overlays, keyCapture)

			onNodeWithText(labels.pressShortcut, useUnmergedTree = true).assertDoesNotExist()
			onNodeWithContentDescription(labels.record, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithText(labels.pressShortcut, useUnmergedTree = true).assertExists()

			onNodeWithText(labels.pressShortcut, useUnmergedTree = true).performClick()
			waitForIdle()
			assertTrue(keyCapture.active, "a click on the cover leaves the recording armed")

			pressControlS(this)

			assertFalse(keyCapture.active)
			onNodeWithText(labels.pressShortcut, useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(labels.grab, useUnmergedTree = true).assertExists()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** A recording armed while the search field holds the keyboard takes it from the field and still captures. */
	@Test
	fun aRecordingArmedFromTheFocusedFieldStillCaptures() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val keyCapture = KeyCaptureController()
			val labels = mount(this, overlays, keyCapture)

			onNode(hasSetTextAction()).performClick()
			waitForIdle()
			onNodeWithContentDescription(labels.record, useUnmergedTree = true).performClick()
			waitForIdle()
			assertTrue(keyCapture.active, "taking the keyboard from the field does not cancel the recording it was taken for")

			pressControlS(this)

			assertFalse(keyCapture.active)
			onNodeWithText(labels.grab, useUnmergedTree = true).assertExists()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** Escape while recording cancels the recording, not Preferences, and leaves the list as it was. */
	@Test
	fun escapeWhileRecordingCancelsItAndLeavesPreferencesOpen() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val keyCapture = KeyCaptureController()
			val labels = mount(this, overlays, keyCapture)

			onNodeWithContentDescription(labels.record, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithTag("root").performKeyInput {
				keyDown(Key.Escape)
				keyUp(Key.Escape)
			}
			waitForIdle()

			assertFalse(keyCapture.active)
			assertTrue(overlays.settingsVisible, "Escape cancels the recording, not Preferences")
			onNodeWithText(labels.grab, useUnmergedTree = true).assertExists()
			onNodeWithText(labels.settings, useUnmergedTree = true).assertExists()
		}
	}

	/** Arming a row's chip ends a recording, so the next press rebinds the row rather than searching. */
	@Test
	fun armingAChipEndsARecording() {
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { settingsVisible = true }
			val labels = mount(this, overlays)

			onNodeWithContentDescription(labels.record, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithText(labels.unbound, useUnmergedTree = true).performClick()
			waitForIdle()

			pressControlS(this)

			assertNotNull(overlays.pendingConfirm, "the chip took the press, and the grab command already holds it")
			onNodeWithText(labels.settings, useUnmergedTree = true).assertExists()
		}
	}
}