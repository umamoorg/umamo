package org.umamo.editor.desktop.viewport

import org.umamo.format.png.PngCodec
import org.umamo.render.device.RenderDevice
import org.umamo.render.device.RenderTarget
import org.umamo.storage.UmamoLog
import java.io.File

/**
 * The UMAMO_DUMP_PNG developer dump: the first frame the engine resolves is written as a PNG to the path
 * the variable names, once per process, and nothing happens while it is unset.  Encoding and the file
 * write live here rather than in :render - reading pixels is the renderer's business, turning them into
 * a PNG on disk is not, and keeping the split means :render needs no image library at all.  Render
 * thread only.
 */
internal class FirstFrameDump {
	// The path to dump to, or null when the variable is unset.  Read once: a process's environment is
	// fixed at launch, so the per-frame check is one null test.
	private val dumpPath: String? = System.getenv("UMAMO_DUMP_PNG")

	private var dumped = false

	/**
	 * Writes the frame resolved into [target] the first time it is called with a dump path set; a no-op
	 * afterwards and whenever no path is set.  A synchronous client read-back, so the caller runs it
	 * before any read-back PBO is bound for the frame.
	 *
	 * @param RenderDevice device The device that resolved the frame.
	 * @param RenderTarget target The display-size resolve target.
	 * @param Int          width  The frame's used width in pixels.
	 * @param Int          height The frame's used height in pixels.
	 */
	fun dumpOnce(device: RenderDevice, target: RenderTarget, width: Int, height: Int) {
		val path = dumpPath ?: return
		if (dumped) {
			return
		}
		File(path).writeBytes(PngCodec.write(device.readPixels(target, width, height)))
		dumped = true
		UmamoLog.info("[GL] puppet dumped to $path (${width}x$height)")
	}
}