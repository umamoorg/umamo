package org.umamo.editor.desktop.viewport

import org.umamo.render.FrameOverlays
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The scrub profiler's bookkeeping: a burst of scrub frames reports once when a still frame follows, a
 * gap of over a second between scrub frames splits two scrubs into two reports, disarming reports what is
 * open, and the report carries the counts, the settings, and the differences.
 */
class ScrubProfilerTest {
	private val lines = ArrayList<String>()
	private var now = 0L
	private val profiler = ScrubProfiler(sink = { line -> lines.add(line) }, clock = { now })

	@Test
	fun aBurstReportsOnTheStillFrameThatFollowsIt() {
		profiler.setArmed(true)
		for (frame in 0 until 3) {
			now += 16_000_000L
			profiler.record("top", 800, 600, 2, FrameOverlays(), paidNanos = 9_000_000L, culledNanos = 6_000_000L, unculledNanos = 4_000_000L, artOnlyNanos = 3_000_000L)
		}
		assertEquals(1, lines.size, "nothing reports mid-scrub")

		profiler.stillFrame("top")

		assertEquals(7, lines.size, "the arm line and the six report lines")
		assertTrue(lines[1].contains("area \"top\" 800x600 @2x: 3 scrub frames") && lines[1].contains("wireframe on, cull hidden on"), lines[1])
		assertTrue(lines[2].contains("median 9.00"), lines[2])
		assertTrue(lines[6].contains("draw-order pass (culled - unculled): min 2.00 / median 2.00 / max 2.00 ms"), lines[6])
		profiler.stillFrame("top")
		assertEquals(7, lines.size, "a second still frame reports nothing")
	}

	@Test
	fun aGapSplitsTwoScrubsAndDisarmingReportsTheOpenOne() {
		profiler.setArmed(true)
		profiler.record("top", 800, 600, 1, FrameOverlays(wireframe = false), paidNanos = 5_000_000L, culledNanos = 5_000_000L, unculledNanos = null, artOnlyNanos = null)
		now += 2_000_000_000L
		profiler.record("top", 800, 600, 1, FrameOverlays(wireframe = false), paidNanos = 5_000_000L, culledNanos = 5_000_000L, unculledNanos = null, artOnlyNanos = null)
		assertEquals(4, lines.size, "the first scrub reported when the second began after the gap")
		assertTrue(lines[1].contains("1 scrub frames") && lines[1].contains("wireframe off"), lines[1])
		assertEquals(3, lines.subList(1, 4).size, "a frame without a wireframe reports the paid and culled lines only")

		profiler.setArmed(false)

		assertTrue(lines.last() == "[scrub-profile] disarmed")
		assertTrue(lines[lines.size - 4].contains("1 scrub frames"), "disarming reported the open scrub")
	}
}