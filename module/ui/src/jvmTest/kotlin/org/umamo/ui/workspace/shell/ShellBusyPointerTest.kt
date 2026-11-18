package org.umamo.ui.workspace.shell

import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTracker
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * When the shell's busy pointer shows: once a tracked operation has run for the grace, and not a frame
 * longer than the last one runs.  A reader recomposes only when that answer flips, never for a task's steps.
 *
 * Stepped by hand on the main clock, since the grace is a delay the clock owns.
 */
@OptIn(ExperimentalTestApi::class)
class ShellBusyPointerTest {
	/** A running task turns the pointer busy only once the grace has passed, and its end turns it back at once. */
	@Test
	fun theBusyPointerWaitsOutTheGraceAndEndsWithTheTask() =
		runComposeUiTest {
			val tracker = ProgressTracker()
			val probe = mount(this, tracker)

			val task = tracker.begin(ProgressLabels.ATLAS_PACKING)
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS - FRAME_MILLIS)
			assertFalse(probe.busy, "the pointer must not show busy inside the grace")

			mainClock.advanceTimeBy(2 * FRAME_MILLIS)
			assertTrue(probe.busy, "the pointer must show busy once the grace has passed")

			task.finish()
			mainClock.advanceTimeByFrame()
			assertFalse(probe.busy, "the pointer must leave busy the frame the last task ends")
		}

	/** A task that ends inside the grace never shows the busy pointer, not even for a frame. */
	@Test
	fun aTaskShorterThanTheGraceNeverShows() =
		runComposeUiTest {
			val tracker = ProgressTracker()
			val probe = mount(this, tracker)
			val compositionsBefore = probe.compositions

			val task = tracker.begin(ProgressLabels.ATLAS_PACKING)
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS / 2)
			task.finish()
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS * 2)

			assertFalse(probe.busy)
			assertEquals(compositionsBefore, probe.compositions, "a flicker would have recomposed the reader")
		}

	/** A second task while one runs, and the first one ending under it, change nothing; the last one ending does. */
	@Test
	fun overlappingTasksKeepThePointerBusyUntilTheLastEnds() =
		runComposeUiTest {
			val tracker = ProgressTracker()
			val probe = mount(this, tracker)

			val first = tracker.begin(ProgressLabels.MESH_GENERATING)
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS + FRAME_MILLIS)
			assertTrue(probe.busy)
			val compositionsWhileBusy = probe.compositions

			val second = tracker.begin(ProgressLabels.MODEL_EXPORTING, listOf("rig.cmo3"))
			mainClock.advanceTimeBy(FRAME_MILLIS)
			first.finish()
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS + FRAME_MILLIS)
			assertTrue(probe.busy, "a task still running keeps the pointer busy")
			assertEquals(compositionsWhileBusy, probe.compositions, "neither change flips the answer")

			second.finish()
			mainClock.advanceTimeByFrame()
			assertFalse(probe.busy)
		}

	/** The steps a task reports recompose nothing that reads the busy pointer. */
	@Test
	fun aTasksStepsDoNotRecomposeTheReader() =
		runComposeUiTest {
			val tracker = ProgressTracker()
			val probe = mount(this, tracker)

			val task = tracker.begin(ProgressLabels.MESH_GENERATING, total = STEP_COUNT)
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS + FRAME_MILLIS)
			assertTrue(probe.busy)
			val compositionsWhileBusy = probe.compositions

			for (stepIndex in 0 until STEP_COUNT) {
				task.advance()
				mainClock.advanceTimeByFrame()
			}

			assertEquals(compositionsWhileBusy, probe.compositions)
			assertEquals(STEP_COUNT, tracker.tasks.value.single().completed, "the steps must really have landed")
		}

	/** With no tracker - the standalone shell - the pointer is never busy. */
	@Test
	fun withNoTrackerThePointerIsNeverBusy() =
		runComposeUiTest {
			val probe = mount(this, tracker = null)
			mainClock.advanceTimeBy(BUSY_POINTER_GRACE_MS * 2)
			assertFalse(probe.busy)
		}

	/** What the reader last composed, and how many times it has composed. */
	private class BusyProbe {
		var busy: Boolean = false
		var compositions: Int = 0
	}

	/**
	 * Mounts one reader of the busy pointer over [tracker], with the main clock stepped by hand.
	 *
	 * @param ComposeUiTest    test    The running UI test.
	 * @param ProgressTracker? tracker The tracker it reads, or null for none.
	 * @return BusyProbe What the reader composes.
	 */
	private fun mount(test: ComposeUiTest, tracker: ProgressTracker?): BusyProbe {
		val probe = BusyProbe()
		test.mainClock.autoAdvance = false
		test.setContent {
			val busy by collectBusyPointerAsState(tracker)
			// Read in composition, so this scope is what the state invalidates.
			val shown = busy
			SideEffect {
				probe.busy = shown
				probe.compositions += 1
			}
		}
		test.mainClock.advanceTimeByFrame()

		return probe
	}

	private companion object {
		/** One frame of the test clock. */
		const val FRAME_MILLIS = 16L

		/** How many steps the stepping task reports. */
		const val STEP_COUNT = 40
	}
}