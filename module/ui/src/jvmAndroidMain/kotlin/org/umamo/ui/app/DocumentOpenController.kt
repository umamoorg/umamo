package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.umamo.edit.EditorSession
import org.umamo.edit.NoticePlacement
import org.umamo.format.FileKind
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.runtime.model.PuppetModel
import org.umamo.storage.UmamoLog
import org.umamo.storage.platformFileFromSavedPath
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.UmaDocument
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.progress.ProgressHandle
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.track
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_document_read_only
import org.umamo.ui.workspace.AlertRequest

/**
 * Everything that REPLACES the open document: New, Open, the CMO3 and MOC3 imports, and a file opened by
 * its stored path (Open Recent, or a file the operating system hands the running editor).  Each goes
 * through the unsaved-changes gate first, since a replace discards the session.
 *
 * A file is read and built off the UI thread, with its progress on the status bar, so the window stays
 * responsive while a large document opens.  That leaves the rigger free to keep editing the document being
 * replaced, so a load that finds the document edited since the gate passed asks again before it swaps.  One
 * open runs at a time: another open, or New, while one runs is refused with a notice rather than raced.
 *
 * Lives for the application's life, like the save controller whose gate it borrows.  An artwork import is
 * NOT here: it adds to the open document rather than replacing it (see ArtworkController).
 *
 * @property EditorAppServices      services The app's shared collaborators.
 * @property DocumentSaveController save     The owner of the unsaved-changes gate.
 * @property Function               load     Reads and builds one file into a document; the real loader by
 *   default, and a stand-in for tests.
 */
internal class DocumentOpenController(
	private val services: EditorAppServices,
	private val save: DocumentSaveController,
	private val load: suspend (PlatformFile, SourceArtImportOptions, ProgressHandle?) -> DocumentLoad = ::loadDocument,
) {
	/** The open running now, from its file choice to its swap; null when none runs.  Touched on the UI thread only. */
	private var opening: Job? = null

	/** Whether an open is running now. */
	val isOpening: Boolean
		get() = opening != null

	/**
	 * Lands the result of a load: success records the recent file and swaps the document in, and a failure
	 * raises the shell's modal alert (document.openFailed) so the rigger sees why nothing opened.  The swap waits
	 * for a model export started while the prompt was up or the file was read: the export reads the document
	 * being replaced.
	 *
	 * @param DocumentLoad load The load's result.
	 */
	fun applyDocumentLoad(load: DocumentLoad) {
		when (load) {
			is DocumentLoad.Loaded -> services.afterRunningExport { swapIn(load) }
			is DocumentLoad.Failed -> services.commandRegistry.invoke("document.openFailed", load.failure)
		}
	}

	/**
	 * Swaps a loaded document in: records it as a recent file, logs its artwork sources, and says once, up front,
	 * when it opened read-only.
	 *
	 * @param DocumentLoad.Loaded load The successful load.
	 */
	private fun swapIn(load: DocumentLoad.Loaded) {
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

	/**
	 * File > New: an empty document, which replaces the open one like any other document swap - so a dirty
	 * document asks first, and the swap waits for a model export started while it asked.
	 */
	fun newDocument() {
		if (refusedWhileOpening()) {
			return
		}

		save.confirmIfDirty {
			if (!refusedWhileOpening()) {
				services.afterRunningExport { services.onOpen(newBlankDocument()) }
			}
		}
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
		openFile { platformFileFromSavedPath(path) }
	}

	/**
	 * Asks for one file of [kind] and loads it over the open document, behind the unsaved-changes gate.
	 * FileKit's native dialog supplies its own (OS-localized) title, so none is passed.
	 *
	 * @param FileKind kind The one format the picker offers.
	 */
	private fun pickAndLoad(kind: FileKind) {
		openFile { services.filePicker.openFile(listOf(kind.extension)) }
	}

	/**
	 * Opens the file [choose] names over the open document: behind the unsaved-changes gate, as the one
	 * open running, read and built off the UI thread with its task on the status bar, and landed back on
	 * the app's scope.  The file is chosen on the app's scope too - a native dialog wants the UI thread.
	 *
	 * @param Function choose Names the file, or null when there is none to open (a cancelled picker).
	 */
	private fun openFile(choose: suspend () -> PlatformFile?) {
		if (refusedWhileOpening()) {
			return
		}

		save.confirmIfDirty {
			if (refusedWhileOpening()) {
				return@confirmIfDirty
			}

			// What the rigger agreed to replace: an edit made while the file loads is not covered by that answer.
			val confirmedSession = services.current().session
			val confirmedModel = confirmedSession?.model?.value
			val options = services.configuredArtworkImportOptions()
			// Started lazily so the gate holds the job before any of it can run, and so before it can complete.
			val open =
				services.scope.launch(start = CoroutineStart.LAZY) {
					val file = choose() ?: return@launch
					val loaded =
						services.progress.track(ProgressLabels.DOCUMENT_OPENING, listOf(file.name)) { progress ->
							withContext(Dispatchers.Default) { load(file, options, progress) }
						}
					landLoad(loaded, confirmedSession, confirmedModel)
				}
			opening = open
			open.invokeOnCompletion {
				if (opening === open) {
					opening = null
				}
			}
			open.start()
		}
	}

	/**
	 * Lands a finished load, first asking again when the document it replaces was edited after the
	 * unsaved-changes gate passed - the answer given then did not cover those edits.
	 *
	 * @param DocumentLoad   load             The load's result.
	 * @param EditorSession? confirmedSession The session the gate passed over, or null when none was open.
	 * @param PuppetModel?   confirmedModel   Its model as the gate passed.
	 */
	private fun landLoad(load: DocumentLoad, confirmedSession: EditorSession?, confirmedModel: PuppetModel?) {
		val session = services.current().session
		val editedSince = session != null && session === confirmedSession && session.model.value !== confirmedModel && session.dirty.value

		if (load is DocumentLoad.Loaded && editedSince) {
			save.confirmIfDirty { applyDocumentLoad(load) }

			return
		}

		applyDocumentLoad(load)
	}

	/**
	 * Whether an open is running, telling the rigger so when it is: a second replace would race the first.
	 *
	 * @return Boolean True when the request must be refused.
	 */
	private fun refusedWhileOpening(): Boolean {
		if (opening == null) {
			return false
		}

		UmamoLog.info("open: another document is still opening; this request was refused")
		services.current().session?.emitNotice("notice.document.openBusy", NoticePlacement.StatusBar)

		return true
	}
}