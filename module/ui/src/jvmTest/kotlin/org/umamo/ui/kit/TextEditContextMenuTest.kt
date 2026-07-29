package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kit's text-field context menu, driven with real pointer input against a real composition.
 *
 * This is not a paranoid test.  The whole mechanism turns on one fact that cannot be checked by reading our
 * own code: whether an initial-pass secondary-click detector really does outrank the handler Compose
 * installs INSIDE its text field.  If a Compose upgrade moves that handler earlier, the built-in menu
 * silently takes the right-click back and the keyframe entries become unreachable again - the exact bug this
 * replaced, returning with no compile error to announce it.
 */
class TextEditContextMenuTest {
	/** A text field inside a context-menu area, which is how every real one is mounted. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aTextFieldsMenuCarriesTheEnclosingItemsThenTheClipboardOnes() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					ContextMenuArea(items = listOf(MenuItem.Action(label = "Insert Keyframe", onSelect = {}))) {
						Box(modifier = Modifier.size(width = 200.dp, height = 40.dp).testTag("field")) {
							TextField(value = "abc", onValueChange = {})
						}
					}
				}
			}
			onNodeWithTag("field").performMouseInput {
				moveTo(Offset(20f, 10f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			// The enclosing area's entry proves ours won the click; the clipboard entry proves we replaced
			// rather than merely suppressed Compose's menu.
			onNodeWithText("Insert Keyframe").assertExists()
			onNodeWithText("Paste").assertExists()
		}

	/**
	 * A focus requester in the caller's modifier still reaches the FIELD, not the menu's wrapper.
	 *
	 * The command palette opens with its search box already focused, and it asks for that with a
	 * focusRequester passed in the modifier.  Wrapping the field in a box and giving the box that modifier
	 * compiles, renders identically, and silently stops the palette from being typed into.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aCallersFocusRequesterStillReachesTheField() =
		runComposeUiTest {
			val focus = FocusRequester()
			var typed = ""
			setContent {
				UmamoTheme {
					TextField(
						value = typed,
						onValueChange = { edited -> typed = edited },
						modifier = Modifier.fillMaxWidth().focusRequester(focus).testTag("field"),
					)
				}
				LaunchedEffect(Unit) { focus.requestFocus() }
			}
			waitForIdle()
			onNodeWithTag("field").performTextInput("hi")
			waitForIdle()
			assertEquals("hi", typed, "the field must have taken focus and received the keystrokes")
		}

	/** With no enclosing area the menu is the clipboard entries alone - and no leading separator. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aBareTextFieldStillGetsTheClipboardMenu() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(width = 200.dp, height = 40.dp).testTag("field")) {
						TextField(value = "abc", onValueChange = {})
					}
				}
			}
			onNodeWithTag("field").performMouseInput {
				moveTo(Offset(20f, 10f))
				press(MouseButton.Secondary)
				release(MouseButton.Secondary)
			}
			waitForIdle()
			onNodeWithText("Select All").assertExists()
		}
}
