package org.umamo.ui.menu

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.rename
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.CommandRegistry
import org.umamo.ui.action.Keymap
import org.umamo.ui.action.LocalCommands
import org.umamo.ui.action.LocalKeymap
import org.umamo.ui.action.chooseEveryRow
import org.umamo.ui.action.defaultKeymap
import org.umamo.ui.action.familyMenuBuilderOf
import org.umamo.ui.action.familyMenuOf
import org.umamo.ui.action.formatAccelerator
import org.umamo.ui.action.menuOutline
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.action.rememberFamilyMenu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.workspace.commands.commandFixtureSession
import org.umamo.ui.workspace.commands.everyCommandTable
import org.umamo.ui.workspace.commands.everyCommandTableFunction
import org.umamo.ui.workspace.commands.fileCommands
import org.umamo.ui.workspace.commands.registerAll
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The application menu bar: five menus, each its command family as the registry lists it.  The bar holds no row of
 * its own, so what these pin - each menu's rows, their labels, their blocks, and when a row is grayed - is what the
 * command tables say.
 */
@OptIn(ExperimentalTestApi::class)
class AppMenuTest {
	/**
	 * A registry holding every command table, as the shell, the settings-backed shell, and the app register them.
	 *
	 * @param EditorSession? session The session the document-scoped tables close over, or null for no document.
	 * @return CommandRegistry The registry.
	 */
	private fun registryOverEveryTable(session: EditorSession? = null): CommandRegistry = CommandRegistry().also { registry -> registry.registerAll(everyCommandTable(session)) }

	@Test
	fun theBarIsFiveMenusInOrder() {
		assertEquals(
			listOf(CommandFamily.File, CommandFamily.Edit, CommandFamily.Select, CommandFamily.Workspace, CommandFamily.Help),
			APP_MENU_BAR,
		)

		val registry = registryOverEveryTable()

		for (family in APP_MENU_BAR) {
			assertNull(family.parent, "$family is a menu of the bar, not a submenu")
			assertTrue(familyMenuOf(family, registry).isNotEmpty(), "$family has no row")
		}
	}

	/**
	 * The File menu with no document open: the puppet's own file with the Import and Export submenus, a rule, then
	 * Exit.  Each row goes by its short label, and is grayed exactly when its command does not apply: no document
	 * can take artwork, and nothing can be captured as an image.
	 */
	@Test
	fun theFileMenuIsItsFamily() {
		assertEquals(
			listOf(
				"New",
				"Open…",
				"Open Recent > (off)",
				"Save",
				"Save As…",
				"Import >",
				"  Artwork… (off)",
				"  CMO3…",
				"  MOC3…",
				"Export >",
				"  CMO3…",
				"  MOC3…",
				"  Image… (off)",
				"---",
				"Exit",
			),
			menuOutline(familyMenuOf(CommandFamily.File, registryOverEveryTable())),
		)
	}

	/** Open Recent is a row per recent file, under the file's name, and each hands the open its own path. */
	@Test
	fun openRecentOffersEachRecentFileAndHandsOverItsPath() {
		val recentFiles = listOf("/rigs/hero.uma", "C:\\rigs\\villain.cmo3")
		val dispatched = ArrayList<String>()
		val registry = recordingRegistryOf(fileCommands({}, {}, {}, {}, { true }, {}, {}, {}, { recentFiles }, {}), dispatched)
		val openRecent = familyMenuOf(CommandFamily.File, registry).filterIsInstance<MenuItem.Submenu>().first { submenu -> submenu.label == "Open Recent" }

		assertTrue(openRecent.enabled)
		assertEquals(listOf("hero.uma", "villain.cmo3"), openRecent.items.map { row -> (row as MenuItem.Action).label })

		chooseEveryRow(openRecent.items)

		assertEquals(listOf("file.openPath(/rigs/hero.uma)", "file.openPath(C:\\rigs\\villain.cmo3)"), dispatched)
	}

	/** The recent files are read as the menu opens, so a file opened since the bar was built is in the list. */
	@Test
	fun openRecentReadsTheRecentFilesAsTheMenuOpens() {
		val recentFiles = ArrayList<String>()
		val registry = CommandRegistry().also { registry -> registry.registerAll(fileCommands({}, {}, {}, {}, { true }, {}, {}, {}, { recentFiles }, {})) }
		val fileMenu = familyMenuBuilderOf(CommandFamily.File, registry)

		assertTrue("Open Recent > (off)" in menuOutline(fileMenu()), "with no recent file there is nothing to open")

		recentFiles.add("/rigs/hero.uma")

		assertEquals(listOf("Open Recent >", "  hero.uma"), menuOutline(fileMenu()).dropWhile { line -> !line.startsWith("Open Recent") }.take(2))
	}

	/** The Edit menu: the undo history, a rule, then the settings.  With no document there is nothing to undo. */
	@Test
	fun theEditMenuIsItsFamily() {
		assertEquals(
			listOf("Undo (off)", "Redo (off)", "---", "Settings…"),
			menuOutline(familyMenuOf(CommandFamily.Edit, registryOverEveryTable())),
		)
	}

	/**
	 * Undo and Redo are grayed by the undo stack itself, read as the menu opens: the bar stays composed for the whole
	 * session, and an edit recomposes none of it.
	 */
	@Test
	fun undoAndRedoGrayWithTheUndoStack() {
		// A session of its own over the fixture's model: the fixture's own session already holds the steps that set it up.
		val session = EditorSession(commandFixtureSession(EditorMode.Object).model.value)
		val editMenu = familyMenuBuilderOf(CommandFamily.Edit, registryOverEveryTable(session))

		assertEquals(listOf("Undo (off)", "Redo (off)"), menuOutline(editMenu()).take(2), "a fresh document has no step either way")

		session.rename(SelectionTarget.Drawable(DrawableId("a")), "renamed")

		assertEquals(listOf("Undo", "Redo (off)"), menuOutline(editMenu()).take(2), "one step to take back")

		session.undo()

		assertEquals(listOf("Undo (off)", "Redo"), menuOutline(editMenu()).take(2), "and then one to take again")
	}

	/**
	 * The Workspace menu: moving between workspaces, making and resetting one, what is done to the active one, and
	 * the layout's trips to and from a file.  The third block is a tab's own menu, which goes by the verb alone.
	 */
	@Test
	fun theWorkspaceMenuIsItsFamily() {
		assertEquals(
			listOf(
				"Previous Workspace",
				"Next Workspace",
				"---",
				"New Workspace",
				"Reset Workspace",
				"---",
				"Rename",
				"Duplicate",
				"Delete",
				"---",
				"Import Workspace…",
				"Export This Workspace…",
				"Export All Workspaces…",
			),
			menuOutline(familyMenuOf(CommandFamily.Workspace, registryOverEveryTable())),
		)
	}

	/**
	 * A menu's order is declared, so it does not follow which table registered first.  The app registers its file,
	 * log, and update tables ahead of the shell's, the shell re-registers its session tables at the tail on every
	 * document swap, and none of that may move a row.
	 */
	@Test
	fun noMenuDependsOnWhichTableRegisteredFirst() {
		val inShellOrder = registryOverEveryTable()
		val reversed = CommandRegistry().also { registry -> everyCommandTableFunction().reversed().forEach { table -> registry.registerAll(table) } }

		for (family in APP_MENU_BAR) {
			assertEquals(menuOutline(familyMenuOf(family, inShellOrder)), menuOutline(familyMenuOf(family, reversed)), "$family")
		}
	}

	/** Every row dispatches its own command, top to bottom, in every menu of the bar. */
	@Test
	fun theRowsDispatchTheirCommands() {
		val dispatched = ArrayList<String>()
		val registry = recordingRegistryOf(everyCommandTable(), dispatched)
		val expected =
			mapOf(
				CommandFamily.File to
					listOf(
						"file.new",
						"file.open",
						"file.save",
						"file.saveAs",
						"file.importArtwork",
						"file.importCmo3",
						"file.importMoc3",
						"file.exportCmo3",
						"file.exportMoc3",
						"file.exportImage",
						"file.exit",
					),
				CommandFamily.Edit to listOf("edit.undo", "edit.redo", "edit.preferences"),
				CommandFamily.Workspace to
					listOf(
						"workspace.prev",
						"workspace.next",
						"workspace.new",
						"workspace.reset",
						"workspace.rename",
						"workspace.duplicate",
						"workspace.delete",
						"workspace.import",
						"workspace.exportThis",
						"workspace.exportAll",
					),
			)

		for ((family, ids) in expected) {
			dispatched.clear()
			chooseEveryRow(familyMenuOf(family, registry))

			assertEquals(ids, dispatched, "$family")
		}
	}

	/** A row shows whatever chord the keymap binds to its command, and none where it binds none. */
	@Test
	fun aRowShowsTheChordItsCommandIsBoundTo() {
		val keymap = defaultKeymap()
		val saveChord = assertNotNull(keymap.chordFor("file.save"), "the default keymap binds Save")
		val rows = familyMenuOf(CommandFamily.File, registryOverEveryTable(), keymap).filterIsInstance<MenuItem.Action>().associateBy { row -> row.label }

		assertEquals(formatAccelerator(saveChord), rows.getValue("Save").shortcut)
		assertEquals(keymap.chordFor("file.exit")?.let { chord -> formatAccelerator(chord) }, rows.getValue("Exit").shortcut)
		assertNull(familyMenuOf(CommandFamily.File, registryOverEveryTable(), Keymap(emptyMap())).filterIsInstance<MenuItem.Action>().first().shortcut, "an unbound command shows none")
	}

	/**
	 * The bar is composed before the shell's effects have registered a single table, so a menu has to come back by
	 * itself when its family lands.
	 */
	@Test
	fun aTableRegisteredAfterTheBarWasBuiltReachesIt() =
		runComposeUiTest {
			val commands = CommandRegistry()
			var editMenu: (() -> List<MenuItem>)? = null
			setContent {
				CompositionLocalProvider(LocalCommands provides commands, LocalKeymap provides Keymap(emptyMap())) {
					editMenu = rememberFamilyMenu(CommandFamily.Edit)
				}
			}
			waitForIdle()
			assertEquals(emptyList(), checkNotNull(editMenu)(), "nothing is registered yet")

			runOnIdle { commands.registerAll(everyCommandTable()) }
			waitForIdle()

			assertEquals(listOf("Undo (off)", "Redo (off)", "---", "Settings…"), menuOutline(checkNotNull(editMenu)()), "the registration recomposed the menu")
		}
}