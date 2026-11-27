package org.umamo.ui.menu

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandFamily
import org.umamo.ui.action.chooseEveryRow
import org.umamo.ui.action.familyMenuOf
import org.umamo.ui.action.menuOutline
import org.umamo.ui.action.recordingRegistryOf
import org.umamo.ui.workspace.commands.logFolderCommands
import org.umamo.ui.workspace.commands.shellCommandTables
import org.umamo.ui.workspace.commands.updateCommands
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The Help menu carries Open Log Folder and Check for Updates exactly where the host registered them: a row whose
 * command was never registered would do nothing when chosen, which on a platform without a file manager to hand the
 * folder to, or without a network request to make, is every time.
 *
 * The two come from tables of their own, registered by the app ahead of the shell's, and still sit after Quick Setup
 * in one block with it: the block's order is declared, not the order its tables arrive in.
 */
class HelpMenuTest {
	/**
	 * The command ids of the Help menu's rows, in order, read by choosing each one.
	 *
	 * @param List<Command> tables The registered commands, in registration order.
	 * @return List<String> The rows' command ids.
	 */
	private fun helpMenuCommandIds(tables: List<Command>): List<String> {
		val dispatched = ArrayList<String>()
		chooseEveryRow(familyMenuOf(CommandFamily.Help, recordingRegistryOf(tables, dispatched)))

		return dispatched
	}

	@Test
	fun theRowsAreThereWhenTheHostSupportsThem() {
		val tables = shellCommandTables() + updateCommands {} + logFolderCommands {}

		assertEquals(
			listOf(
				"help.sourceCode",
				"help.webSite",
				"help.documentation",
				"help.quickSetup",
				"help.checkForUpdates",
				"help.openLogFolder",
				"help.credits",
				"help.about",
			),
			helpMenuCommandIds(tables),
		)
		assertEquals(
			listOf(
				"Source Code…",
				"Web Site…",
				"Documentation…",
				"---",
				"Quick Setup…",
				"Check for Updates…",
				"Open Log Folder…",
				"---",
				"Credits…",
				"About Umamo…",
			),
			menuOutline(familyMenuOf(CommandFamily.Help, recordingRegistryOf(tables, ArrayList()))),
			"three blocks, the host's rows joining Quick Setup's with no rule between",
		)
	}

	@Test
	fun theRowsAreLeftOutWhereTheHostCannot() {
		assertEquals(
			listOf("help.sourceCode", "help.webSite", "help.documentation", "help.quickSetup", "help.credits", "help.about"),
			helpMenuCommandIds(shellCommandTables()),
		)
	}

	/** The app registers the host's two tables before the shell registers its own, and the rows still land in place. */
	@Test
	fun theHostsRowsKeepTheirPlaceWhenTheirTablesRegisterFirst() {
		assertEquals(
			listOf(
				"help.sourceCode",
				"help.webSite",
				"help.documentation",
				"help.quickSetup",
				"help.checkForUpdates",
				"help.openLogFolder",
				"help.credits",
				"help.about",
			),
			helpMenuCommandIds(logFolderCommands {} + updateCommands {} + shellCommandTables()),
		)
	}
}