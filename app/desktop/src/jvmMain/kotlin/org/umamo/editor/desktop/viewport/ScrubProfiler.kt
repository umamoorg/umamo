package org.umamo.editor.desktop.viewport

import org.umamo.render.FrameOverlays
import org.umamo.storage.UmamoLog

/**
 * A measurement aid for the wireframe culling's cost on a real GPU, to be removed once measured: while armed,
 * every 2D frame rendered because a parameter moved (a scrub frame) is timed four ways by the engine - as
 * the scrub pays it (the overlay re-capture, the draw-order pass, and the draws), then still with the
 * wireframe culled, still unculled, and with no wireframe at all - and when the scrub ends (a frame of the
 * area without a pose change, a gap of a second between scrub frames, or the profiler disarmed) one report
 * per area goes to the log: frame count, size, the overlay settings, and min / median / max of each, with
 * the draw-order pass and the wireframe draws as the differences.
 *
 * Armed from the UI thread, recorded on the render thread: every method takes the one lock.
 *
 * @property Function sink Where a report line goes; the application log by default.
 * @property Function clock The nanosecond clock the burst gap is judged by.
 */
internal class ScrubProfiler(
	private val sink: (String) -> Unit = { line -> UmamoLog.info(line) },
	private val clock: () -> Long = System::nanoTime,
) {
	/** One scrub frame's four timings, in nanoseconds; null where the frame drew no wireframe. */
	private class Sample(val paid: Long, val culled: Long, val unculled: Long?, val artOnly: Long?)

	/** One area's run of scrub frames since its last still frame, gap, or report. */
	private class Burst(val width: Int, val height: Int, val renderScale: Int, val overlays: FrameOverlays, val startedAt: Long) {
		val samples = ArrayList<Sample>()
		var lastAt: Long = startedAt
	}

	private val lock = Any()
	private val bursts = HashMap<String, Burst>()

	/** Whether scrub frames are being timed; read by the render thread per frame. */
	@Volatile
	var armed: Boolean = false
		private set

	/**
	 * Arms or disarms the profiler; disarming reports every open burst.
	 *
	 * @param Boolean enabled Whether to time scrub frames from now on.
	 */
	fun setArmed(enabled: Boolean) {
		synchronized(lock) {
			if (armed == enabled) {
				return
			}
			armed = enabled
			if (enabled) {
				sink("[scrub-profile] armed: scrub a parameter over a 2D viewport; the report logs when the scrub ends, or when this is toggled off")
			} else {
				flushAll()
				sink("[scrub-profile] disarmed")
			}
		}
	}

	/**
	 * Records one scrub frame of [areaId].  A frame more than [BURST_GAP_NANOS] after the area's last one
	 * reports the earlier burst first, so two scrubs give two reports.
	 *
	 * @param String areaId The area.
	 * @param Int width The display width in pixels.
	 * @param Int height The display height in pixels.
	 * @param Int renderScale Framebuffer pixels per display pixel.
	 * @param FrameOverlays overlays What the frame drew.
	 * @param Long paidNanos The frame as the scrub paid it: the re-capture, the order pass, the draws.
	 * @param Long culledNanos The same frame rendered again, still, with the wireframe culled.
	 * @param Long? unculledNanos The frame still with the wireframe unculled, or null without a wireframe.
	 * @param Long? artOnlyNanos The frame still with no wireframe, or null without a wireframe.
	 */
	fun record(areaId: String, width: Int, height: Int, renderScale: Int, overlays: FrameOverlays, paidNanos: Long, culledNanos: Long, unculledNanos: Long?, artOnlyNanos: Long?) {
		synchronized(lock) {
			val now = clock()
			var burst = bursts[areaId]
			if (burst != null && now - burst.lastAt > BURST_GAP_NANOS) {
				report(areaId, burst)
				burst = null
			}
			if (burst == null) {
				burst = Burst(width, height, renderScale, overlays, now)
				bursts[areaId] = burst
			}
			burst.samples.add(Sample(paidNanos, culledNanos, unculledNanos, artOnlyNanos))
			burst.lastAt = now
		}
	}

	/**
	 * Notes a frame of [areaId] rendered without a pose change: the scrub, if one was running, has ended.
	 *
	 * @param String areaId The area.
	 */
	fun stillFrame(areaId: String) {
		synchronized(lock) {
			bursts.remove(areaId)?.let { burst -> report(areaId, burst) }
		}
	}

	/** Reports and drops every open burst. */
	private fun flushAll() {
		for ((areaId, burst) in bursts) {
			report(areaId, burst)
		}
		bursts.clear()
	}

	/**
	 * Writes one area's report to the sink.
	 *
	 * @param String areaId The area.
	 * @param Burst burst Its scrub frames.
	 */
	private fun report(areaId: String, burst: Burst) {
		val samples = burst.samples
		if (samples.isEmpty()) {
			return
		}
		val overlays = burst.overlays
		val spanSeconds = (burst.lastAt - burst.startedAt) / 1e9
		sink(
			"[scrub-profile] area \"$areaId\" ${burst.width}x${burst.height} @${burst.renderScale}x: ${samples.size} scrub frames over %.2f s; wireframe ${onOff(overlays.wireframe)}, cull hidden ${onOff(overlays.wireframeCulling)}, opacity ${(overlays.wireframeOpacity * 100f).toInt()} %%".format(spanSeconds),
		)
		sink("[scrub-profile]   as the scrub paid it (re-capture + order pass + draws): ${stats(samples.map { sample -> sample.paid })}")
		sink("[scrub-profile]   still, wireframe culled: ${stats(samples.map { sample -> sample.culled })}")
		val unculled = samples.mapNotNull { sample -> sample.unculled }
		val artOnly = samples.mapNotNull { sample -> sample.artOnly }
		if (unculled.size == samples.size && artOnly.size == samples.size) {
			sink("[scrub-profile]   still, wireframe unculled: ${stats(unculled)}")
			sink("[scrub-profile]   still, no wireframe: ${stats(artOnly)}")
			sink(
				"[scrub-profile]   draw-order pass (culled - unculled): ${stats(samples.map { sample -> sample.culled - sample.unculled!! })}; wireframe draws (unculled - none): ${stats(samples.map { sample -> sample.unculled!! - sample.artOnly!! })}; re-capture (paid - culled): ${stats(samples.map { sample -> sample.paid - sample.culled })}",
			)
		}
	}

	/**
	 * Min, median, and max of nanosecond timings, in milliseconds.
	 *
	 * @param List<Long> nanos The timings.
	 * @return String The three figures.
	 */
	private fun stats(nanos: List<Long>): String {
		val sorted = nanos.sorted()
		val median = sorted[sorted.size / 2]
		return "min %.2f / median %.2f / max %.2f ms".format(sorted.first() / 1e6, median / 1e6, sorted.last() / 1e6)
	}

	/**
	 * A flag as "on" or "off".
	 *
	 * @param Boolean flag The flag.
	 * @return String The word.
	 */
	private fun onOff(flag: Boolean): String = if (flag) "on" else "off"

	private companion object {
		/** How long between two scrub frames of one area ends the first scrub: a second. */
		const val BURST_GAP_NANOS = 1_000_000_000L
	}
}