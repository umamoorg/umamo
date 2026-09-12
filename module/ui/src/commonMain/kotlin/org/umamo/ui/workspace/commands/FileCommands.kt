package org.umamo.ui.workspace.commands

import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.SourceLayerRef
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
 * package and invert the dependency.  The artwork table ([fileArtworkCommands]) is the exception: the
 * shell registers it (with the app's closures injected) because its operation strip needs the hovered
 * area at dispatch.
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
 * A request to rebind one or more tiles to one layer, the payload of the sources.relink command: one
 * tile from the tile chip or a drop, every tile bound to a lost key from a review row, so those land
 * as one step.
 *
 * @property List<AtlasTileId> tileIds The tiles.
 * @property SourceLayerRef?   ref     The binding they take, or null to unbind.
 */
class RelinkRequest(
	val tileIds: List<AtlasTileId>,
	val ref: SourceLayerRef?,
) {
	/**
	 * The one-tile form.
	 *
	 * @param AtlasTileId     tileId The tile.
	 * @param SourceLayerRef? ref    The binding it takes, or null to unbind.
	 */
	constructor(tileId: AtlasTileId, ref: SourceLayerRef?) : this(listOf(tileId), ref)
}

/**
 * A request to repoint one artwork record at another file, the payload of the sources.replaceArtwork
 * command; the app picks the file.
 *
 * @property ArtSourceId sourceId The record to repoint.
 */
class ReplaceRequest(
	val sourceId: ArtSourceId,
)

/**
 * Which listed files a reload covers, the optional payload of the document.reloadArtwork command: the
 * watcher names the files that changed, so the rest are not re-read; a press of Reload passes none
 * and covers every present file.
 *
 * @property Set<ArtSourceId> sourceIds The files to re-read.
 */
class ReloadScope(
	val sourceIds: Set<ArtSourceId>,
)

/**
 * The app's artwork orchestrations the shell's table dispatches to: each reads files the way only the
 * app can (the picker, a path on the platform's file system) and lands the result on the session.
 * Every one takes the area its operation strip shows in, resolved by the shell at dispatch.
 *
 * @property Function addArtwork    Picks a file and adds it to the open document.
 * @property Function reloadArtwork Re-reads the listed files that are present - those the scope names,
 *   or every one when it is null - and reloads the document from them.
 * @property Function relinkArtwork  Rebinds a tile, pulling the layer's art in when its file can be read.
 * @property Function matchArtwork   Reads every file it can and rebinds the unresolved bindings the matcher is confident about.
 * @property Function replaceArtwork Picks a file and repoints the named record at it.
 * @property Function canReload      Whether any listed file could be re-read, queried live.
 */
class ArtworkOperations(
	val addArtwork: (areaId: String?) -> Unit,
	val reloadArtwork: (areaId: String?, scope: ReloadScope?) -> Unit,
	val relinkArtwork: (request: RelinkRequest, areaId: String?) -> Unit,
	val matchArtwork: (areaId: String?) -> Unit,
	val replaceArtwork: (request: ReplaceRequest, areaId: String?) -> Unit,
	val canReload: () -> Boolean,
)

/**
 * The artwork commands over the OPEN document: Add Artwork (a second file joins the document), Reload
 * (every present file is re-read and the changed layers land), the Sources space's relink (a tile
 * rebound, with the layer's art pulled in), Match Automatically (the bindings the files no longer
 * resolve rebound to their confident matches), and Replace Artwork (one record repointed at another
 * file).  Each is an undoable edit and lands on the operation settings strip.
 *
 * Unlike the other file commands these are registered by the SHELL, not the app, with the app's
 * file-reading closures injected as a collaborator: the strip's area (the hovered work surface, else
 * the last one the pointer touched - the Sources header is the usual origin, and a panel hosts no
 * strip) is a question only the shell's routing can answer.  An app-registered handler would have no
 * area to give and the strip would fall to the shell's bottom edge.  The collaborator is read at
 * dispatch, so the table survives a document swap without re-registration, and a null one (no puppet
 * document) hides the commands.
 *
 * @param CommandRouting routing The hovered-area resolver, read at dispatch.
 * @param Function       artwork Supplies the current orchestrations, or null when no document can take artwork.
 * @return List<Command> The commands to register.
 */
internal fun fileArtworkCommands(routing: CommandRouting, artwork: () -> ArtworkOperations?): List<Command> =
	listOf(
		Command(
			"file.addArtwork",
			title = Res.string.cmd_file_add_artwork,
			availability = CommandAvailability { artwork() != null },
		) { artwork()?.addArtwork?.invoke(routing.operationStripArea()) },
		Command(
			"document.reloadArtwork",
			title = Res.string.cmd_document_reload_artwork,
			availability = CommandAvailability { artwork()?.canReload?.invoke() == true },
		) { argument -> artwork()?.reloadArtwork?.invoke(routing.operationStripArea(), argument as? ReloadScope) },
		Command(
			"sources.relink",
			title = Res.string.cmd_sources_relink,
			availability = CommandAvailability { artwork() != null },
		) { argument ->
			val request = argument as? RelinkRequest ?: return@Command
			artwork()?.relinkArtwork?.invoke(request, routing.operationStripArea())
		},
		Command(
			"sources.matchAutomatically",
			title = Res.string.cmd_sources_match_automatically,
			availability = CommandAvailability { artwork() != null },
		) { artwork()?.matchArtwork?.invoke(routing.operationStripArea()) },
		Command(
			"sources.replaceArtwork",
			title = Res.string.cmd_sources_replace_artwork,
			availability = CommandAvailability { artwork() != null },
		) { argument ->
			val request = argument as? ReplaceRequest ?: return@Command
			artwork()?.replaceArtwork?.invoke(request, routing.operationStripArea())
		},
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