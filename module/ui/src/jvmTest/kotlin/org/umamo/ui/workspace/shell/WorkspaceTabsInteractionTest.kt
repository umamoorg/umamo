package org.umamo.ui.workspace.shell

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The tab strip's press handling, driven with real pointer input over the real workspace commands: the
 * double-click that opens the inline rename editor, and the reorder drag that shares its gesture.
 *
 * The strip's gesture is a long-lived pointerInput coroutine per tab slot, so what these pin is which
 * workspace a slot's gesture believes it belongs to after the list underneath it has changed.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceTabsInteractionTest {
	/**
	 * The names of every tab currently showing the inline rename editor.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @return List The editor's text for each open editor (empty when none is open).
	 */
	private fun openEditors(test: ComposeUiTest): List<String> =
		listOf("Alpha", "Bravo", "Charlie").filter { name ->
			test.onAllNodes(hasSetTextAction() and hasText(name)).fetchSemanticsNodes().isNotEmpty()
		}

	/** Two quick presses on a tab open that tab's rename editor. */
	@Test
	fun doubleClickOpensTheClickedTabsEditor() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			onNodeWithText("Bravo").performMouseInput {
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this))
			assertEquals("bravo", fixture.controller.renamingWorkspaceId, "the double-click ran Rename Workspace on that tab")
		}

	/**
	 * After the order changes underneath a slot, a double-click there renames the workspace the slot now
	 * shows.  The slot's gesture outlives the change (same index, same tab count), so a gesture that captured
	 * its workspace when it started would open the editor on the tab that used to sit there.
	 */
	@Test
	fun doubleClickAfterAReorderRenamesTheTabNowInThatSlot() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			// A first click starts slot 0's gesture while it still holds Alpha.
			onNodeWithText("Alpha").performMouseInput { click() }
			waitForIdle()
			mainClock.advanceTimeBy(1_000L)
			runOnIdle { fixture.controller.reorder(1, 0) }
			waitForIdle()
			assertEquals(listOf("Bravo", "Alpha", "Charlie"), fixture.names, "the order changed under the slot")
			onNodeWithText("Bravo").performMouseInput {
				click()
				advanceEventTime(100L)
				click()
			}
			waitForIdle()
			assertEquals(listOf("Bravo"), openEditors(this), "the editor opens on the tab that was double-clicked")
		}

	/** A press that became a reorder drag never pairs with the click after it as a double-click. */
	@Test
	fun aClickRightAfterADragIsNotADoubleClick() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)
			onNodeWithText("Alpha").performMouseInput {
				press()
				// Past the reorder hold gate, then a nudge too small to change the order.
				advanceEventTime(150L)
				moveBy(Offset(2f, 0f))
				release()
				advanceEventTime(50L)
				click()
			}
			waitForIdle()
			assertEquals(emptyList(), openEditors(this), "no rename editor opens")
			assertEquals(1, onAllNodesWithText("Alpha").fetchSemanticsNodes().size)
		}
}