package org.umamo.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.action.Keymap
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.kit.menu.TopLevelMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu bar's Select menu: Select All and Invert, then the by-kind pairs under two submenus that only Object mode
 * opens.
 */
@OptIn(ExperimentalTestApi::class)
class SelectMenuTest {
	/**
	 * Builds the Select menu, recording what its rows dispatch.
	 *
	 * @param Boolean inObjectMode Whether the session is in Object mode.
	 * @param MutableList<String> dispatched Collects the dispatched command ids.
	 * @return TopLevelMenu The menu.
	 */
	private fun selectMenuOf(
		inObjectMode: Boolean,
		dispatched: MutableList<String>,
	): TopLevelMenu {
		var menu: TopLevelMenu? = null
		runComposeUiTest {
			setContent {
				menu = selectMenu(Keymap(emptyMap()), hasDocument = true, inObjectMode = inObjectMode, dispatch = { commandId, _ -> dispatched += commandId })
			}
			waitForIdle()
		}

		return checkNotNull(menu)
	}

	/**
	 * Chooses every action row, nested ones included, in order.
	 *
	 * @param List<MenuItem> items The rows.
	 */
	private fun chooseEvery(items: List<MenuItem>) {
		for (item in items) {
			when (item) {
				is MenuItem.Action -> {
					item.onSelect()
				}

				is MenuItem.Submenu -> {
					chooseEvery(item.items)
				}

				else -> {}
			}
		}
	}

	@Test
	fun theRowsDispatchTheSelectCommands() {
		val dispatched = ArrayList<String>()
		chooseEvery(selectMenuOf(inObjectMode = true, dispatched).items)

		assertEquals(
			listOf(
				"select.all",
				"select.invert",
				"select.all.parts",
				"select.all.drawables",
				"select.all.warps",
				"select.all.rotations",
				"select.invert.parts",
				"select.invert.drawables",
				"select.invert.warps",
				"select.invert.rotations",
			),
			dispatched,
		)
	}

	@Test
	fun theByKindSubmenusOpenOnlyInObjectMode() {
		val inObjectMode = selectMenuOf(inObjectMode = true, ArrayList()).items.filterIsInstance<MenuItem.Submenu>()
		val elsewhere = selectMenuOf(inObjectMode = false, ArrayList()).items

		assertEquals(2, inObjectMode.size)
		assertTrue(inObjectMode.all { submenu -> submenu.enabled })
		assertTrue(elsewhere.filterIsInstance<MenuItem.Submenu>().none { submenu -> submenu.enabled })
		assertTrue(elsewhere.filterIsInstance<MenuItem.Action>().all { row -> row.enabled }, "Select All and Invert serve every mode")
	}
}