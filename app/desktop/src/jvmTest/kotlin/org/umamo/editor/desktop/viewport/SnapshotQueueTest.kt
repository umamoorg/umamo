package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.runBlocking
import org.umamo.format.raster.RasterImage
import org.umamo.render.ContentBounds
import org.umamo.render.FrameBackdrop
import org.umamo.render.ViewportCamera
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
		val first = queue.request(camera, 3, 4, FrameBackdrop.Grid)
		val second = queue.request(camera, 5, 6, FrameBackdrop.Transparent)
		val served = mutableListOf<String>()
		queue.serve({ true }) { requestCamera, width, height, backdrop ->
			assertSame(camera, requestCamera)
			served += "${width}x$height:$backdrop"
			if (width == 3) image else null
		}
		assertEquals(listOf("3x4:Grid", "5x6:${FrameBackdrop.Transparent}"), served)
		assertSame(image, settled(first))
		assertNull(settled(second), "an abandoned capture answers null")
	}

	@Test
	fun aRequestAfterCloseIsAnsweredNullAtOnce() {
		val queue = SnapshotQueue()
		queue.close()
		assertNull(settled(queue.request(camera, 1, 1, FrameBackdrop.Grid)))
	}

	@Test
	fun closeAnswersEveryQueuedCapture() {
		val queue = SnapshotQueue()
		val first = queue.request(camera, 1, 1, FrameBackdrop.Grid)
		val second = queue.request(camera, 2, 2, FrameBackdrop.Grid)
		assertFalse(first.isCompleted)
		queue.close()
		assertNull(settled(first))
		assertNull(settled(second))
	}

	@Test
	fun aFailingCaptureAnswersNullAndTheNextIsStillServed() {
		val queue = SnapshotQueue()
		val failing = queue.request(camera, 1, 1, FrameBackdrop.Grid)
		val next = queue.request(camera, 2, 2, FrameBackdrop.Grid)
		queue.serve({ true }) { _, width, _, _ ->
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
		val first = queue.request(camera, 1, 1, FrameBackdrop.Grid)
		val second = queue.request(camera, 2, 2, FrameBackdrop.Grid)
		queue.serve({ false }) { _, _, _, _ -> fail("a stopped loop must not capture") }
		assertFalse(first.isCompleted, "left for the teardown to answer")
		assertFalse(second.isCompleted)
		queue.close()
		assertNull(settled(first))
		assertNull(settled(second))
	}
}