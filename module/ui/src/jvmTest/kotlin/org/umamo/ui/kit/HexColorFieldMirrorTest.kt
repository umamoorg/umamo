package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.unit.dp
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * That a hex field follows its external value even while it holds focus.
 *
 * This field is not the only writer of what it shows - its own swatch picker, a parameter scrub moving a
 * keyed channel, and undo all change the value underneath it.  It used to skip the mirror whenever it was
 * focused, and since nothing ever takes focus back off it, one click into the field froze it permanently:
 * a keyed multiply color sat on its first key while the viewport went on blending.
 */
class HexColorFieldMirrorTest {
	private val green = "#FF3DFF00"
	private val magenta = "#FFFF00FF"

	/** An external change lands in the field even though the field is focused. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun anExternalChangeReachesAFocusedField() =
		runComposeUiTest {
			var value by mutableStateOf(green)
			setContent {
				Box(modifier = Modifier.width(240.dp)) {
					HexColorField(
						value = value,
						onValueChange = { hex -> value = hex },
					)
				}
			}
			// Focus it the way a user would, then change the value from somewhere else entirely.
			field().performClick()
			waitForIdle()
			value = magenta
			waitForIdle()
			field().assertTextEquals(magenta)
		}

	/** Typing is not fought: the user's own edit round-trips and the field keeps what they typed. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun typingIsNotClobberedByItsOwnRoundTrip() =
		runComposeUiTest {
			var value by mutableStateOf(green)
			setContent {
				Box(modifier = Modifier.width(240.dp)) {
					HexColorField(
						value = value,
						onValueChange = { hex -> value = hex },
					)
				}
			}
			field().performClick()
			field().performTextClearance()
			field().performTextInput(magenta)
			waitForIdle()
			field().assertTextEquals(magenta)
			assertEquals(magenta, value, "a parseable edit commits out through onValueChange")
		}

	/**
	 * Moving the selection is not an edit, so it must not persist anything.
	 *
	 * The field holds a TextFieldValue so its context menu can act on a selection, and BasicTextField's
	 * TextFieldValue overload reports caret and selection moves through the same callback as typing.
	 * Persisting those re-committed the stored color for merely clicking into the field or dragging a
	 * selection across it - and on a KEYED channel that is an undo step plus the orange uncommitted-edit
	 * tint, for a value that never changed.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun movingTheSelectionPersistsNothing() =
		runComposeUiTest {
			var value by mutableStateOf(green)
			var commits = 0
			setContent {
				Box(modifier = Modifier.width(240.dp)) {
					HexColorField(
						value = value,
						onValueChange = { hex ->
							commits++
							value = hex
						},
					)
				}
			}
			field().performClick()
			waitForIdle()
			// Counted from here: the click itself places a caret, which is the very thing under test.
			val commitsBeforeSelecting = commits

			field().performTextInputSelection(TextRange(0, value.length))
			waitForIdle()
			assertEquals(commitsBeforeSelecting, commits, "selecting the text is not an edit of it")

			field().performTextClearance()
			field().performTextInput(magenta)
			waitForIdle()
			assertEquals(magenta, value, "but a real edit still persists")
		}

	/**
	 * The editable node inside the field.
	 *
	 * Selected by its set-text action rather than a tag: the caller's modifier lands on the field's outer
	 * row, whose subtree also holds the swatch, so a tag there is not the node text assertions act on.
	 *
	 * @return SemanticsNodeInteraction The text node.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun androidx.compose.ui.test.ComposeUiTest.field() = onNode(hasSetTextAction())
}