package org.umamo.ui.kit.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The menu bar builds a menu's entries as the menu opens, and again at each opening: the bar stays composed for the
 * whole session while what its rows read changes under it, so entries held from the bar's composition would show
 * whatever was true then.
 */
@OptIn(ExperimentalTestApi::class)
class MenuBarTest {
	@Test
	fun aMenusEntriesAreBuiltEachTimeItOpens() {
		var builds = 0
		val menus = listOf(TopLevelMenu(label = MENU) { listOf(MenuItem.Action(label = "$ROW ${++builds}", onSelect = {})) })
		// The host's way of closing the open menu, which is what the shell's Escape takes.
		val controller = MenuBarController()
		runComposeUiTest {
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalMenuBarController provides controller) {
						Box(modifier = Modifier.size(800.dp, 600.dp)) {
							MenuBar(menus = menus)
						}
					}
				}
			}
			waitForIdle()
			assertEquals(0, builds, "a closed menu builds nothing")

			onNodeWithText(MENU).performClick()
			waitForIdle()
			assertEquals(1, builds)
			assertTrue(popupShows("$ROW 1"))

			runOnIdle { checkNotNull(controller.closeOpenMenu) { "the open menu parked no closer" }() }
			waitForIdle()
			assertEquals(1, builds, "closing the menu builds nothing")
			assertFalse(popupShows("$ROW 1"), "the menu is closed")

			onNodeWithText(MENU).performClick()
			waitForIdle()
			assertEquals(2, builds, "the second opening built the entries again")
			assertTrue(popupShows("$ROW 2"), "and shows them, not the first opening's")
		}
	}

	/**
	 * Whether a popup shows a node reading exactly [text].
	 *
	 * @param String text The node's text.
	 * @return Boolean True when it does.
	 */
	private fun ComposeUiTest.popupShows(text: String): Boolean = onAllNodes(hasText(text) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	private companion object {
		/** The bar label. */
		const val MENU = "File"

		/** What every built row's label starts with; the build count follows. */
		const val ROW = "Row"
	}
}