package org.umamo.ui.workspace.shell

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.rightClick
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A workspace tab's own menu, and the strip's "+" button, over the real workspace commands: the menu is the
 * workspace's Rename, Duplicate, and Delete aimed at the tab it was opened on, whichever workspace is active, then
 * the tab navigation.
 */
@OptIn(ExperimentalTestApi::class)
class WorkspaceTabMenuTest {
	/**
	 * Right-clicks the tab reading [name].
	 *
	 * @param String name The tab's label.
	 */
	private fun ComposeUiTest.openTabMenu(name: String) {
		onNodeWithText(name).performMouseInput { rightClick(center) }
		waitForIdle()
	}

	/**
	 * How many rows of the open menu read [label], counting only those that can be picked when [pickable].
	 *
	 * @param String label The row's text.
	 * @param Boolean pickable Whether to count only enabled rows.
	 * @return Int The count.
	 */
	private fun ComposeUiTest.menuRows(
		label: String,
		pickable: Boolean = false,
	): Int {
		val inMenu = hasText(label) and hasAnyAncestor(isPopup())

		return onAllNodes(if (pickable) inMenu and hasClickAction() else inMenu).fetchSemanticsNodes().size
	}

	/**
	 * Clicks the open menu's row reading [label].
	 *
	 * @param String label The row's text.
	 */
	private fun ComposeUiTest.pickMenuRow(label: String) {
		onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performMouseInput { click(center) }
		waitForIdle()
	}

	@Test
	fun theMenuIsTheTabsThreeActionsThenTheNavigation() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)

			openTabMenu("Bravo")

			val rows = listOf("Rename", "Duplicate", "Delete", "Previous Workspace", "Next Workspace")

			for (row in rows) {
				assertEquals(1, menuRows(row, pickable = true), row)
			}

			val tops = rows.map { row -> onNode(hasText(row) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNode().boundsInRoot.top }
			assertEquals(tops.sorted(), tops, "top to bottom in that order")
		}

	/** The rows act on the tab the menu was opened on, not on the active workspace. */
	@Test
	fun duplicateCopiesTheTabTheMenuWasOpenedOn() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)

			openTabMenu("Bravo")
			pickMenuRow("Duplicate")

			assertEquals(listOf("Alpha", "Bravo", "Bravo copy", "Charlie"), fixture.names)
		}

	@Test
	fun deleteRemovesTheTabTheMenuWasOpenedOn() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)

			openTabMenu("Charlie")
			pickMenuRow("Delete")

			assertEquals(listOf("Alpha", "Bravo"), fixture.names)
			assertEquals("alpha", fixture.controller.layout.activeWorkspaceId, "the active workspace is untouched")
		}

	@Test
	fun renameOpensTheEditorOnTheTabTheMenuWasOpenedOn() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)

			openTabMenu("Charlie")
			pickMenuRow("Rename")

			assertEquals("charlie", fixture.controller.renamingWorkspaceId)
			assertEquals(1, onAllNodes(hasSetTextAction() and hasText("Charlie")).fetchSemanticsNodes().size, "the tab turned into its editor")
		}

	/** The strip never goes empty, so on the last workspace Delete is shown and cannot be picked. */
	@Test
	fun onTheLastWorkspaceDeleteCannotBePicked() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture(listOf("Alpha"))
			fixture.mount(this)

			openTabMenu("Alpha")

			assertEquals(1, menuRows("Delete"))
			assertEquals(0, menuRows("Delete", pickable = true))
			assertEquals(1, menuRows("Duplicate", pickable = true), "the other actions still apply to it")
		}

	/** The "+" button is New Workspace: the same command the Workspace menu and the palette run. */
	@Test
	fun thePlusButtonRunsNewWorkspace() =
		runComposeUiTest {
			val fixture = WorkspaceTabsFixture()
			fixture.mount(this)

			onNodeWithContentDescription("New Workspace").performClick()
			waitForIdle()

			assertEquals(listOf("Alpha", "Bravo", "Charlie", "Workspace"), fixture.names)
			assertEquals("Workspace", fixture.controller.layout.activeWorkspace()?.name, "the new workspace is the active one")
		}
}