package org.umamo.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.action.Keymap
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.TopLevelMenu
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Help menu carries Open Log Folder exactly where the host registered the command: a row whose command was never
 * registered would do nothing when chosen, which on a platform with no file manager to hand the folder to is every
 * time.
 */
@OptIn(ExperimentalTestApi::class)
class HelpMenuTest {
	/**
	 * The command ids of the Help menu's rows, in order, read by choosing each one.
	 *
	 * @param Boolean canOpenLogFolder Whether the host registered Open Log Folder.
	 * @return List The rows' command ids.
	 */
	private fun helpMenuCommandIds(canOpenLogFolder: Boolean): List<String> {
		val dispatched = ArrayList<String>()
		var menu: TopLevelMenu? = null
		runComposeUiTest {
			setContent {
				menu = helpMenu(Keymap(emptyMap()), { commandId, _ -> dispatched += commandId }, canOpenLogFolder)
			}
			waitForIdle()
		}
		for (item in checkNotNull(menu).items) {
			if (item is MenuItem.Action) {
				item.onSelect()
			}
		}
		return dispatched
	}

	@Test
	fun theRowIsThereWhenTheHostCanOpenTheFolder() {
		assertEquals(
			listOf("help.sourceCode", "help.webSite", "help.documentation", "help.quickSetup", "help.openLogFolder", "help.credits", "help.about"),
			helpMenuCommandIds(canOpenLogFolder = true),
		)
	}

	@Test
	fun theRowIsLeftOutWhereTheHostCannot() {
		assertEquals(
			listOf("help.sourceCode", "help.webSite", "help.documentation", "help.quickSetup", "help.credits", "help.about"),
			helpMenuCommandIds(canOpenLogFolder = false),
		)
	}
}