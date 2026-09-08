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
 * package and invert the dependency.  The add-artwork table is the exception: the shell registers it
 * (with the app's closure injected) because its operation strip needs the hovered area at dispatch.
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
 * @param Function onImportArtwork Runs the artwork import (layered art or a flat raster: picker, dirty-confirm, load).
 * @param Function onImportCmo3 Runs the CMO3 import (picker, dirty-confirm, load).
 * @param Function onImportMoc3 Runs the MOC3 import.
 * @return List<Command> The commands to register.
 */
internal fun fileCommands(onImportArtwork: () -> Unit, onImportCmo3: () -> Unit, onImportMoc3: () -> Unit): List<Command> =
	listOf(
		// Artwork is the headline entry: draw in the art program, import, rig.  Every layered and flat
		// raster format the registry reads comes in through this one row.
		Command("file.importArtwork", title = Res.string.cmd_import_artwork) { onImportArtwork() },
		Command("file.importCmo3", title = Res.string.cmd_import_cmo3) { onImportCmo3() },
		// MOC3 comes in through its own row rather than one merged "import" filter, keeping the
		// source-project / baked-runtime distinction visible in the UI.
		Command("file.importMoc3", title = Res.string.cmd_import_moc3) { onImportMoc3() },
	)

/**
 * The add-artwork command: a second (third, ...) artwork file joins the OPEN document as an undoable
 * edit - the Sources space's own action, also reachable from the palette.
 *
 * Unlike the other file commands this one is registered by the SHELL, not the app, with the app's
 * picker-and-read closure injected as a collaborator: the add lands on the operation settings strip,
 * and the strip's area (the hovered work surface, else the last one the pointer touched - the Sources
 * header button is the usual origin, and a panel hosts no strip) is a question only the shell's routing
 * can answer.  An app-registered handler would have no area to give and the strip would fall to the
 * shell's bottom edge.  The collaborator is read at dispatch, so the table survives a document swap
 * without re-registration, and a null one (no puppet document) hides the command.
 *
 * @param CommandRouting routing    The hovered-area resolver, read at dispatch.
 * @param Function       addArtwork Supplies the current add orchestration (picker, read, append, pack,
 *   commit) over the given area, or null when no document can take artwork.
 * @return List<Command> The command to register.
 */
internal fun fileAddArtworkCommands(routing: CommandRouting, addArtwork: () -> ((String?) -> Unit)?): List<Command> =
	listOf(
		Command(
			"file.addArtwork",
			title = Res.string.cmd_file_add_artwork,
			availability = CommandAvailability { addArtwork() != null },
		) { addArtwork()?.invoke(routing.operationStripArea()) },
	)

/**
 * The export commands, one per target format, available only while a puppet document is open.
 *
 * Neither carries a default chord.  With two formats there is no honest meaning for one "export"
 * accelerator, and the pair is one keystroke away in the palette and one row apart in the File menu -
 * a shortcut that silently favours whichever format was implemented first is worse than none.
 *
 * @param Function canExport      Whether the open document can be exported, queried live.
 * @param Function onExportCmo3   Runs the CMO3 export (picker, reconcile, write, report).
 * @param Function onExportMoc3   Runs the MOC3 export (picker, lower, write the family, report).
 * @return List<Command> The commands to register.
 * @note Registered in its own effect keyed on the document AND the session, so the handler always closes
 *   over the pair the export reconciles from - a mismatched pair would write one model's rig onto
 *   another's atlas pages.
 */
internal fun fileExportCommands(
	canExport: () -> Boolean,
	onExportCmo3: () -> Unit,
	onExportMoc3: () -> Unit,
): List<Command> =
	listOf(
		Command(
			"file.exportCmo3",
			title = Res.string.cmd_export_cmo3,
			availability = CommandAvailability { canExport() },
		) { onExportCmo3() },
		Command(
			"file.exportMoc3",
			title = Res.string.cmd_export_moc3,
			availability = CommandAvailability { canExport() },
		) { onExportMoc3() },
	)