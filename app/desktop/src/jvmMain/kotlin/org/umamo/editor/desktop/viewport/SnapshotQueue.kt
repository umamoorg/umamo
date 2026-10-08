package org.umamo.editor.desktop.viewport

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Deferred
import org.umamo.format.raster.RasterImage
import org.umamo.render.FrameBackdrop
import org.umamo.render.ViewportCamera
import org.umamo.storage.UmamoLog
import org.umamo.ui.viewport.AreaOverlays
import java.util.concurrent.ConcurrentLinkedQueue

/**
 * The image captures waiting for the render thread: queued by the UI thread, taken up between frames,
 * and always answered - with the premultiplied pixels, or with null once the render thread can no
 * longer serve them.  A queue for the same reason as the raster batches: two requests landing in one
 * tick must both be served.
 */
internal class SnapshotQueue {
	/**
	 * An image capture waiting for the render thread: what to draw, and where its pixels go.
	 *
	 * @property ViewportCamera                      camera   The capture's camera.
	 * @property Int                                 width    The image width in pixels.
	 * @property Int                                 height   The image height in pixels.
	 * @property FrameBackdrop                       backdrop What the puppet is drawn over.
	 * @property AreaOverlays                        overlays The grid geometry, and the grid lines and axes a grid backdrop draws.
	 * @property CompletableDeferred<RasterImage?>   result   Completed with the premultiplied pixels, or null.
	 */
	private class PendingSnapshot(
		val camera: ViewportCamera,
		val width: Int,
		val height: Int,
		val backdrop: FrameBackdrop,
		val overlays: AreaOverlays,
		val result: CompletableDeferred<RasterImage?>,
	)

	// Captures queued by the UI thread and taken up by the render thread between frames.
	private val pendingSnapshots = ConcurrentLinkedQueue<PendingSnapshot>()

	// False once the render thread can no longer serve a capture (its context never came up, or it has shut
	// down), so a request made after that is answered at once instead of waiting forever.
	@Volatile
	private var acceptingSnapshots = true

	/**
	 * Queues an image capture for the render thread.
	 *
	 * Always completes: with the premultiplied pixels, or with null when the render thread cannot serve it.
	 * The check comes after the enqueue, so a [close] racing the request still sweeps it up.
	 *
	 * @param ViewportCamera camera   The capture's camera.
	 * @param Int            width    The image width in pixels.
	 * @param Int            height   The image height in pixels.
	 * @param FrameBackdrop  backdrop What the puppet is drawn over.
	 * @param AreaOverlays   overlays The grid geometry, and the grid lines and axes a grid backdrop draws.
	 * @return Deferred<RasterImage?> The premultiplied pixels, top row first, or null.
	 */
	fun request(camera: ViewportCamera, width: Int, height: Int, backdrop: FrameBackdrop, overlays: AreaOverlays): Deferred<RasterImage?> {
		val snapshot = PendingSnapshot(camera, width, height, backdrop, overlays, CompletableDeferred())
		pendingSnapshots.add(snapshot)
		if (!acceptingSnapshots) {
			failAll()
		}
		return snapshot.result
	}

	/**
	 * Stops accepting captures and answers every queued one with null: the render thread will not serve
	 * them.  Safe to call more than once and from either thread; a request that lands afterwards is
	 * answered the same way at once.
	 */
	fun close() {
		acceptingSnapshots = false
		failAll()
	}

	/** Answers every queued capture with null. */
	private fun failAll() {
		while (true) {
			val snapshot = pendingSnapshots.poll() ?: break
			snapshot.result.complete(null)
		}
	}

	/**
	 * Renders every queued capture, on the render thread.  A capture that fails logs and completes null; it
	 * never takes the render loop down with it.
	 *
	 * A shutdown stops the work at the next capture (and, inside one, at the next tile when the capture
	 * polls the same gate), so dispose() waits for one tile rather than a whole large image; what is left
	 * in the queue is answered by [close].
	 *
	 * @param Function keepGoing Polled before each capture; false leaves the rest queued.
	 * @param Function capture   Draws one capture at its camera, width, height, backdrop, and overlays,
	 *   returning the premultiplied pixels, or null when it was abandoned at shutdown.
	 */
	fun serve(keepGoing: () -> Boolean, capture: (ViewportCamera, Int, Int, FrameBackdrop, AreaOverlays) -> RasterImage?) {
		while (keepGoing()) {
			val snapshot = pendingSnapshots.poll() ?: break
			try {
				val image = capture(snapshot.camera, snapshot.width, snapshot.height, snapshot.backdrop, snapshot.overlays)
				if (image == null) {
					UmamoLog.info("[GL] image capture (${snapshot.width}x${snapshot.height}) abandoned at shutdown")
				}
				snapshot.result.complete(image)
			} catch (failure: Exception) {
				UmamoLog.error("[GL] image capture (${snapshot.width}x${snapshot.height}) failed", failure)
				snapshot.result.complete(null)
			} catch (failure: OutOfMemoryError) {
				// The stitched image is the one large allocation here, and running short of it costs this
				// capture, not the viewport.
				UmamoLog.error("[GL] image capture (${snapshot.width}x${snapshot.height}) ran out of memory", failure)
				snapshot.result.complete(null)
			}
		}
	}
}