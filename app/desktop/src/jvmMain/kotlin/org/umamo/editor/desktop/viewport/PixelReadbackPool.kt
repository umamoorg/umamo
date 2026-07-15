package org.umamo.editor.desktop.viewport

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.ImageInfo
import org.lwjgl.opengl.GL11
import org.lwjgl.opengl.GL15
import org.lwjgl.opengl.GL21
import org.lwjgl.opengl.GL30
import org.lwjgl.opengl.GL32
import org.umamo.render.ViewportCamera
import org.umamo.runtime.model.PuppetModel
import java.nio.ByteBuffer
import java.util.ArrayDeque
import org.jetbrains.skia.Image as SkiaImage

/**
 * A read-back whose fence has signaled: the built Compose bitmap (null if the PBO map failed) plus the
 * camera and model the pixels reflect, tagged with the area they belong to. The engine matches [areaId]
 * back to its slot and publishes the frame; a read-back whose slot was unregistered while in flight still
 * appears here and is discarded by the engine.
 *
 * @property String areaId The area the pixels were rendered for.
 * @property ImageBitmap bitmap The display bitmap, or null when the PBO could not be mapped.
 * @property ViewportCamera camera The camera the pixels were rendered with.
 * @property PuppetModel model The model whose geometry the pixels reflect.
 */
internal data class CompletedReadback(
	val areaId: String,
	val bitmap: ImageBitmap?,
	val camera: ViewportCamera,
	val model: PuppetModel,
)

/**
 * Pools pixel-buffer objects and drives the asynchronous read-back of the display-size resolve framebuffer.
 * All GL work here runs on the render thread (the caller guarantees a current context); this class never
 * crosses threads.
 *
 * A plain glReadPixels into a client array stalls the GL thread until the GPU finishes and the copy
 * completes. Instead [readInto] glReadPixels into a PBO (returns immediately, scheduling the copy on the GPU
 * timeline), drops a fence, and [collectCompleted] only maps the PBO on a later tick once the fence is
 * signaled - so the thread never blocks on the GPU and can keep the pipeline full while a slider is dragged.
 *
 * 読み戻しを PBO とフェンスで非同期化する。GL スレッドが GPU 完了を待って停止しないようにする。
 */
internal class PixelReadbackPool {
	/** A recyclable pixel-buffer object and its current byte capacity. */
	private class Pbo(val id: Int, var capacity: Int)

	/** An in-flight asynchronous read-back: which area/size, into which PBO, gated by which fence, of which model. */
	private class Readback(
		val areaId: String,
		val width: Int,
		val height: Int,
		val pbo: Pbo,
		val fence: Long,
		val camera: ViewportCamera,
		val model: PuppetModel,
	)

	private val freePbos = ArrayDeque<Pbo>()
	private val inFlight = ArrayDeque<Readback>()

	/** True while any read-back is still awaiting its fence, so the loop should poll at the busy cadence. */
	fun hasPending(): Boolean = inFlight.isNotEmpty()

	/**
	 * Kicks off an asynchronous read-back of the display-size resolve framebuffer into a fresh PBO gated by a
	 * fence. Binds [resolveFbo] as the read framebuffer explicitly (rather than relying on residual bind
	 * state) so the read source is unambiguous; the FBO's single color attachment makes glReadBuffer's
	 * default correct. Returns immediately; the result is collected later by [collectCompleted].
	 *
	 * @param Int resolveFbo The display-size resolve framebuffer to read.
	 * @param String areaId The area this read-back belongs to.
	 * @param Int width The read width in pixels.
	 * @param Int height The read height in pixels.
	 * @param ViewportCamera camera The camera the pixels were rendered with (bundled into the frame).
	 * @param PuppetModel model The model the pixels reflect (bundled into the frame).
	 */
	fun readInto(
		resolveFbo: Int,
		areaId: String,
		width: Int,
		height: Int,
		camera: ViewportCamera,
		model: PuppetModel,
	) {
		GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, resolveFbo)
		val pbo = acquirePbo(width * height * 4)
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo.id)
		// With a PBO bound, the last argument is a BUFFER OFFSET, not a client pointer - glReadPixels
		// returns immediately and the copy happens asynchronously on the GPU timeline.
		GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, 0L)
		GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)

		val fence = GL32.glFenceSync(GL32.GL_SYNC_GPU_COMMANDS_COMPLETE, 0)
		GL11.glFlush() // make sure the commands + fence are submitted so the fence can eventually signal

		inFlight.addLast(Readback(areaId, width, height, pbo, fence, camera, model))
	}

	/**
	 * Collects every read-back whose fence has signaled (in submission order), maps its PBO without blocking,
	 * builds the ImageBitmap, and recycles the PBO. Stops at the first unsignaled fence (later ones cannot be
	 * done before it). Returns one [CompletedReadback] per collected read-back, in order; the caller matches
	 * each to its slot.
	 *
	 * @return List The completed read-backs, in submission order.
	 */
	fun collectCompleted(): List<CompletedReadback> {
		val completed = mutableListOf<CompletedReadback>()
		while (inFlight.isNotEmpty()) {
			val readback = inFlight.first()
			val status = GL32.glClientWaitSync(readback.fence, 0, 0L)
			if (status != GL32.GL_ALREADY_SIGNALED && status != GL32.GL_CONDITION_SATISFIED) {
				break
			}
			inFlight.removeFirst()
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, readback.pbo.id)
			val mapped = GL15.glMapBuffer(GL21.GL_PIXEL_PACK_BUFFER, GL15.GL_READ_ONLY)
			val bitmap = mapped?.let { buffer -> pixelsToImageBitmap(readback.width, readback.height, buffer) }
			GL15.glUnmapBuffer(GL21.GL_PIXEL_PACK_BUFFER)
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
			GL32.glDeleteSync(readback.fence)
			freePbos.addLast(readback.pbo)
			completed.add(CompletedReadback(readback.areaId, bitmap, readback.camera, readback.model))
		}
		return completed
	}

	/**
	 * Acquires a PBO with at least [capacity] bytes from the free pool (allocating/growing as needed) and
	 * leaves it bound. Uses GL_STREAM_READ since it is written by GL and read once by the client.
	 *
	 * @param Int capacity The minimum byte capacity needed.
	 * @return Pbo The acquired PBO.
	 */
	private fun acquirePbo(capacity: Int): Pbo {
		val pbo = freePbos.pollFirst() ?: Pbo(GL15.glGenBuffers(), 0)
		if (pbo.capacity < capacity) {
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, pbo.id)
			GL15.glBufferData(GL21.GL_PIXEL_PACK_BUFFER, capacity.toLong(), GL15.GL_STREAM_READ)
			GL15.glBindBuffer(GL21.GL_PIXEL_PACK_BUFFER, 0)
			pbo.capacity = capacity
		}
		return pbo
	}

	/**
	 * Converts a bottom-up RGBA read-back into a top-down, opaque Compose [ImageBitmap]. GL returns rows
	 * bottom-to-top, so rows are flipped (matching the renderer's PNG dump).
	 *
	 * The flip is a bulk copy per row - ByteBuffer.get(byte[], ...) is one memcpy out of the mapped PBO (the
	 * direct-buffer equivalent of System.arraycopy, which cannot take a ByteBuffer source), far cheaper than
	 * the per-byte absolute reads it replaces. Alpha is then forced opaque in a separate sequential pass: the
	 * preview background is already composited into RGB, so a constant alpha keeps the image opaque without
	 * depending on the framebuffer's alpha channel.
	 *
	 * @param Int width The image width.
	 * @param Int height The image height.
	 * @param ByteBuffer pixels The bottom-up RGBA read-back (a mapped PBO).
	 * @return ImageBitmap The display bitmap.
	 */
	private fun pixelsToImageBitmap(width: Int, height: Int, pixels: ByteBuffer): ImageBitmap {
		val rowBytes = width * 4
		val topDown = ByteArray(rowBytes * height)
		for (row in 0 until height) {
			pixels.position((height - 1 - row) * rowBytes)
			pixels.get(topDown, row * rowBytes, rowBytes)
		}
		var alphaIndex = 3
		while (alphaIndex < topDown.size) {
			topDown[alphaIndex] = 255.toByte()
			alphaIndex += 4
		}
		val info = ImageInfo(width, height, ColorType.RGBA_8888, ColorAlphaType.OPAQUE)
		return SkiaImage.makeRaster(info, topDown, rowBytes).toComposeImageBitmap()
	}

	/**
	 * Deletes the pending fences and every pooled PBO. Runs on the render thread during teardown, after the
	 * caller's single glFinish barrier (so no driver worker is mid-copy on a buffer being freed).
	 */
	fun dispose() {
		for (readback in inFlight) {
			GL32.glDeleteSync(readback.fence)
			GL15.glDeleteBuffers(readback.pbo.id)
		}
		inFlight.clear()
		for (pbo in freePbos) {
			GL15.glDeleteBuffers(pbo.id)
		}
		freePbos.clear()
	}
}
