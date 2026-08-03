package org.umamo.ui.workspace.commands

import org.umamo.ui.action.Command
import org.umamo.ui.action.CommandAvailability
import org.umamo.ui.resources.*

/*
 * The document import / export commands.
 *
 * These are the one group the app registers rather than the shell, because their work needs the file
 * picker, the document loader, and the CMO3 codec - all of which sit above the shell (and, for the codec,
 * off commonMain entirely).  Only the TABLE lives here: each builder takes the action as a plain lambda,
 * so the ids, titles, and availability tiers sit with every other command table while the app keeps the
 * document logic.  Registering them here instead would drag the whole document layer into the shell's
 * package and invert the dependency.
 *
 * Import / Export rather than Open / Save is deliberate: CMO3 and MOC3 are interop boundaries, and
 * Open / Save is reserved for the native UMA format.
 */

/**
 * The import commands, one per source format.
 *
 * Split from [fileExportCommands] because the two register on different triggers: an import handler
 * depends on nothing that changes while the app runs, while export closes over the open document.
 *
 * @param Function onImportCmo3 Runs the CMO3 import (picker, dirty-confirm, load).
 * @param Function onImportMoc3 Runs the MOC3 import.
 * @return List<Command> The commands to register.
 */
internal fun fileCommands(onImportCmo3: () -> Unit, onImportMoc3: () -> Unit): List<Command> =
	listOf(
		Command("file.importCmo3", title = Res.string.cmd_import_cmo3) { onImportCmo3() },
		// MOC3 comes in through its own row rather than one merged "import" filter, keeping the
		// source-project / baked-runtime distinction visible in the UI.
		Command("file.importMoc3", title = Res.string.cmd_import_moc3) { onImportMoc3() },
	)

/**
 * The CMO3 export command, available only while a puppet document is open.
 *
 * @param Function canExport Whether the open document can be exported, queried live.
 * @param Function onExport Runs the export (picker, reconcile, write, report).
 * @return List<Command> The commands to register.
 * @note Registered in its own effect keyed on the document AND the session, so the handler always closes
 *   over the pair the export reconciles from - a mismatched pair would write one model's rig onto
 *   another's atlas pages.
 */
internal fun fileExportCommands(canExport: () -> Boolean, onExport: () -> Unit): List<Command> =
	listOf(
		Command(
			"file.exportCmo3",
			title = Res.string.cmd_export_cmo3,
			availability = CommandAvailability { canExport() },
		) { onExport() },
	)
