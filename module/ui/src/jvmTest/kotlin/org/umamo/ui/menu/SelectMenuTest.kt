package org.umamo.ui.menu

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.kit.menu.TopLevelMenu
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.selectCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu bar's Select menu: Select All and Invert, then the by-kind families under two submenus that only Object
 * mode opens.  The submenus hold whatever the registry lists for their families, so the selection table alone decides
 * their rows.
 */
@OptIn(ExperimentalTestApi::class)
class SelectMenuTest {
	/**
	 * Registers the selection table the way the shell does with no document open.
	 *
	 * @param CommandRegistry registry The registry to register into.
	 */
	private fun registerSelectTable(registry: CommandRegistry) {
		registry.registerAll(selectCommands(null, CommandRouting { null }, KeyformSheetViews(), SessionAvailability(null)))
	}

	/**
	 * Builds the Select menu, recording what its rows dispatch.
	 *
	 * @param Boolean inObjectMode Whether the session is in Object mode.
	 * @param MutableList<String> dispatched Collects the dispatched command ids.
	 * @param CommandRegistry commands The registry the by-kind families are listed from: the selection table's unless
	 *   a case hands in its own.
	 * @return TopLevelMenu The menu.
	 */
	private fun selectMenuOf(
		inObjectMode: Boolean,
		dispatched: MutableList<String>,
		commands: CommandRegistry = CommandRegistry().also { registry -> registerSelectTable(registry) },
	): TopLevelMenu {
		var menu: TopLevelMenu? = null
		runComposeUiTest {
			setContent {
				menu = selectMenu(Keymap(emptyMap()), commands, hasDocument = true, inObjectMode = inObjectMode, dispatch = { commandId, _ -> dispatched += commandId })
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

	/** Each submenu is headed by its family and names its rows by the kind alone: the heading says the rest. */
	@Test
	fun eachFamilyIsHeadedAndItsRowsNameTheKindAlone() {
		val submenus = selectMenuOf(inObjectMode = true, ArrayList()).items.filterIsInstance<MenuItem.Submenu>()

		assertEquals(listOf("Select All of Type", "Invert Selection of Type"), submenus.map { submenu -> submenu.label })

		for (submenu in submenus) {
			assertEquals(
				listOf("Parts", "Drawables", "Warp Deformers", "Rotation Deformers"),
				submenu.items.map { row -> (row as MenuItem.Action).label },
				submenu.label,
			)
		}
	}

	/** The rows come from the registry and nowhere else, so a family nothing has registered offers nothing to open. */
	@Test
	fun aFamilyNothingHasRegisteredOffersNothing() {
		val submenus = selectMenuOf(inObjectMode = true, ArrayList(), CommandRegistry()).items.filterIsInstance<MenuItem.Submenu>()

		assertEquals(2, submenus.size)
		assertTrue(submenus.all { submenu -> submenu.items.isEmpty() }, "no row without a registered command")
		assertTrue(submenus.none { submenu -> submenu.enabled }, "and a submenu with no row cannot open, Object mode or not")
	}

	/**
	 * The app builds the menu bar outside the shell, whose effects register the selection table only after that
	 * composition - so the menu has to come back by itself when the family lands.
	 */
	@Test
	fun aFamilyRegisteredAfterTheMenuWasBuiltReachesIt() =
		runComposeUiTest {
			val commands = CommandRegistry()
			var menu: TopLevelMenu? = null
			setContent {
				menu = selectMenu(Keymap(emptyMap()), commands, hasDocument = true, inObjectMode = true, dispatch = { _, _ -> })
			}
			waitForIdle()
			assertTrue(checkNotNull(menu).items.filterIsInstance<MenuItem.Submenu>().all { submenu -> submenu.items.isEmpty() }, "nothing is registered yet")

			runOnIdle { registerSelectTable(commands) }
			waitForIdle()

			val submenus = checkNotNull(menu).items.filterIsInstance<MenuItem.Submenu>()
			assertEquals(listOf(4, 4), submenus.map { submenu -> submenu.items.size }, "the registration recomposed the menu")
			assertTrue(submenus.all { submenu -> submenu.enabled })
		}
}