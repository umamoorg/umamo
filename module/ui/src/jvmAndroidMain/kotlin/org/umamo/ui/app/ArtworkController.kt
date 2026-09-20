package org.umamo.ui.app

import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.readBytes
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import org.umamo.edit.NoticePlacement
import org.umamo.edit.deleteTile
import org.umamo.edit.setLayerIgnored
import org.umamo.edit.setTileSources
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.reimport.InventoryLayerMatcher
import org.umamo.reimport.WatchedReloadResult
import org.umamo.runtime.model.ArtSourceId
import org.umamo.storage.UmamoLog
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.document.ReadArtwork
import org.umamo.ui.document.artworkImportExtensions
import org.umamo.ui.document.artworkImportOptions
import org.umamo.ui.document.fileDisplayName
import org.umamo.ui.document.fileModifiedAtMillis
import org.umamo.ui.document.isFileSystemPath
import org.umamo.ui.document.readArtwork
import org.umamo.ui.document.readArtworkAt
import org.umamo.ui.document.readSourceArt
import org.umamo.ui.document.systemSourceFilePresence
import org.umamo.ui.model.AddArtworkRequest
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.MatchArtworkRequest
import org.umamo.ui.model.RelinkArtworkRequest
import org.umamo.ui.model.ReloadArtworkRequest
import org.umamo.ui.model.ReloadArtworkResult
import org.umamo.ui.model.ReloadEntry
import org.umamo.ui.model.ReplaceArtworkRequest
import org.umamo.ui.model.SourceSuggestionState
import org.umamo.ui.model.SourceSuggestions
import org.umamo.ui.model.runAddArtwork
import org.umamo.ui.model.runMatchArtwork
import org.umamo.ui.model.runRelinkArtwork
import org.umamo.ui.model.runReloadArtwork
import org.umamo.ui.model.runReplaceArtwork
import org.umamo.ui.model.scoreSourceSuggestions
import org.umamo.ui.settings.IMPORT_DELETE_ART_IGNORES_LAYER_KEY
import org.umamo.ui.workspace.commands.ArtworkOperations
import org.umamo.ui.workspace.commands.ImportArtworkRequest
import org.umamo.ui.workspace.commands.RelinkRequest
import org.umamo.ui.workspace.commands.ReloadScope
import org.umamo.ui.workspace.commands.ReplaceRequest

/**
 * An artwork file as picked and read for an add or a replace.
 *
 * @property ReadArtwork         read       The parsed art, its format, and its content hash.
 * @property ArtSourceDescriptor descriptor The file's name, path, format, and hash as the document records them.
 */
private class PickedArtwork(
	val read: ReadArtwork,
	val descriptor: ArtSourceDescriptor,
)

/**
 * The artwork operations over ONE open puppet document: Import Artwork, Reload, the Sources space's relink,
 * Match Automatically, Replace Artwork, Delete Art, and the layer ignore.  Each reads files the way only
 * the app can - the picker, a path on the platform's file system - and lands the result on the session as
 * one undoable edit.  None of them swaps the document or asks over unsaved changes: artwork is ADDED to
 * the open document, the way importing an object into a Blender scene adds to it.
 *
 * Made per document and holding its [OpenPuppet], so every operation lands on the session its files
 * belong to.  The shell registers the commands (see fileArtworkCommands) and reads [operations] at
 * dispatch, because the area an operation's strip shows in is a question only the shell's routing answers.
 *
 * @property EditorAppServices services The app's shared collaborators.
 * @property OpenPuppet        puppet   The open puppet document with its session and pages.
 * @property DocumentWatch?    watch    The document's artwork watcher, told how each reload ended, or null.
 */
internal class ArtworkController(
	private val services: EditorAppServices,
	private val puppet: OpenPuppet,
	private val watch: DocumentWatch?,
) {
	/**
	 * The suggestions the last operation that read a file scored for its unresolved bindings, for the
	 * Sources space's review chips.  An operation replaces what stands for the files it read and leaves the
	 * rest; a new document starts empty, since it gets a new controller.
	 */
	private val suggestions = MutableStateFlow<SourceSuggestions>(emptyMap())

	/** The published suggestions as the Sources space reads them: one instance for the controller's life. */
	val suggestionState: SourceSuggestionState = SourceSuggestionState(suggestions)

	/**
	 * The host every artwork operation over the open document lands through: the same one the repack
	 * builds, so an add, a reload, and a relink share its report route and its resolver pre-warm.
	 */
	private val host: AtlasRepackHost =
		AtlasRepackHost(
			session = puppet.session,
			artRasters = puppet.document.artRasters,
			sessionAtlasPages = puppet.atlasPages,
			premultipliedAlpha = puppet.document.textures.premultipliedAlpha,
			scope = services.scope,
			report = { report -> services.commandRegistry.invoke("document.repackReport", report) },
			rememberOptions = { _, _ -> },
		)

	/**
	 * The operations as the shell's artwork command table takes them.  A reload has something to read when
	 * any listed file has a real path - checked without touching the disk, since the palette asks on every
	 * listing; a missing file is found out by the reload.
	 */
	val operations: ArtworkOperations =
		ArtworkOperations(
			importArtwork = { request, areaId -> importArtwork(request, areaId) },
			reloadArtwork = { areaId, reloadScope -> reloadArtworkFromDisk(areaId, reloadScope) },
			relinkArtwork = { request, areaId -> relinkArtwork(request, areaId) },
			matchArtwork = { areaId -> matchArtwork(areaId) },
			replaceArtwork = { request, areaId -> replaceArtwork(request, areaId) },
			deleteArt = { request -> puppet.session.deleteTile(request.tileId, ignoreLayer = services.settings.getBoolean(IMPORT_DELETE_ART_IGNORES_LAYER_KEY) == true) },
			ignoreLayer = { request -> puppet.session.setLayerIgnored(request.ref, request.ignored) },
			canReload = { puppet.session.model.value.sources.any { source -> source.path?.let(::isFileSystemPath) == true } },
		)

	/**
	 * Publishes what one operation scored: the files it read take its proposals (an empty map for a file it
	 * read and found nothing for), and every other file keeps whatever was published last.
	 *
	 * @param Set               covered   The files the operation read.
	 * @param SourceSuggestions published What it scored for them.
	 */
	private fun publishSuggestions(covered: Set<ArtSourceId>, published: SourceSuggestions) {
		suggestions.value = suggestions.value.filterKeys { (sourceId, _) -> sourceId !in covered } + published
	}

	/**
	 * Picks an artwork file and reads it, describing it the way the document records a file.  A file that
	 * will not read raises the same alert an open would.
	 *
	 * @return PickedArtwork? The read file, or null for a cancelled picker or a file that would not read.
	 */
	private suspend fun pickArtwork(): PickedArtwork? {
		val picked = services.filePicker.openFile(artworkImportExtensions) ?: return null
		val bytes =
			runCatching { picked.readBytes() }.getOrElse { failure ->
				UmamoLog.error("failed to read ${picked.name}", failure)
				services.commandRegistry.invoke("document.openFailed", DocumentOpenFailure(DocumentOpenError.ReadFailed, picked.name))
				return null
			}
		val read =
			readArtwork(bytes, picked.name) ?: run {
				services.commandRegistry.invoke("document.openFailed", DocumentOpenFailure(DocumentOpenError.Unrecognized, picked.name))
				return null
			}
		val path = picked.absolutePath()
		return PickedArtwork(read, ArtSourceDescriptor(picked.name, path, read.kind.extension, read.contentHash, path.let(::fileModifiedAtMillis)))
	}

	/**
	 * The artwork import: each file's layers join the open document as one undoable edit.  Every layered and
	 * flat-raster format the registry reads comes in through this one path, from the File menu's Import row,
	 * the Sources space, and a drop on the window alike.  The options carry the parameter template, which
	 * seeds only when the document has no parameters of its own - a rig's first artwork.
	 *
	 * With no [request] it asks for a file.  With one it adds the files it was handed, one after another and
	 * each awaited: an add packs against the model as it stood when it began, so two running at once would
	 * each pack as though the other had not happened.  A file that will not read is reported and the rest
	 * still land.
	 *
	 * @param ImportArtworkRequest? request The files to add, or null to ask for one.
	 * @param String?               areaId  The area the command fired over, resolved by the shell before the
	 *   picker opens; it is where the operation strip shows once the import lands.
	 */
	private fun importArtwork(request: ImportArtworkRequest?, areaId: String?) {
		services.scope.launch {
			if (request == null) {
				val picked = pickArtwork() ?: return@launch
				addArtwork(picked, areaId)
				return@launch
			}
			for (path in request.paths) {
				addArtwork(readArtworkForPath(path) ?: continue, areaId)
			}
		}
	}

	/**
	 * Reads the artwork file at [path], describing it the way the document records a file.
	 *
	 * A file that yields no art raises the unrecognized-file alert an open would, so a dropped file that
	 * turns out not to be artwork is named rather than silently skipped.  The reasons are not told apart -
	 * a file gone since the drop, one the reader refuses, and one that is simply not art all reach the same
	 * row - because the reader reports them as one absence and the distinction would not change what the
	 * rigger does about it.
	 *
	 * @param String path The file's stored path.
	 * @return PickedArtwork? The read file, or null when no art came out of it.
	 */
	private suspend fun readArtworkForPath(path: String): PickedArtwork? {
		val name = fileDisplayName(path)
		val read =
			readArtworkAt(path) ?: run {
				services.commandRegistry.invoke("document.openFailed", DocumentOpenFailure(DocumentOpenError.Unrecognized, name))
				return null
			}
		return PickedArtwork(read, ArtSourceDescriptor(name, path, read.kind.extension, read.contentHash, read.lastModified))
	}

	/**
	 * Adds one read file's layers to the open document as one undoable edit.
	 *
	 * @param PickedArtwork artwork The read file and how the document records it.
	 * @param String?       areaId  The area the operation strip shows in.
	 */
	private suspend fun addArtwork(artwork: PickedArtwork, areaId: String?) {
		runAddArtwork(
			host,
			AddArtworkRequest(artwork.read.art, artwork.descriptor, services.configuredArtworkImportOptions()),
			areaId,
		)
	}

	/**
	 * Reloads the listed artwork files that are present on disk - those the scope names, or every one - as
	 * one undo step; a file that cannot be read is logged and skipped.  Real file-system paths only: a
	 * platform uri has no reader here, so a document opened through one reloads nothing.  The watcher hears
	 * how it ended, so it knows whether to wait for the model's new hashes, try again, or let go.
	 *
	 * @param String?      areaId      The area the operation strip shows in.
	 * @param ReloadScope? reloadScope The files to re-read, or null for every present file.
	 */
	private fun reloadArtworkFromDisk(areaId: String?, reloadScope: ReloadScope?) {
		services.scope.launch {
			val entries = ArrayList<ReloadEntry>()
			val covered = LinkedHashSet<ArtSourceId>()
			for (source in puppet.session.model.value.sources) {
				if (reloadScope != null && source.id !in reloadScope.sourceIds) {
					continue
				}
				val path = source.path ?: continue
				if (systemSourceFilePresence(path) != true) {
					continue
				}
				covered.add(source.id)
				val read = readArtworkAt(path)
				if (read == null) {
					UmamoLog.warn("reload artwork: '${source.name}' at $path could not be read; skipped")
					continue
				}
				entries.add(ReloadEntry(source.id, read.art, read.contentHash, read.lastModified))
			}
			if (entries.isEmpty()) {
				puppet.session.emitNotice("notice.reload.noFiles", NoticePlacement.StatusBar)
				watch?.coordinator?.reloadFinished(covered, WatchedReloadResult.Abandoned)
				return@launch
			}
			// A reload that lands publishes what its matcher left under the bar (scored before it minted);
			// one that found nothing changed re-scores the files as they stand.
			val result = runReloadArtwork(host, ReloadArtworkRequest(entries, artworkImportOptions(), InventoryLayerMatcher.DEFAULT_THRESHOLD), areaId) { scored -> publishSuggestions(covered, scored) }
			if (result == ReloadArtworkResult.NothingChanged) {
				publishSuggestions(covered, scoreSourceSuggestions(host, entries))
			}
			watch?.coordinator?.reloadFinished(
				entries.mapTo(LinkedHashSet()) { entry -> entry.sourceId },
				when (result) {
					ReloadArtworkResult.Applied -> WatchedReloadResult.Applied
					ReloadArtworkResult.NothingChanged -> WatchedReloadResult.NothingChanged
					ReloadArtworkResult.Superseded -> WatchedReloadResult.Superseded
					ReloadArtworkResult.Refused -> WatchedReloadResult.Abandoned
				},
			)
		}
	}

	/**
	 * Rebinds one or more tiles as one step.  An unbind is the plain binding edit; a binding to a layer
	 * pulls the layer's art in when its file is on disk - or, for a CMO3-origin document whose file is not,
	 * from the layer PNGs the official editor decomposed into the CMO3 at import - and changes the bindings
	 * alone when neither can be read.
	 *
	 * @param RelinkRequest request The tiles and the binding they take.
	 * @param String?       areaId  The area the operation strip shows in.
	 */
	private fun relinkArtwork(request: RelinkRequest, areaId: String?) {
		val ref = request.ref
		if (ref == null) {
			puppet.session.setTileSources(request.tileIds, null)
			return
		}
		services.scope.launch {
			val source = puppet.session.model.value.sources.firstOrNull { candidate -> candidate.id == ref.sourceId }
			val read = source?.let { listed -> readSourceArt(puppet.document, listed) }
			if (read?.fromCmo3 == true) {
				UmamoLog.info("relink artwork: '${ref.layerKey}' read from the CMO3's own decomposed layer image, since its file could not be read on this machine")
			}
			runRelinkArtwork(host, RelinkArtworkRequest(request.tileIds, ref, read?.art, artworkImportOptions(), request.retire), areaId)
		}
	}

	/**
	 * Reads every listed file it can and rebinds the bindings the files no longer resolve to the layers the
	 * matcher is confident about, as one undo step with the threshold on the strip; what it is not sure
	 * about it publishes as suggestions for the rows that need review.
	 *
	 * @param String? areaId The area the operation strip shows in.
	 */
	private fun matchArtwork(areaId: String?) {
		services.scope.launch {
			val entries = ArrayList<ReloadEntry>()
			for (source in puppet.session.model.value.sources) {
				val read = readSourceArt(puppet.document, source) ?: continue
				entries.add(ReloadEntry(source.id, read.art, read.contentHash, read.lastModified))
			}
			if (entries.isEmpty()) {
				puppet.session.emitNotice("notice.reload.noFiles", NoticePlacement.StatusBar)
				return@launch
			}
			val covered = entries.mapTo(HashSet()) { entry -> entry.sourceId }
			runMatchArtwork(
				host,
				MatchArtworkRequest(entries, InventoryLayerMatcher.DEFAULT_THRESHOLD, artworkImportOptions(), standing = suggestions.value),
				areaId,
			) { scored -> publishSuggestions(covered, scored) }
		}
	}

	/**
	 * Repoints one listed file at another the rigger picks: what the new file resolves by key reloads, what
	 * the matcher is confident about rebinds, and the rest is flagged for review with suggestions scored
	 * against the new file's layers.
	 *
	 * @param ReplaceRequest request The record to repoint.
	 * @param String?        areaId  The area the operation strip shows in.
	 */
	private fun replaceArtwork(request: ReplaceRequest, areaId: String?) {
		services.scope.launch {
			val picked = pickArtwork() ?: return@launch
			runReplaceArtwork(
				host,
				ReplaceArtworkRequest(request.sourceId, picked.read.art, picked.descriptor, picked.read.contentHash, artworkImportOptions(), InventoryLayerMatcher.DEFAULT_THRESHOLD),
				areaId,
			) { scored -> publishSuggestions(setOf(request.sourceId), scored) }
		}
	}
}