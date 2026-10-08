package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.render.FrameBackdrop
import org.umamo.render.FrameOverlays
import org.umamo.storage.FilePicker
import org.umamo.ui.action.Command
import org.umamo.ui.document.ImageExportSessionOptions
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.viewport.AreaOverlays
import org.umamo.ui.viewport.GridConfig
import org.umamo.ui.viewport.ImageBackground
import org.umamo.ui.viewport.ImageExportOptions
import org.umamo.ui.viewport.ImageRegion
import org.umamo.ui.viewport.StubPuppetViewportService
import org.umamo.ui.workspace.export.ExportOptionsRequest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull

/**
 * Pins what an Export Image capture draws around the puppet: a Viewport Grid capture takes the grid and the
 * axes as the exporting viewport shows them, and an export with no viewport behind it takes the editor's
 * defaults.  The render service is a stub, so the capture itself never happens; what matters is what it is
 * asked for.
 */
class ImageExportControllerTest {
	private val gridCapture = ImageExportOptions(ImageRegion.Canvas, 100f, ImageBackground.Grid, "#FFFFFFFF")

	/** A picker that answers every save with [destination]; the stub renders nothing, so it is never written. */
	private class SavingPicker(private val destination: PlatformFile) : FilePicker {
		override suspend fun openFile(extensions: List<String>): PlatformFile? = null

		override suspend fun saveFile(suggestedName: String, extension: String): PlatformFile? = destination
	}

	/**
	 * Exports the canvas over a grid backdrop through the controller, confirming the options dialog the way
	 * the shell would, and waits for the capture request to land on the service.
	 *
	 * @param AppControllerFixture      fixture        The fixture the controller is built over.
	 * @param StubPuppetViewportService service        The render service the capture is asked of.
	 * @param String?                   viewportAreaId The 2D viewport the export was invoked from, or null for none.
	 */
	private suspend fun exportCanvasOverGrid(fixture: AppControllerFixture, service: StubPuppetViewportService, viewportAreaId: String?) {
		val document = newBlankDocument()
		val puppet = OpenPuppet(document, EditorSession(document.puppet, document.liveParams.values), atlasPages = null)
		val slot = DocumentViewportSlot().apply { this.service = service }
		var request: ExportOptionsRequest.Image? = null
		fixture.registry.register(Command("document.exportOptions", title = null) { argument -> request = assertIs<ExportOptionsRequest.Image>(argument) })
		val controller = ImageExportController(fixture.services, puppet, file = null, viewport = slot, sessionOptions = ImageExportSessionOptions())

		controller.exportImage(viewportAreaId)
		assertNotNull(request, "the options dialog opened").onConfirm(gridCapture, gridCapture)
		fixture.settle()
	}

	@Test
	fun aGridCaptureTakesTheExportingViewportsOverlays() =
		runTest {
			val fixture = AppControllerFixture(this, filePicker = SavingPicker(PlatformFile(File("unwritten.png"))))
			val service = StubPuppetViewportService()
			val shown = AreaOverlays(GridConfig(50f, 4), FrameOverlays(gridLines = true, axes = false, meshOverlay = true))
			service.setAreaOverlays("viewport", shown)

			exportCanvasOverGrid(fixture, service, "viewport")

			assertEquals(listOf<Pair<FrameBackdrop, AreaOverlays>>(FrameBackdrop.Grid to shown), service.renderImageRequests)
		}

	@Test
	fun aCaptureWithNoViewportBehindItTakesTheDefaults() =
		runTest {
			val fixture = AppControllerFixture(this, filePicker = SavingPicker(PlatformFile(File("unwritten.png"))))
			val service = StubPuppetViewportService()

			exportCanvasOverGrid(fixture, service, null)

			assertEquals(listOf<Pair<FrameBackdrop, AreaOverlays>>(FrameBackdrop.Grid to AreaOverlays.Default), service.renderImageRequests)
		}
}