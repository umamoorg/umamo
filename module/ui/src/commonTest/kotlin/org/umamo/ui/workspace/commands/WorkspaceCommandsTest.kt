package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.workspace.ShellOverlayState
import org.umamo.ui.workspace.SpaceKind
import org.umamo.ui.workspace.layout.InterfaceLayout
import org.umamo.ui.workspace.layout.LeafArea
import org.umamo.ui.workspace.layout.Workspace
import org.umamo.ui.workspace.layout.WorkspaceLayoutController
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Rename, Duplicate, and Delete Workspace: each acts on the workspace whose id it is run with, which is how a tab's
 * own menu aims it at that tab, and on the active one when run with none, from the Workspace menu, the palette, or a
 * key.
 */
class WorkspaceCommandsTest {
	/**
	 * A layout of [workspaces] with the first one active.
	 *
	 * @param Workspace workspaces The workspaces, in tab order.
	 * @return WorkspaceLayoutController The layout's controller.
	 */
	private fun layoutOf(vararg workspaces: Workspace): WorkspaceLayoutController = WorkspaceLayoutController(InterfaceLayout(workspaces.first().id, workspaces.toList())) {}

	/**
	 * A workspace over a single viewport leaf.
	 *
	 * @param String id The workspace id.
	 * @param String? name The stored name, or null for a workspace that goes by its built-in title.
	 * @return Workspace The workspace.
	 */
	private fun workspace(id: String, name: String? = null): Workspace = Workspace(id = id, root = LeafArea("leaf-$id", SpaceKind.Viewport2D), name = name)

	/**
	 * The workspace table over [controller], by command id.
	 *
	 * @param WorkspaceLayoutController controller The layout the commands rewrite.
	 * @return Map<String, Command> The commands.
	 */
	private fun commandsOver(controller: WorkspaceLayoutController): Map<String, Command> =
		workspaceCommands(controller, ShellOverlayState()) { fixtureWorkspaceNames() }.associateBy { command -> command.id }

	/**
	 * The tab titles of [controller]'s layout, in order.
	 *
	 * @param WorkspaceLayoutController controller The layout.
	 * @return List<String> Each workspace's title.
	 */
	private fun titles(controller: WorkspaceLayoutController): List<String> = controller.layout.workspaces.map { workspace -> fixtureWorkspaceNames().titleOf(workspace) }

	/** Rename opens the in-place editor on the tab it is aimed at, and renames nothing until the editor commits. */
	@Test
	fun renameOpensTheEditorOnTheWorkspaceItIsAimedAt() {
		val controller = layoutOf(workspace("alpha", "Alpha"), workspace("bravo", "Bravo"))
		val rename = commandsOver(controller).getValue("workspace.rename")

		rename.handler.run("bravo")

		assertEquals("bravo", controller.renamingWorkspaceId)
		assertEquals(listOf("Alpha", "Bravo"), titles(controller), "nothing is renamed yet")

		controller.rename("bravo", "Beta")

		assertNull(controller.renamingWorkspaceId, "the commit closes the editor")
		assertEquals(listOf("Alpha", "Beta"), titles(controller))
	}

	/** Run with no id - from the menu bar, the palette, or a key - Rename is the active workspace's. */
	@Test
	fun renameWithNoIdIsTheActiveWorkspaces() {
		val controller = layoutOf(workspace("alpha", "Alpha"), workspace("bravo", "Bravo"))
		val rename = commandsOver(controller).getValue("workspace.rename")
		controller.setActiveWorkspace("bravo")

		rename.handler.run(null)

		assertEquals("bravo", controller.renamingWorkspaceId)

		controller.cancelRename()

		assertNull(controller.renamingWorkspaceId)
		assertEquals(listOf("Alpha", "Bravo"), titles(controller), "an abandoned edit renames nothing")
	}

	/** An id the layout does not hold opens no editor: a tab's menu can outlive its tab. */
	@Test
	fun renameOfAWorkspaceThatIsGoneOpensNothing() {
		val controller = layoutOf(workspace("alpha", "Alpha"))

		commandsOver(controller).getValue("workspace.rename").handler.run("gone")

		assertNull(controller.renamingWorkspaceId)
	}

	/**
	 * Duplicate copies the workspace it is aimed at, puts the copy after it, activates it, and names it from the
	 * original's title: a built-in that was never renamed has no stored name, and its copy is named from the title
	 * its tab shows.
	 */
	@Test
	fun duplicateCopiesTheWorkspaceItIsAimedAtAndNamesTheCopyFromItsTitle() {
		val controller = layoutOf(workspace("modelling"), workspace("bravo", "Bravo"), workspace("charlie", "Charlie"))
		val duplicate = commandsOver(controller).getValue("workspace.duplicate")

		duplicate.handler.run("bravo")

		assertEquals(listOf("Modelling", "Bravo", "Bravo copy", "Charlie"), titles(controller))
		assertEquals("Bravo copy", controller.layout.activeWorkspace()?.name, "the copy is the active workspace")

		controller.setActiveWorkspace("modelling")
		duplicate.handler.run(null)

		assertEquals(listOf("Modelling", "Modelling copy", "Bravo", "Bravo copy", "Charlie"), titles(controller), "with no id, the active workspace is the one copied")

		duplicate.handler.run("bravo")

		assertEquals(listOf("Modelling", "Modelling copy", "Bravo", "Bravo copy 2", "Bravo copy", "Charlie"), titles(controller), "a second copy takes the next free name")
	}

	/** Delete removes the workspace it is aimed at, or the active one, and ends a rename in progress on that tab. */
	@Test
	fun deleteRemovesTheWorkspaceItIsAimedAt() {
		val controller = layoutOf(workspace("alpha", "Alpha"), workspace("bravo", "Bravo"), workspace("charlie", "Charlie"))
		val commands = commandsOver(controller)
		val delete = commands.getValue("workspace.delete")
		commands.getValue("workspace.rename").handler.run("bravo")

		delete.handler.run("bravo")

		assertEquals(listOf("Alpha", "Charlie"), titles(controller))
		assertNull(controller.renamingWorkspaceId, "the tab being renamed is gone, and its editor with it")
		assertEquals("alpha", controller.layout.activeWorkspaceId, "the active workspace was not the one deleted")

		delete.handler.run(null)

		assertEquals(listOf("Charlie"), titles(controller), "with no id, the active workspace is the one deleted")
	}

	/** The strip never goes empty, so Delete does not apply to the last workspace - which grays its row everywhere. */
	@Test
	fun deleteDoesNotApplyToTheLastWorkspace() {
		val controller = layoutOf(workspace("alpha", "Alpha"), workspace("bravo", "Bravo"))
		val commands = commandsOver(controller)
		val delete = commands.getValue("workspace.delete")

		assertTrue(delete.availability.isAvailable())

		delete.handler.run("bravo")

		assertFalse(delete.availability.isAvailable(), "one workspace is left")
		assertTrue(commands.getValue("workspace.rename").availability.isAvailable(), "the others still apply to it")
		assertTrue(commands.getValue("workspace.duplicate").availability.isAvailable())
	}

	/** New Workspace is what the strip's "+" button runs: a workspace named from the localized base name, deduped. */
	@Test
	fun newWorkspaceIsNamedFromTheBaseNameAndActivated() {
		val controller = layoutOf(workspace("alpha", "Alpha"))
		val create = commandsOver(controller).getValue("workspace.new")

		create.handler.run(null)
		create.handler.run(null)

		assertEquals(listOf("Alpha", "Workspace", "Workspace 2"), titles(controller))
		assertEquals("Workspace 2", controller.layout.activeWorkspace()?.name)
	}
}