package org.umamo.ui.app

import kotlinx.coroutines.launch
import org.umamo.format.FileKind
import org.umamo.storage.UmamoLog
import org.umamo.storage.platformFileFromSavedPath
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.UmaDocument
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_document_read_only
import org.umamo.ui.workspace.AlertRequest

/**
 * Everything that REPLACES the open document: New, Open, the CMO3 and MOC3 imports, and a file opened by
 * its stored path (Open Recent, or a file the operating system hands the running editor).  Each goes
 * through the unsaved-changes gate first, since a replace discards the session.
 *
 * Lives for the application's life, like the save controller whose gate it borrows.  An artwork import is
 * NOT here: it adds to the open document rather than replacing it (see ArtworkController).
 *
 * @property EditorAppServices      services The app's shared collaborators.
 * @property DocumentSaveController save     The owner of the unsaved-changes gate.
 */
internal class DocumentOpenController(
	private val services: EditorAppServices,
	private val save: DocumentSaveController,
) {
	/**
	 * Lands the result of a load: success records the recent file and swaps the document in, and a failure
	 * raises the shell's modal alert (document.openFailed) so the rigger sees why nothing opened.
	 *
	 * @param DocumentLoad load The load's result.
	 */
	fun applyDocumentLoad(load: DocumentLoad) {
		when (load) {
			is DocumentLoad.Loaded -> {
				val opened = load.document
				// Only a document that came from a file is recordable; a loaded one always did, but the read
				// is null-safe because the type it arrives as covers the new, unsaved document too.
				opened.path?.let { path -> services.settings.addRecentFile(path) }
				// What the document says about its artwork files, before anything probes them: the recorded
				// path is what a reload, the watcher, and the Sources space will all go by.
				for (source in (opened as? PuppetDocument)?.puppet?.sources.orEmpty()) {
					val recorded = source.path
					if (recorded == null) {
						UmamoLog.info("source artwork: '${source.name}' (${source.format}, ${source.layers.size} layer(s)) has no recorded path")
					} else {
						UmamoLog.info("source artwork: '${source.name}' (${source.format}, ${source.layers.size} layer(s)) recorded at $recorded")
					}
				}
				services.onOpen(opened)
				// A file that opened read-only says so once, up front: Save is greyed for the document's life, and a
				// rigger who edits it for an hour before finding that out has been misled.
				if (opened is UmaDocument && opened.isReadOnly) {
					val entries = opened.readOnlyReasons.joinToString { reason -> "${reason.path} (${reason.kind})" }
					services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_document_read_only, listOf(opened.displayName, entries)))
				}
			}
			is DocumentLoad.Failed -> services.commandRegistry.invoke("document.openFailed", load.failure)
		}
	}

	/**
	 * File > New: an empty document, which replaces the open one like any other document swap - so a dirty
	 * document asks first.
	 */
	fun newDocument() {
		save.confirmIfDirty { services.onOpen(newBlankDocument()) }
	}

	/**
	 * File > Open: the native document, read whole - the one thing Open means.  A dirty document asks first,
	 * with Save on offer, like every other document replace.
	 */
	fun openViaPicker() {
		pickAndLoad(FileKind.Uma)
	}

	/**
	 * File > Import > CMO3.  The filter is CMO3 alone: artwork comes in through Import Artwork, which adds to
	 * the open document rather than replacing it, and MOC3 through its own row, which keeps the distinction
	 * between a source project and a baked runtime visible in the UI.  Open and Save are reserved for the
	 * native UMA format.
	 */
	fun importCmo3ViaPicker() {
		pickAndLoad(FileKind.Cmo3)
	}

	/**
	 * File > Import > MOC3.  The picked `.moc3` routes through loadDocument's file-level MOC3 branch, which
	 * discovers the model3.json manifest, the cdi3 display info, and the atlas pages beside the file.
	 */
	fun importMoc3ViaPicker() {
		pickAndLoad(FileKind.Moc3)
	}

	/**
	 * Opens the file at a stored path the way a recent file opens, unsaved-changes check included.  The
	 * configured artwork import options ride along because a stored path may name an artwork file, which
	 * opens as a rig born from it.
	 *
	 * @param String path The stored path or uri.
	 */
	fun openStoredPath(path: String) {
		save.confirmIfDirty {
			services.scope.launch {
				applyDocumentLoad(loadDocument(platformFileFromSavedPath(path), services.configuredArtworkImportOptions()))
			}
		}
	}

	/**
	 * Asks for one file of [kind] and loads it over the open document, behind the unsaved-changes gate.
	 * FileKit's native dialog supplies its own (OS-localized) title, so none is passed.
	 *
	 * @param FileKind kind The one format the picker offers.
	 */
	private fun pickAndLoad(kind: FileKind) {
		save.confirmIfDirty {
			services.scope.launch {
				services.filePicker.openFile(listOf(kind.extension))?.let { picked ->
					applyDocumentLoad(loadDocument(picked))
				}
			}
		}
	}
}