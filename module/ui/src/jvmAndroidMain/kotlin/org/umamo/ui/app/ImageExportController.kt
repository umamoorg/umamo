package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.absolutePath
import io.github.vinceglb.filekit.name
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okio.IOException
import org.jetbrains.compose.resources.getString
import org.umamo.edit.NoticePlacement
import org.umamo.format.FileKind
import org.umamo.format.png.PngCodec
import org.umamo.render.puppet.canvasBoundsOf
import org.umamo.storage.UmamoLog
import org.umamo.storage.writeReplacing
import org.umamo.ui.document.ImageExportSessionOptions
import org.umamo.ui.document.exportSuggestedName
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_export_image_failed
import org.umamo.ui.resources.export_image_failed_renderer
import org.umamo.ui.resources.export_options_image_no_canvas
import org.umamo.ui.resources.export_options_image_no_viewport
import org.umamo.ui.resources.export_options_image_nothing_visible
import org.umamo.ui.resources.export_options_image_too_large
import org.umamo.ui.viewport.ImageExportOptions
import org.umamo.ui.viewport.ImageFrameResult
import org.umamo.ui.viewport.MAX_IMAGE_EDGE
import org.umamo.ui.viewport.frameBackdrop
import org.umamo.ui.viewport.resolveImageFrame
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.ExportOptionsRequest

/**
 * Export Image for ONE open document: the options dialog, the destination, a capture straight from the viewport
 * renderer, and the PNG on disk.
 *
 * Made per document like the format exports, holding its [OpenPuppet] and the slot its render service arrives
 * in.  An export is not a save: nothing here touches the dirty state.
 *
 * @property EditorAppServices         services       The app's shared collaborators.
 * @property OpenPuppet                puppet         The open puppet document with its session.
 * @property DocumentViewportSlot      viewport       Where the document's render service is while it lives.
 * @property ImageExportSessionOptions sessionOptions The dialog's session memory, which outlives this controller.
 */
internal class ImageExportController(
	private val services: EditorAppServices,
	private val puppet: OpenPuppet,
	private val viewport: DocumentViewportSlot,
	private val sessionOptions: ImageExportSessionOptions,
) {
	/**
	 * Opens the Export Image dialog, framed around what there is to capture right now: the 2D viewport area the
	 * command resolved (none when the pointer last touched another space), the canvas, and the shown content at
	 * the current pose.  Confirming records the options and continues to the destination and the render.
	 *
	 * @param String? viewportAreaId The 2D viewport area the View region frames, or null for none.
	 */
	fun exportImage(viewportAreaId: String?) {
		val service = viewport.service ?: return
		services.commandRegistry.invoke(
			"document.exportOptionsImage",
			ExportOptionsRequest.Image(
				initial = sessionOptions.dialogOptions(),
				viewFrame = viewportAreaId?.let { areaId -> service.areaView(areaId) },
				canvasBounds = canvasBoundsOf(puppet.session.model.value),
				contentBounds = service.visibleContentBounds(),
				onConfirm = { options ->
					sessionOptions.recordConfirmed(options)
					services.scope.launch { export(options, viewportAreaId) }
				},
			),
		)
	}

	/**
	 * Asks where, renders, encodes, and writes.  The frame is resolved again after the picker closes rather
	 * than taken from the dialog, so the image is of what the viewport shows when it is drawn.
	 *
	 * @param ImageExportOptions options        The confirmed options.
	 * @param String?            viewportAreaId The 2D viewport area the View region frames, or null for none.
	 */
	private suspend fun export(options: ImageExportOptions, viewportAreaId: String?) {
		val destination = services.filePicker.saveFile(exportSuggestedName(puppet.document.displayName), FileKind.Png.extension) ?: return
		val service = viewport.service
		if (service == null) {
			alert(destination, getString(Res.string.export_image_failed_renderer))
			return
		}
		val framing =
			resolveImageFrame(
				options.region,
				options.scalePercent / 100f,
				viewportAreaId?.let { areaId -> service.areaView(areaId) },
				canvasBoundsOf(puppet.session.model.value),
				service.visibleContentBounds(),
			)
		val frame =
			when (framing) {
				is ImageFrameResult.Framed -> framing.frame
				ImageFrameResult.NoViewport -> return alert(destination, getString(Res.string.export_options_image_no_viewport))
				ImageFrameResult.NoCanvas -> return alert(destination, getString(Res.string.export_options_image_no_canvas))
				ImageFrameResult.NothingVisible -> return alert(destination, getString(Res.string.export_options_image_nothing_visible))
				is ImageFrameResult.TooLarge ->
					return alert(destination, getString(Res.string.export_options_image_too_large, framing.width, framing.height, MAX_IMAGE_EDGE))
			}
		puppet.session.emitNotice("notice.document.exportingImage", NoticePlacement.StatusBar)
		val image = service.renderImage(frame, options.frameBackdrop())
		if (image == null) {
			alert(destination, getString(Res.string.export_image_failed_renderer))
			return
		}
		val bytes = withContext(Dispatchers.Default) { PngCodec.write(image) }
		try {
			// Once the bytes exist they land, as with a save: a torn-down composition must not leave half a file.
			withContext(NonCancellable) { destination.writeReplacing(bytes) }
		} catch (failure: IOException) {
			UmamoLog.error("export image: could not write ${destination.name}", failure)
			alert(destination, failure.message ?: destination.name)
			return
		}
		UmamoLog.info("exported image ${destination.absolutePath()} (${frame.width}x${frame.height})")
		puppet.session.emitNotice("notice.document.exportedImage", NoticePlacement.StatusBar, listOf(destination.name))
	}

	/**
	 * Tells the rigger the image was not exported, and why.
	 *
	 * @param PlatformFile destination The file that was not written.
	 * @param String       reason      The already-localized reason.
	 */
	private fun alert(destination: PlatformFile, reason: String) {
		UmamoLog.warn("export image: ${destination.name} not written: $reason")
		services.commandRegistry.invoke("document.alert", AlertRequest(Res.string.alert_export_image_failed, listOf(destination.name, reason)))
	}
}