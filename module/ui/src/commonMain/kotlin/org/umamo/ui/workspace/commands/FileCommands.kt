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
 * Open / Save mean the native UMA document and nothing else; CMO3 and MOC3 are interop boundaries, so they
 * come and go through Import / Export.
 */

/**
 * The commands that make, open, save, or replace the whole document: New, Open, Save, Save As, and one
 * import per interop format.
 *
 * Split from [fileExportCommands] because the two register on different triggers: these handlers read
 * the document live through the app's holders, while export closes over the open document.  The artwork
 * import is NOT here - it adds to the open document rather than replacing it, so it registers with the
 * other artwork operations ([fileArtworkCommands]).
 *
 * @param Function onNew Starts a new, empty document (dirty-confirm first).
 * @param Function onOpen Opens a `.uma` (picker, dirty-confirm, load).
 * @param Function onSave Saves to the document's `.uma`, asking where on the first save.
 * @param Function onSaveAs Asks where to save.
 * @param Function canSave Whether the open document can be saved, queried live (gates Save and Save As).
 * @param Function onImportCmo3 Runs the CMO3 import (picker, dirty-confirm, load).
 * @param Function onImportMoc3 Runs the MOC3 import.
 * @return List<Command> The commands to register.
 */
internal fun fileCommands(
	onNew: () -> Unit,
	onOpen: () -> Unit,
	onSave: () -> Unit,
	onSaveAs: () -> Unit,
	canSave: () -> Boolean,
	onImportCmo3: () -> Unit,
	onImportMoc3: () -> Unit,
): List<Command> =
	listOf(
		Command("file.new", title = Res.string.cmd_file_new) { onNew() },
		Command("file.open", title = Res.string.cmd_file_open) { onOpen() },
		Command("file.save", title = Res.string.cmd_file_save, availability = CommandAvailability { canSave() }) { onSave() },
		Command("file.saveAs", title = Res.string.cmd_file_save_as, availability = CommandAvailability { canSave() }) { onSaveAs() },
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
 * @property List<AtlasTileId> retire  The tiles bound to the target layer that go with the move, as the
 *   accepted proposal named them (a fresh drawable a reload minted for the layer); empty otherwise.
 */
class RelinkRequest(
	val tileIds: List<AtlasTileId>,
	val ref: SourceLayerRef?,
	val retire: List<AtlasTileId> = emptyList(),
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
 * A request to remove one piece of source art from the atlas, the payload of the sources.deleteArt
 * command: a tile no drawable samples, which otherwise lingers on a page and in the Sources table.
 *
 * @property AtlasTileId tileId The tile to remove.
 */
class DeleteArtRequest(
	val tileId: AtlasTileId,
)

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
 * A request to mark or clear the ignore on one layer of a listed artwork file, the payload of the
 * sources.ignoreLayer command: while marked, a reload leaves the layer out of the rig.
 *
 * @property SourceLayerRef ref     The file and layer key.
 * @property Boolean        ignored True to mark, false to clear.
 */
class IgnoreLayerRequest(
	val ref: SourceLayerRef,
	val ignored: Boolean,
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
 * @property Function importArtwork Picks an artwork file and adds it to the open document.
 * @property Function reloadArtwork Re-reads the listed files that are present - those the scope names,
 *   or every one when it is null - and reloads the document from them.
 * @property Function relinkArtwork  Rebinds a tile, pulling the layer's art in when its file can be read.
 * @property Function matchArtwork   Reads every file it can and rebinds the unresolved bindings the matcher is confident about.
 * @property Function replaceArtwork Picks a file and repoints the named record at it.
 * @property Function deleteArt      Removes a tile no drawable samples from the atlas (and, under the import
 *   setting, marks its layer ignored with it).
 * @property Function ignoreLayer    Marks or clears the ignore on one layer, so a reload leaves it out or mints it again.
 * @property Function canReload      Whether any listed file could be re-read, queried live.
 */
class ArtworkOperations(
	val importArtwork: (areaId: String?) -> Unit,
	val reloadArtwork: (areaId: String?, scope: ReloadScope?) -> Unit,
	val relinkArtwork: (request: RelinkRequest, areaId: String?) -> Unit,
	val matchArtwork: (areaId: String?) -> Unit,
	val replaceArtwork: (request: ReplaceRequest, areaId: String?) -> Unit,
	val deleteArt: (request: DeleteArtRequest) -> Unit,
	val ignoreLayer: (request: IgnoreLayerRequest) -> Unit,
	val canReload: () -> Boolean,
)

/**
 * The artwork commands over the OPEN document: Import Artwork (a file's layers join the document), Reload
 * (every present file is re-read and the changed layers land), the Sources space's relink (a tile
 * rebound, with the layer's art pulled in), Match Automatically (the bindings the files no longer
 * resolve rebound to their confident matches), Replace Artwork (one record repointed at another file),
 * and the Sources row's Delete Art and ignore toggle.  Each is an undoable edit; the file-reading ones
 * land on the operation settings strip.
 *
 * Unlike the other file commands these are registered by the SHELL, not the app, with the app's
 * file-reading closures injected as a collaborator: the strip's area (the hovered work surface, else
 * the last one the pointer touched - the Sources header is the usual origin, and a panel hosts no
 * strip) is a question only the shell's routing can answer.  An app-registered handler would have no
 * area to give and the strip would show nowhere.  The collaborator is read at dispatch, so the table
 * survives a document swap without re-registration, and a null one (no puppet document) hides the
 * commands.
 *
 * @param CommandRouting routing The hovered-area resolver, read at dispatch.
 * @param Function       artwork Supplies the current orchestrations, or null when no document can take artwork.
 * @return List<Command> The commands to register.
 */
internal fun fileArtworkCommands(routing: CommandRouting, artwork: () -> ArtworkOperations?): List<Command> =
	listOf(
		// The one way artwork enters a document, from the File menu's Import row and from the Sources
		// space alike: a file's layers are ADDED to the open document as one undoable edit, the way
		// importing an object into a Blender scene adds to it rather than replacing the scene.
		Command(
			"file.importArtwork",
			title = Res.string.cmd_import_artwork,
			availability = CommandAvailability { artwork() != null },
		) { artwork()?.importArtwork?.invoke(routing.operationStripArea()) },
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
		Command(
			"sources.deleteArt",
			title = Res.string.cmd_sources_delete_art,
			availability = CommandAvailability { artwork() != null },
		) { argument ->
			val request = argument as? DeleteArtRequest ?: return@Command
			artwork()?.deleteArt?.invoke(request)
		},
		// No title, like the other argument-only commands: a row supplies the layer, and the palette has
		// nothing to offer without one.
		Command(
			"sources.ignoreLayer",
			title = null,
			availability = CommandAvailability { artwork() != null },
		) { argument ->
			val request = argument as? IgnoreLayerRequest ?: return@Command
			artwork()?.ignoreLayer?.invoke(request)
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