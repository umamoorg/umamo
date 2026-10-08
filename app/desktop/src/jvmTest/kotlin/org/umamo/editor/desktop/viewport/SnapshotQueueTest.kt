package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.runBlocking
import org.umamo.format.raster.RasterImage
import org.umamo.render.ContentBounds
import org.umamo.render.FrameBackdrop
import org.umamo.render.FrameOverlays
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.AreaOverlays
import org.umamo.ui.viewport.GridConfig
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Pins the capture queue's promises: every request is answered - with the capture's pixels in request
 * order, with null when the capture fails or is abandoned, and with null at once after the queue has
 * closed - and a stopped loop leaves the queue for the close to sweep.  Pure: no render thread.
 */
class SnapshotQueueTest {
	private val camera = ViewportCamera.fit(ContentBounds(0f, 0f, 10f, 10f), 10, 10)
	private val image = RasterImage(1, 1, ByteArray(4))

	private fun settled(result: kotlinx.coroutines.Deferred<RasterImage?>): RasterImage? {
		assertTrue(result.isCompleted, "the capture must have been answered")
		return runBlocking { result.await() }
	}

	@Test
	fun aServedCaptureCarriesItsRequestInOrder() {
		val queue = SnapshotQueue()
		val gridOverlays = AreaOverlays(GridConfig(50f, 4), FrameOverlays(gridLines = true, axes = false, meshOverlay = true))
		val first = queue.request(camera, 3, 4, FrameBackdrop.Grid, gridOverlays)
		val second = queue.request(camera, 5, 6, FrameBackdrop.Transparent, AreaOverlays.Default)
		val served = mutableListOf<String>()
		queue.serve({ true }) { requestCamera, width, height, backdrop, overlays ->
			assertSame(camera, requestCamera)
			served += "${width}x$height:$backdrop:${overlays.grid.scale}:${overlays.frame.axes}"
			if (width == 3) image else null
		}
		assertEquals(listOf("3x4:Grid:50.0:false", "5x6:${FrameBackdrop.Transparent}:100.0:true"), served, "each capture carries its own options")
		assertSame(image, settled(first))
		assertNull(settled(second), "an abandoned capture answers null")
	}

	@Test
	fun aRequestAfterCloseIsAnsweredNullAtOnce() {
		val queue = SnapshotQueue()
		queue.close()
		assertNull(settled(queue.request(camera, 1, 1, FrameBackdrop.Grid, AreaOverlays.Default)))
	}

	@Test
	fun closeAnswersEveryQueuedCapture() {
		val queue = SnapshotQueue()
		val first = queue.request(camera, 1, 1, FrameBackdrop.Grid, AreaOverlays.Default)
		val second = queue.request(camera, 2, 2, FrameBackdrop.Grid, AreaOverlays.Default)
		assertFalse(first.isCompleted)
		queue.close()
		assertNull(settled(first))
		assertNull(settled(second))
	}

	@Test
	fun aFailingCaptureAnswersNullAndTheNextIsStillServed() {
		val queue = SnapshotQueue()
		val failing = queue.request(camera, 1, 1, FrameBackdrop.Grid, AreaOverlays.Default)
		val next = queue.request(camera, 2, 2, FrameBackdrop.Grid, AreaOverlays.Default)
		queue.serve({ true }) { _, width, _, _, _ ->
			if (width == 1) {
				throw IllegalStateException("the capture broke")
			}
			image
		}
		assertNull(settled(failing))
		assertSame(image, settled(next))
	}

	@Test
	fun aStoppedLoopLeavesTheQueueForClose() {
		val queue = SnapshotQueue()
		val first = queue.request(camera, 1, 1, FrameBackdrop.Grid, AreaOverlays.Default)
		val second = queue.request(camera, 2, 2, FrameBackdrop.Grid, AreaOverlays.Default)
		queue.serve({ false }) { _, _, _, _, _ -> fail("a stopped loop must not capture") }
		assertFalse(first.isCompleted, "left for the teardown to answer")
		assertFalse(second.isCompleted)
		queue.close()
		assertNull(settled(first))
		assertNull(settled(second))
	}
}