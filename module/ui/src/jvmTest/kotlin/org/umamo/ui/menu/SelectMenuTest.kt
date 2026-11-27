package org.umamo.ui.menu

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.chooseEveryRow
import org.umamo.ui.action.familyMenuOf
import org.umamo.ui.action.menuOutline
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.KeyformSheetViews
import org.umamo.ui.workspace.commands.CommandRouting
import org.umamo.ui.workspace.commands.SessionAvailability
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.registerAll
import org.umamo.ui.workspace.commands.selectCommands
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The menu bar's Select menu: Select All and Invert, a rule, then the by-kind families under two submenus that only
 * Object mode opens.  The menu holds whatever the registry lists for its family, so the selection table alone decides
 * its rows, and each row's own command decides when it is grayed.
 */
class SelectMenuTest {
	/**
	 * A registry holding the selection table the way the shell registers it over [session].
	 *
	 * @param EditorSession? session The open document's session, or null for no document.
	 * @return CommandRegistry The registry.
	 */
	private fun selectRegistry(session: EditorSession?): CommandRegistry =
		CommandRegistry().also { registry -> registry.registerAll(selectCommands(session, CommandRouting { null }, KeyformSheetViews(), SessionAvailability(session))) }

	@Test
	fun theRowsDispatchTheSelectCommands() {
		val dispatched = ArrayList<String>()
		val tables = selectCommands(null, CommandRouting { null }, KeyformSheetViews(), SessionAvailability(null))
		chooseEveryRow(familyMenuOf(CommandFamily.Select, recordingRegistryOf(tables, dispatched)))

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

	/** The by-kind submenus sit in a block of their own, each headed by its family with its rows named by the kind alone. */
	@Test
	fun theMenuIsTwoBlocksAndEachSubmenuNamesItsRowsByTheKindAlone() {
		val kinds = listOf("  Parts", "  Drawables", "  Warp Deformers", "  Rotation Deformers")

		assertEquals(
			listOf("Select All", "Invert Selection", "---", "Select All of Type >") + kinds + listOf("Invert Selection of Type >") + kinds,
			menuOutline(familyMenuOf(CommandFamily.Select, selectRegistry(commandFixtureSession(EditorMode.Object)))),
		)
	}

	@Test
	fun theByKindSubmenusOpenOnlyInObjectMode() {
		val inObjectMode = familyMenuOf(CommandFamily.Select, selectRegistry(commandFixtureSession(EditorMode.Object)))
		val inEditMode = familyMenuOf(CommandFamily.Select, selectRegistry(commandFixtureSession(EditorMode.Edit)))

		assertEquals(2, inObjectMode.filterIsInstance<MenuItem.Submenu>().size)
		assertTrue(inObjectMode.filterIsInstance<MenuItem.Submenu>().all { submenu -> submenu.enabled })
		assertEquals(2, inEditMode.filterIsInstance<MenuItem.Submenu>().size, "the submenus stay where they are")
		assertTrue(inEditMode.filterIsInstance<MenuItem.Submenu>().none { submenu -> submenu.enabled }, "and cannot open: none of their rows applies")
		assertTrue(inEditMode.filterIsInstance<MenuItem.Action>().all { row -> row.enabled }, "Select All and Invert serve every mode")
	}

	/** With no document open nothing can be selected, so every row is grayed by its own command. */
	@Test
	fun withNoDocumentEveryRowIsGrayed() {
		val menu = familyMenuOf(CommandFamily.Select, selectRegistry(null))

		assertTrue(menu.filterIsInstance<MenuItem.Action>().none { row -> row.enabled })
		assertTrue(menu.filterIsInstance<MenuItem.Submenu>().none { submenu -> submenu.enabled })
	}

	/** The rows come from the registry and nowhere else, so a family nothing has registered is a menu with no row. */
	@Test
	fun aFamilyNothingHasRegisteredOffersNothing() {
		assertEquals(emptyList(), familyMenuOf(CommandFamily.Select, CommandRegistry()))
	}
}