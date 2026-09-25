package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import okio.IOException
import org.jetbrains.compose.resources.getString
import org.umamo.edit.NoticePlacement
import org.umamo.format.FileKind
import org.umamo.format.cmo3.Cmo3
import org.umamo.interop.ExportReport
import org.umamo.interop.describeExportNotice
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.storage.UmamoLog
import org.umamo.storage.writeReplacing
import org.umamo.ui.document.Cmo3Document
import org.umamo.ui.document.DocumentFile
import org.umamo.ui.document.Moc3Document
import org.umamo.ui.document.Moc3ExportSessionOptions
import org.umamo.ui.document.RenderedCmo3Export
import org.umamo.ui.document.existingBundleFiles
import org.umamo.ui.document.exportedModelFor
import org.umamo.ui.document.prepareCmo3Export
import org.umamo.ui.document.prepareMoc3Export
import org.umamo.ui.document.renderCmo3Export
import org.umamo.ui.document.writeMoc3Bundle
import org.umamo.ui.model.DrawableThumbnailer
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_export_failed
import org.umamo.ui.resources.confirm_export_overwrite
import org.umamo.ui.resources.dialog_overwrite
import org.umamo.ui.resources.export_failed_unexpected
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ExportOptionsRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.random.Random
import kotlin.time.Clock

/**
 * The CMO3 and MOC3 exports of ONE open document.  Both puppet document kinds export to either format:
 * Export CMO3 reconciles onto a CMO3-origin document's retained graph and synthesizes a fresh one
 * otherwise, while Export MOC3 bakes fresh from the model whatever the origin.
 *
 * Made per document and holding its [OpenPuppet], so the document, the session, and the page set an export
 * reconciles from are the same three for the controller's whole life - a mismatched set would write one
 * model's rig onto another's atlas pages.  The export commands re-register whenever it is remade.
 *
 * @property EditorAppServices        services          The app's shared collaborators.
 * @property OpenPuppet?              puppet            The open puppet document with its session and pages, or null.
 * @property DocumentFile?            file              Where the document saves, which names the exports; null with
 *   no document.
 * @property Moc3ExportSessionOptions moc3ExportOptions The MOC3 export dialog's session memory, sticky for the
 *   application's life and never persisted, so it outlives this controller.
 */
internal class DocumentExportController(
	private val services: EditorAppServices,
	private val puppet: OpenPuppet?,
	private val file: DocumentFile?,
	private val moc3ExportOptions: Moc3ExportSessionOptions,
) {
	/** Whether there is a puppet document to export. */
	val canExport: Boolean = puppet != null

	/**
	 * Exports the open document to a `.cmo3` the rigger picks.  An export is not a save: the dirty baseline
	 * stays put, since only a save marks the session saved, and only for the `.uma` it wrote.
	 */
	fun exportCmo3() {
		val exported = puppet ?: return
		val started =
			services.modelExports.tryStart(services.scope) {
				val suggestedName = file?.exportBaseName ?: services.untitledName()
				services.filePicker.saveFile(suggestedName, FileKind.Cmo3.extension)?.let { destination ->
					services.alertingExportFailures(destination.name) {
						writeCmo3(exported, destination, suggestedName)
					}
				}
			}
		if (!started) {
			exported.session.emitNotice("notice.document.exportBusy", NoticePlacement.StatusBar)
		}
	}

	/**
	 * Lowers the open document into a CMO3 and writes it to [destination].  The bytes land through a
	 * replace-write, as a save's do, so a failed write leaves whatever was there rather than half a file.
	 *
	 * The model and the page set are read here, on the UI thread, and the seconds of work that turn them into
	 * bytes run off it, so the editor stays usable while a large model exports.
	 *
	 * @param OpenPuppet   exported      The document being exported, with its session and pages.
	 * @param PlatformFile destination   The picked file.
	 * @param String       suggestedName The display name a synthesized skeleton records.
	 */
	private suspend fun writeCmo3(exported: OpenPuppet, destination: PlatformFile, suggestedName: String) {
		val puppetDocument = exported.document
		val edited = exportedModelFor(puppetDocument, exported.session)
		// The session's resolved page set: the document's own instance until a repack
		// composed a new one, which is exactly the gate the archive patch keys on.
		val effectiveTextures = exported.pageBinding().textures
		// Named in the log because both outcomes are otherwise silent: an unedited model exports
		// the graph as-is with an empty report, and the document's own pages mean no page patch.
		UmamoLog.info(
			"export: model ${if (edited === puppetDocument.puppet) "is the unedited import" else "carries session edits"}" +
				" (atlas ${if (edited.atlas === puppetDocument.puppet.atlas) "unchanged" else "repacked"});" +
				" pages ${if (effectiveTextures === puppetDocument.textures) "are the document's own" else "are the session's (${effectiveTextures.atlases.size})"}",
		)
		exported.session.emitNotice("notice.document.exportingModel", NoticePlacement.StatusBar)
		val nowMillis = Clock.System.now().toEpochMilliseconds()
		val obfuscateKey = Random.nextInt()
		val rendered =
			if (puppetDocument is Cmo3Document) {
				// The reconcile edits the retained graph that the document's own rasters read on this thread, so it
				// stays here; the thumbnail and the serialization only read, and leave.
				val modelThumbnail = withContext(Dispatchers.Default) { DrawableThumbnailer(edited, effectiveTextures).modelRasterFor() }
				val prepared = prepareCmo3Export(puppetDocument, edited, effectiveTextures, suggestedName, nowMillis, obfuscateKey, modelThumbnail)
				RenderedCmo3Export(withContext(Dispatchers.Default) { Cmo3.write(prepared.model) }, prepared.report)
			} else {
				withContext(Dispatchers.Default) { renderCmo3Export(puppetDocument, edited, effectiveTextures, suggestedName, nowMillis, obfuscateKey) }
			}
		// Once the bytes exist they land: a torn-down composition must not leave half a file.
		withContext(NonCancellable) { destination.writeReplacing(rendered.bytes) }
		reportExport(rendered.report)
		UmamoLog.info("exported ${destination.absolutePath()}")
		exported.session.emitNotice("notice.document.exportedModel", NoticePlacement.StatusBar, listOf(destination.name))
	}

	/**
	 * Exports the open document's moc family.  Options first, destination second: the choices do not depend
	 * on where the family lands, and recording them on confirm - before the picker - keeps them sticky
	 * through a cancelled picker.
	 */
	fun exportMoc3() {
		val exported = puppet ?: return
		if (services.modelExports.isBusy) {
			exported.session.emitNotice("notice.document.exportBusy", NoticePlacement.StatusBar)
			return
		}
		val puppetDocument = exported.document
		val moc3Document = puppetDocument as? Moc3Document
		val seedModel = exportedModelFor(puppetDocument, exported.session)
		services.commandRegistry.invoke(
			"document.exportOptions",
			ExportOptionsRequest.Moc3(
				initial = moc3ExportOptions.dialogOptionsFor(puppetDocument.path, seedModel),
				physicsAvailable = moc3Document?.sidecars?.any { sidecar -> sidecar.kind == Moc3Sidecars.SidecarKind.Physics } == true,
				userDataAvailable = moc3Document?.sidecars?.any { sidecar -> sidecar.kind == Moc3Sidecars.SidecarKind.UserData } == true,
				canvasWidth = seedModel.canvasWidth,
				canvasHeight = seedModel.canvasHeight,
				onConfirm = { options ->
					moc3ExportOptions.recordConfirmed(puppetDocument.path, options)
					// Checked again: another export can have started while the options dialog was up.
					if (!services.modelExports.tryStart(services.scope) { prepareMoc3(exported, options) }) {
						exported.session.emitNotice("notice.document.exportBusy", NoticePlacement.StatusBar)
					}
				},
			),
		)
	}

	/**
	 * Asks where the moc family goes, builds it off the UI thread, and writes it - at once when nothing of the
	 * family is already there, else after the overwrite warning.
	 *
	 * @param OpenPuppet        exported The document being exported, with its session and pages.
	 * @param Moc3ExportOptions options  What the rigger chose to include.
	 */
	private suspend fun prepareMoc3(exported: OpenPuppet, options: Moc3ExportOptions) {
		val puppetDocument = exported.document
		val destination = services.filePicker.saveFile(file?.exportBaseName ?: services.untitledName(), FileKind.Moc3.extension) ?: return
		// Re-resolved at write time: the dialog is modeless enough that the session could undo between confirm and
		// the picker closing.  Read here, on the UI thread; only the build leaves it.
		val edited = exportedModelFor(puppetDocument, exported.session)
		val effectiveTextures = exported.pageBinding().textures
		exported.session.emitNotice("notice.document.exportingModel", NoticePlacement.StatusBar)
		val bundle =
			services.alertingExportFailures(destination.name) {
				withContext(Dispatchers.Default) {
					prepareMoc3Export(
						document = puppetDocument,
						edited = edited,
						effectiveTextures = effectiveTextures,
						// FileKit appends the extension, so the picked handle's own name is authoritative.
						destinationName = destination.name,
						options = options,
					)
				}
			} ?: return
		// The native save dialog confirmed the picked file only; the rest of the family (manifest, cdi3,
		// textures, sidecars) lands beside it unannounced, so anything already there gets one warning naming
		// what an OK would replace.
		val existing = existingBundleFiles(destination, bundle)
		if (existing.isEmpty()) {
			writeMoc3(exported, destination, bundle)
			return
		}
		services.commandRegistry.invoke(
			"document.confirm",
			ConfirmRequest(
				message = Res.string.confirm_export_overwrite,
				// File names are document data, listed in full - the dialog wraps, and a name the warning omitted
				// is a file the rigger did not agree to lose.
				arguments = listOf(existing.size, existing.joinToString()),
				confirmLabel = Res.string.dialog_overwrite,
				// The warning holds no claim on the gate - a dismissed confirm has no callback that could release
				// one - so the write takes its own.
				onConfirm = {
					if (!services.modelExports.tryStart(services.scope) { writeMoc3(exported, destination, bundle) }) {
						exported.session.emitNotice("notice.document.exportBusy", NoticePlacement.StatusBar)
					}
				},
			),
		)
	}

	/**
	 * Writes a built moc family beside [destination] and reports it.
	 *
	 * @param OpenPuppet          exported    The document being exported, for its status notices.
	 * @param PlatformFile        destination The picked `.moc3` file.
	 * @param Moc3Sidecars.Bundle bundle      The family to write.
	 */
	private suspend fun writeMoc3(exported: OpenPuppet, destination: PlatformFile, bundle: Moc3Sidecars.Bundle) {
		services.alertingExportFailures(destination.name) {
			val written = withContext(Dispatchers.IO) { writeMoc3Bundle(destination, bundle) }
			reportExport(bundle.report)
			UmamoLog.info("exported $written file(s) as ${destination.absolutePath()}")
			exported.session.emitNotice("notice.document.exportedModel", NoticePlacement.StatusBar, listOf(destination.name))
		}
	}

	/**
	 * Surfaces an export's report: every notice to the log, and the report itself to the alert the shell
	 * shows.  Both exports end this way - anything unrepresentable is stated, never dropped.
	 *
	 * @param ExportReport report The export's report.
	 */
	private fun reportExport(report: ExportReport) {
		for (notice in report.notices) {
			UmamoLog.warn("export: ${describeExportNotice(notice)}")
		}
		if (!report.isEmpty) {
			services.commandRegistry.invoke("document.exportReport", report)
		}
	}
}

/**
 * Runs one export's work and turns its failure into an alert, so a failed export costs the export rather than
 * the session: left alone, a failure escapes the app's scope to Compose's window exception handler, which shows a
 * bare Java error and asks the window to close.  Running out of memory is named as such, with a jar launch's way out;
 * an I/O failure carries its own message; anything else is an export bug, logged with its stack for the report.
 * Cancellation passes through untouched.
 *
 * @param String   destinationName The file the export writes, named in the alert and the log.
 * @param Function work            The export's work.
 * @return T? The work's result, or null when it failed and was reported.
 */
internal suspend fun <T> EditorAppServices.alertingExportFailures(destinationName: String, work: suspend () -> T): T? =
	try {
		work()
	} catch (cancelled: CancellationException) {
		throw cancelled
	} catch (failure: OutOfMemoryError) {
		// The export's own allocations are unreachable once this unwinds, so the editor carries on.
		val limit = hostHeap?.let { launchHeap -> " (the heap may grow to ${describeHeapLimit(launchHeap.maxBytes)})" }.orEmpty()
		UmamoLog.error("export: ran out of memory writing $destinationName$limit", failure)
		commandRegistry.invoke("document.alert", exportOutOfMemoryAlert(destinationName, hostHeap))
		null
	} catch (failure: IOException) {
		UmamoLog.error("export: could not write $destinationName", failure)
		commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_export_failed, listOf(destinationName, failure.message ?: destinationName)))
		null
	} catch (failure: Exception) {
		UmamoLog.error("export: $destinationName failed", failure)
		commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_export_failed, listOf(destinationName, getString(Res.string.export_failed_unexpected))))
		null
	}