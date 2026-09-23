package org.umamo.ui.app

import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.async
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.buildJsonObject
import org.umamo.edit.NoticePlacement
import org.umamo.format.FileKind
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.UmaModel
import org.umamo.render.FrameBackdrop
import org.umamo.storage.UmamoLog
import org.umamo.storage.platformFileFromSavedPath
import org.umamo.ui.document.Cmo3Document
import org.umamo.ui.document.Moc3Document
import org.umamo.ui.document.PuppetDocument
import org.umamo.ui.document.UMA_THUMBNAIL_SIZE
import org.umamo.ui.document.UmaWriteOutcome
import org.umamo.ui.document.addRecentFile
import org.umamo.ui.document.fileDisplayName
import org.umamo.ui.document.umamoWriterInfo
import org.umamo.ui.document.writeUmaDocument
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_save_failed
import org.umamo.ui.viewport.fitSquare
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.EDITOR_STATE_AREAS
import org.umamo.ui.workspace.EDITOR_STATE_SESSION
import org.umamo.ui.workspace.commands.DirtyDocumentPrompt
import org.umamo.ui.workspace.sessionStateJson

/**
 * How long a save waits for the renderer to draw its thumbnail before compositing one itself.  A capture
 * this small takes a frame or two; the wait only matters when the render thread is stuck.
 */
private const val THUMBNAIL_RENDER_TIMEOUT_MILLIS = 5_000L

/**
 * Save and Save As, and the two gates that stand in front of anything that would discard the session:
 * replacing the document and quitting.
 *
 * Lives for the application's life and reads the open document through [EditorAppServices.current] at
 * each call.  The file commands register once and the exit guard installs once, so a controller that held
 * a document would keep asking about whichever one was open when it was made.
 *
 * @property EditorAppServices services The app's shared collaborators.
 */
internal class DocumentSaveController(
	private val services: EditorAppServices,
) {
	/**
	 * Whether Save can write now: a puppet document that did not open read-only.
	 *
	 * @return Boolean True when a save is possible.
	 */
	fun canSaveNow(): Boolean {
		val context = services.current()
		return context.document is PuppetDocument && context.file?.canSave == true
	}

	/**
	 * Saves the open document, or asks where first.  The first save of a document that did not come from a
	 * `.uma` is a Save As suggesting the origin's name; after that a plain Save writes the same file
	 * without asking.
	 *
	 * The document's whole context is taken ONCE, here, and everything the save gathers comes from it - so a
	 * document swapped in while the save dialog is up cannot lend its pages or its area state to this
	 * document's file.  The model and the atlas pages are snapshotted on the calling (UI) thread and the
	 * writer runs off it, so the editor stays usable while a large document encodes; the session is marked
	 * saved with that same snapshot, so an edit made meanwhile keeps the document dirty.
	 *
	 * @param Boolean  saveAs  True to ask where to save even when the document already has a file.
	 * @param Function onSaved Called once the file has landed; not called for a cancelled or failed save.
	 */
	fun save(saveAs: Boolean, onSaved: () -> Unit = {}) {
		val context = services.current()
		val puppet = context.puppet ?: return
		val file = context.file ?: return
		val activeSession = puppet.session
		if (!file.canSave) {
			return
		}
		if (file.saving) {
			activeSession.emitNotice("notice.document.saveBusy", NoticePlacement.StatusBar)
			return
		}
		// Kept on the holder as the save in flight, so a quit or a document replace asked for meanwhile waits
		// for it instead of racing it (afterPendingSave below); it completes true only when the file landed.
		file.saveJob =
			services.scope.async {
				val knownPath = file.umaPath
				val destination =
					if (!saveAs && knownPath != null) {
						platformFileFromSavedPath(knownPath)
					} else {
						// The native save dialog owns the overwrite prompt: a .uma is one file, unlike the MOC3 family.
						services.filePicker.saveFile(file.suggestedBaseName ?: services.untitledName(), FileKind.Uma.extension) ?: return@async false
					}
				val snapshot = activeSession.model.value
				val binding = puppet.pageBinding()
				// Editor state is gathered with the model, on this thread, and rides the save without ever counting as a
				// change to the document (UMA §7).
				val editorState =
					buildJsonObject {
						put(EDITOR_STATE_AREAS, context.areaViewStates.gather())
						put(EDITOR_STATE_SESSION, sessionStateJson(activeSession.viewState(), activeSession.pose.value, snapshot))
					}
				val base = file.base ?: UmaModel.create(umamoWriterInfo())
				file.saving = true
				activeSession.emitNotice("notice.document.saving", NoticePlacement.StatusBar)
				val outcome =
					try {
						writeUmaDocument(puppet.document, base, snapshot, binding, editorState, destination, renderedThumbnail(context.viewport))
					} finally {
						file.saving = false
					}
				when (outcome) {
					is UmaWriteOutcome.Written -> {
						activeSession.markSaved(snapshot)
						val path = destination.absolutePath()
						file.umaPath = path
						file.base = outcome.uma
						services.settings.addRecentFile(path)
						UmamoLog.info("saved $path")
						activeSession.emitNotice("notice.document.saved", NoticePlacement.StatusBar, listOf(fileDisplayName(path)))
						// Once per document, what a .uma of this origin does not carry: shown after
						// the saved notice, so it is the one left on screen.
						if (!file.lossNoticeShown) {
							file.lossNoticeShown = true
							// Logged as well as shown: a status notice is gone in seconds, and this is the one place the
							// rigger is told what the new file leaves behind.
							when (puppet.document) {
								is Cmo3Document -> {
									UmamoLog.info("saved $path from a CMO3: the CMO3 structure Umamo does not model is not in the .uma; exports from this session still reconcile onto the original")
									activeSession.emitNotice("notice.document.cmo3Loss", NoticePlacement.StatusBar)
								}
								is Moc3Document -> {
									UmamoLog.info("saved $path from a MOC3: physics, motions, expressions, user data, and pose are not in the .uma")
									activeSession.emitNotice("notice.document.moc3Loss", NoticePlacement.StatusBar)
								}
								else -> Unit
							}
						}
						onSaved()
						true
					}
					is UmaWriteOutcome.Failed -> {
						services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_save_failed, listOf(destination.name, outcome.reason)))
						false
					}
				}
			}
	}

	/**
	 * The saved thumbnail as the viewport renderer draws it (UMA §5.6): the pose on screen, the drawables the
	 * editor shows, blended, masked, and colored as the viewport has them, fitted into the square over
	 * transparency.
	 *
	 * The render thread draws its own latest model, which can trail the snapshot this save writes by a frame; a
	 * thumbnail is a picture of the document, and a frame's lag does not change what it shows.
	 *
	 * @param DocumentViewportSlot viewport Where the document's render service is.
	 * @return RasterImage? The thumbnail, or null when there is no renderer, nothing is shown, or the render did
	 *   not come back in time - the writer then composites one itself.
	 */
	private suspend fun renderedThumbnail(viewport: DocumentViewportSlot): RasterImage? {
		val service = viewport.service ?: return null
		val bounds = service.visibleContentBounds() ?: return null
		val thumbnail =
			withTimeoutOrNull(THUMBNAIL_RENDER_TIMEOUT_MILLIS) {
				service.renderImage(fitSquare(bounds, UMA_THUMBNAIL_SIZE), FrameBackdrop.Transparent)
			}
		if (thumbnail == null) {
			UmamoLog.warn("save: the renderer did not draw the thumbnail; compositing it instead")
		}
		return thumbnail
	}

	/**
	 * Runs [action] once no save is being written.  A save still in flight settles before anything that
	 * would end the process or replace the document: the write runs on a thread the process does not wait
	 * for, so a quit that went ahead mid-save would kill it - and a clean document (an import never edited)
	 * has nothing unsaved to stop the quit with.  Waiting also keeps the unsaved-changes prompt from
	 * appearing over a running save, where its Save button could only answer that one is in progress.
	 *
	 * @param Function action What to do once nothing is being written.
	 */
	private fun afterPendingSave(action: () -> Unit) {
		val context = services.current()
		val file = context.file
		if (file == null) {
			action()
			return
		}
		file.afterPendingSave(
			services.scope,
			onWaiting = { context.session?.emitNotice("notice.document.waitingForSave", NoticePlacement.StatusBar) },
			action = action,
		)
	}

	/**
	 * The choices a dirty document's prompt offers: go on without saving, or save first and go on once the
	 * save has landed - the latter only when the document can be saved at all.
	 *
	 * @param Function proceed What the prompt stands in front of.
	 * @return DirtyDocumentPrompt The prompt's choices.
	 */
	private fun dirtyDocumentPrompt(proceed: () -> Unit): DirtyDocumentPrompt =
		DirtyDocumentPrompt(
			discard = proceed,
			save = if (canSaveNow()) ({ save(saveAs = false, onSaved = proceed) }) else null,
		)

	/**
	 * Runs [proceed] - something that replaces the document - asking first when the document is dirty.
	 * Replacing the document discards its session, the undo history and any unsaved edits with it.  The
	 * shell owns the confirm dialog (document.confirmReplace), keeping its Escape and Enter routing with
	 * every other overlay.
	 *
	 * @param Function proceed Replaces the document.
	 */
	fun confirmIfDirty(proceed: () -> Unit) {
		afterPendingSave {
			if (services.current().session?.dirty?.value == true) {
				services.commandRegistry.invoke("document.confirmReplace", dirtyDocumentPrompt(proceed))
			} else {
				proceed()
			}
		}
	}

	/**
	 * Runs [exit], asking first when the document is dirty (document.confirmExit): quitting discards the
	 * session the same way a replace does.  File > Exit calls this directly; the host's window close, OS
	 * quit, and back gesture reach it through the exit guard.
	 *
	 * @param Function exit Closes the application.
	 */
	fun confirmExit(exit: () -> Unit) {
		afterPendingSave {
			if (services.current().session?.dirty?.value == true) {
				services.commandRegistry.invoke("document.confirmExit", dirtyDocumentPrompt(exit))
			} else {
				exit()
			}
		}
	}
}