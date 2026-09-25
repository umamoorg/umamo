package org.umamo.ui.workspace

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.platform.ClipEntry
import androidx.compose.ui.platform.Clipboard
import androidx.compose.ui.platform.ClipboardManager
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.NativeClipboard
import androidx.compose.ui.platform.asAwtTransferable
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.AnnotatedString
import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.parseKeyChord
import org.umamo.ui.kit.MessageDialog
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.cmd_mesh_grab
import org.umamo.ui.theme.UmamoTheme
import java.awt.datatransfer.DataFlavor
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

/**
 * Copies an alert's text the two ways a rigger would - selecting it and pressing the copy chord, and the kit's
 * text menu - under the shell's real modal ladder, which swallows every other key while the alert is up.  The
 * alert's command (`java -XX:MaxRAMPercentage=50 -jar …`) is the text most worth copying, and the ladder is what
 * could silently eat the chord before the text ever sees it.
 */
@OptIn(ExperimentalTestApi::class, ExperimentalComposeUiApi::class)
class AlertTextCopyTest {
	private val message = "Start it from a terminal with: java -XX:MaxRAMPercentage=50 -jar umamo.jar"

	/** The clipboard the text field's copy chord writes: the suspend API, recorded rather than the system's. */
	private class RecordingClipboard : Clipboard {
		var entry: ClipEntry? = null

		override suspend fun getClipEntry(): ClipEntry? = entry

		override suspend fun setClipEntry(clipEntry: ClipEntry?) {
			entry = clipEntry
		}

		override val nativeClipboard: NativeClipboard = java.awt.datatransfer.Clipboard("test")

		/**
		 * The copied text, or null.
		 *
		 * @return String? The text.
		 */
		fun text(): String? = entry?.asAwtTransferable?.getTransferData(DataFlavor.stringFlavor) as? String
	}

	/** The clipboard the kit's text menu writes: the older API, recorded. */
	@Suppress("DEPRECATION")
	private class RecordingClipboardManager : ClipboardManager {
		var copied: String? = null

		override fun setText(annotatedString: AnnotatedString) {
			copied = annotatedString.text
		}

		override fun getText(): AnnotatedString? = copied?.let { text -> AnnotatedString(text) }
	}

	/**
	 * Mounts an alert under a root that routes every key through the modal ladder, with a command bound to the
	 * copy chord so a chord that leaked past the alert would show.
	 *
	 * @param ComposeUiTest             test      The running UI test.
	 * @param ShellOverlayState         overlays  The shell's overlay state, its alert already pending.
	 * @param RecordingClipboard        clipboard The copy chord's clipboard.
	 * @param RecordingClipboardManager manager   The text menu's clipboard.
	 * @param MutableList               fired     Receives each command the keymap ran.
	 */
	@Suppress("DEPRECATION")
	private fun mount(
		test: ComposeUiTest,
		overlays: ShellOverlayState,
		clipboard: RecordingClipboard,
		manager: RecordingClipboardManager,
		fired: MutableList<String>,
	) {
		val registry = CommandRegistry()
		registry.register(Command("test.copyChord", title = Res.string.cmd_mesh_grab) { fired += "test.copyChord" })
		val keymap = Keymap(mapOf(parseKeyChord("primary+KeyC")!! to "test.copyChord"))
		val rootFocus = FocusRequester()
		test.setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalClipboard provides clipboard, LocalClipboardManager provides manager) {
					Box(
						modifier =
							Modifier
								.fillMaxSize()
								.testTag("root")
								.focusRequester(rootFocus)
								.focusable()
								.onPreviewKeyEvent { event ->
									handleModalKeyLadder(event.toShellKeyStroke(), ShellModalState(overlays = overlays, commandRegistry = registry, keymap = keymap))
								},
					) {
						if (overlays.pendingAlert != null) {
							MessageDialog(message = message, onDismiss = { overlays.pendingAlert = null })
						}
					}
				}
			}
		}
	}

	@Test
	fun theCopyChordCopiesASelectionWithoutDismissingTheAlert() =
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { pendingAlert = AlertRequest(Res.string.cmd_mesh_grab) }
			val clipboard = RecordingClipboard()
			val fired = ArrayList<String>()
			mount(this, overlays, clipboard, RecordingClipboardManager(), fired)

			// A drag from the first character to the last selects the whole message, which wraps onto two lines,
			// and a press inside the card is not a scrim click.  The pointer moves onto the text first: a press
			// where it starts, the window's corner, is the scrim.
			onNodeWithText(message).performMouseInput {
				moveTo(topLeft + Offset(1f, 1f))
				press()
				moveTo(bottomRight - Offset(1f, 1f))
				release()
			}
			waitForIdle()
			assertNotNull(overlays.pendingAlert, "selecting the text leaves the alert up")

			onNodeWithText(message).performKeyInput {
				keyDown(Key.CtrlLeft)
				pressKey(Key.C)
				keyUp(Key.CtrlLeft)
			}
			waitForIdle()

			assertEquals(message, clipboard.text(), "the chord reached the selected text")
			assertNotNull(overlays.pendingAlert, "copying leaves the alert up")
			assertEquals(emptyList(), fired, "the keymap never sees the chord behind an alert")

			onNodeWithText(message).performKeyInput { pressKey(Key.Escape) }
			waitForIdle()
			assertNull(overlays.pendingAlert, "Escape still dismisses while the text holds focus")
		}

	@Test
	fun theTextMenuOffersCopyAndSelectAllAndCopiesTheText() =
		runComposeUiTest {
			val overlays = ShellOverlayState().apply { pendingAlert = AlertRequest(Res.string.cmd_mesh_grab) }
			val manager = RecordingClipboardManager()
			mount(this, overlays, RecordingClipboard(), manager, ArrayList())

			// Select All first, then Copy: the read-only menu has no Cut or Paste.
			onNodeWithText(message).performMouseInput {
				moveTo(center)
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			onNodeWithText("Cut").assertDoesNotExist()
			onNodeWithText("Paste").assertDoesNotExist()
			onNodeWithText("Select All").performClick()
			waitForIdle()
			onNodeWithText(message).performMouseInput {
				moveTo(center)
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			onNodeWithText("Copy").performClick()
			waitForIdle()

			assertEquals(message, manager.copied)
			assertNotNull(overlays.pendingAlert, "the menu does not dismiss the alert")
		}
}