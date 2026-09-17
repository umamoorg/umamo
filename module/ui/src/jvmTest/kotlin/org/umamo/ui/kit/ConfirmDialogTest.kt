package org.umamo.ui.kit

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins which buttons a confirm dialog shows and which action each one runs.
 *
 * The dialog is the last thing between a rigger and losing work, so the mapping from a named button to
 * its action is the part worth pinning: a three-way dialog whose "Don't Save" ran the save, or whose
 * cancel ran the destructive action, would read as correct in the source and be wrong on screen.  A
 * two-button dialog must also stay two buttons - the third choice is opt-in.
 */
class ConfirmDialogTest {
	private val message = "Discard the unsaved changes?"

	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aDialogWithoutAnAlternativeShowsItsTwoNamedButtons() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					ConfirmDialog(
						message = message,
						onConfirm = {},
						onCancel = {},
						confirmLabel = "Discard",
						cancelLabel = "Keep Editing",
					)
				}
			}

			onNodeWithText(message).assertIsDisplayed()
			onNodeWithText("Discard").assertIsDisplayed()
			onNodeWithText("Keep Editing").assertIsDisplayed()
			// The third choice is opt-in: nothing takes its place in a two-button dialog.
			onAllNodesWithText("Don't Save").assertCountEquals(0)
		}
	}

	@OptIn(ExperimentalTestApi::class)
	@Test
	fun eachButtonRunsOnlyItsOwnAction() {
		var confirmCount = 0
		var cancelCount = 0
		var alternativeCount = 0
		runComposeUiTest {
			setContent {
				UmamoTheme {
					ConfirmDialog(
						message = message,
						onConfirm = { confirmCount++ },
						onCancel = { cancelCount++ },
						confirmLabel = "Save",
						cancelLabel = "Cancel",
						alternative = DialogChoice("Don't Save") { alternativeCount++ },
					)
				}
			}

			onNodeWithText("Don't Save").performClick()
			assertEquals(1, alternativeCount)
			assertEquals(0, confirmCount)
			assertEquals(0, cancelCount)

			onNodeWithText("Cancel").performClick()
			assertEquals(1, cancelCount)
			assertEquals(0, confirmCount)

			onNodeWithText("Save").performClick()
			assertEquals(1, confirmCount)
			assertEquals(1, cancelCount, "and no button runs another's action")
			assertEquals(1, alternativeCount)
		}
	}
}