package org.umamo.editor.desktop.viewport

import org.umamo.render.ViewportCamera
import org.umamo.render.device.ReadbackTicket
import org.umamo.render.device.RenderDevice
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap
import org.umamo.ui.viewport.RenderedFrame
import java.util.ArrayDeque

/**
 * The asynchronous read-backs in flight, in submission order, and what each one's pixels will mean on
 * arrival.  The engine issues one per area render and collects the finished ones front-first each loop
 * tick, publishing each to its area's slot; at teardown the rest are cancelled through the device.
 * Render thread only.
 *
 * @property RenderDevice device The device the tickets were issued by, polled and cancelled through.
 */
internal class FrameReadbackQueue(private val device: RenderDevice) {
	/** An asynchronous read-back in flight: the device ticket plus what the pixels will mean on arrival. */
	private class PendingFrame(
		val ticket: ReadbackTicket,
		val areaId: String,
		val camera: ViewportCamera,
		val model: PuppetModel,
	)

	// In-flight read-backs in submission order; polled front-first each loop tick.
	private val pendingFrames = ArrayDeque<PendingFrame>()

	/** Whether any read-back is still in flight, which keeps the loop on its short poll. */
	val hasPending: Boolean
		get() = pendingFrames.isNotEmpty()

	/**
	 * Records a read-back just begun, bound to the camera it was rendered at and the model its geometry
	 * reflects, so the frame can be published against them.
	 *
	 * @param ReadbackTicket ticket The device's ticket for the read-back.
	 * @param String         areaId The area the frame is for.
	 * @param ViewportCamera camera The plain (non-supersampled) camera the frame was rendered at.
	 * @param PuppetModel    model  The model the frame's geometry reflects.
	 */
	fun issue(ticket: ReadbackTicket, areaId: String, camera: ViewportCamera, model: PuppetModel) {
		pendingFrames.addLast(PendingFrame(ticket, areaId, camera, model))
	}

	/**
	 * Collects every read-back whose fence signaled and publishes it to its area's slot, clearing the slot's
	 * in-flight flag. A read-back whose slot was unregistered while in flight is discarded (the slot is gone).
	 *
	 * @param Map<String, AreaSlot> areas The registered areas, by id.
	 */
	fun collect(areas: Map<String, AreaSlot>) {
		// Front-first, stopping at the first still-in-flight ticket: reads complete in submission order on
		// the GPU timeline, so a later one cannot be done before an earlier one.
		while (pendingFrames.isNotEmpty()) {
			val pending = pendingFrames.first()
			val pixels = device.pollReadback(pending.ticket) ?: break
			pendingFrames.removeFirst()
			val slot = areas[pending.areaId] ?: continue
			slot.inFlight = false
			// The device's read-back is TOP-first RGBA already; the preview background is composited into
			// RGB, so it is opaque - the shared seam's Opaque path ignores the alpha bytes (no per-frame
			// alpha pass) and gives the eventual Android viewport the same conversion for free.
			val bitmap = rgbaToImageBitmap(pixels.rgba, pixels.width, pixels.height, RgbaAlphaType.Opaque)
			slot.imageState.value = RenderedFrame(bitmap, pending.camera, pending.model)
		}
	}

	/**
	 * Abandons every read-back still in flight: the fences and staging are freed through the device.
	 * For teardown, once the device has finished its pending GPU work.
	 */
	fun cancelAll() {
		while (pendingFrames.isNotEmpty()) {
			device.cancelReadback(pendingFrames.removeFirst().ticket)
		}
	}
}