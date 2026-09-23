package org.umamo.ui.app

import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import io.github.vinceglb.filekit.write
import kotlinx.coroutines.launch
import org.umamo.format.FileKind
import org.umamo.format.cmo3.Cmo3
import org.umamo.interop.ExportReport
import org.umamo.interop.describeExportNotice
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.storage.UmamoLog
import org.umamo.ui.document.DocumentFile
import org.umamo.ui.document.Moc3Document
import org.umamo.ui.document.Moc3ExportSessionOptions
import org.umamo.ui.document.existingBundleFiles
import org.umamo.ui.document.exportedModelFor
import org.umamo.ui.document.prepareCmo3Export
import org.umamo.ui.document.prepareMoc3Export
import org.umamo.ui.document.writeMoc3Bundle
import org.umamo.ui.model.DrawableThumbnailer
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.confirm_export_overwrite
import org.umamo.ui.resources.dialog_overwrite
import org.umamo.ui.workspace.ConfirmRequest
import org.umamo.ui.workspace.ExportOptionsRequest
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
		val puppetDocument = exported.document
		services.scope.launch {
			val suggestedName = file?.exportBaseName ?: services.untitledName()
			services.filePicker.saveFile(suggestedName, FileKind.Cmo3.extension)?.let { destination ->
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
				// The model's own icons come from the outliner's rest-pose composite, over the same
				// pages the export writes - pure CPU, so the Android shell writes them too.
				val modelThumbnail = DrawableThumbnailer(edited, effectiveTextures).modelRasterFor()
				val prepared =
					prepareCmo3Export(
						document = puppetDocument,
						edited = edited,
						effectiveTextures = effectiveTextures,
						modelName = suggestedName,
						nowMillis = Clock.System.now().toEpochMilliseconds(),
						obfuscateKey = Random.nextInt(),
						modelThumbnail = modelThumbnail,
					)
				destination.write(Cmo3.write(prepared.model))
				reportExport(prepared.report)
				UmamoLog.info("exported ${destination.absolutePath()}")
			}
		}
	}

	/**
	 * Exports the open document's moc family.  Options first, destination second: the choices do not depend
	 * on where the family lands, and recording them on confirm - before the picker - keeps them sticky
	 * through a cancelled picker.
	 */
	fun exportMoc3() {
		val exported = puppet ?: return
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
					services.scope.launch {
						services.filePicker.saveFile(file?.exportBaseName ?: services.untitledName(), FileKind.Moc3.extension)?.let { destination ->
							val bundle =
								prepareMoc3Export(
									document = puppetDocument,
									// Re-resolved at write time: the dialog is modeless enough that the
									// session could undo between confirm and the picker closing.
									edited = exportedModelFor(puppetDocument, exported.session),
									effectiveTextures = exported.pageBinding().textures,
									// FileKit appends the extension, so the picked handle's own name is authoritative.
									destinationName = destination.name,
									options = options,
								)

							fun writeAndReport() {
								services.scope.launch {
									val written = writeMoc3Bundle(destination, bundle)
									reportExport(bundle.report)
									UmamoLog.info("exported $written file(s) as ${destination.absolutePath()}")
								}
							}
							// The native save dialog confirmed the picked file only; the rest of the family
							// (manifest, cdi3, textures, sidecars) lands beside it unannounced, so anything
							// already there gets one warning naming what an OK would replace.
							val existing = existingBundleFiles(destination, bundle)
							if (existing.isEmpty()) {
								writeAndReport()
							} else {
								services.commandRegistry.invoke(
									"document.confirm",
									ConfirmRequest(
										message = Res.string.confirm_export_overwrite,
										// File names are document data, listed in full - the dialog wraps, and a
										// name the warning omitted is a file the rigger did not agree to lose.
										arguments = listOf(existing.size, existing.joinToString()),
										confirmLabel = Res.string.dialog_overwrite,
										onConfirm = ::writeAndReport,
									),
								)
							}
						}
					}
				},
			),
		)
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