package org.umamo.ui.progress

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** A task's count from worker threads reporting at once: every step counts, none is lost to a race. */
class ProgressTrackerThreadsTest {
	@Test
	fun stepsReportedFromManyThreadsAtOnceAllCount() {
		val tracker = ProgressTracker()
		val task = tracker.begin(ProgressLabels.MESH_GENERATING, total = 8 * 2000)
		val other = tracker.begin(ProgressLabels.ATLAS_PACKING)
		val workers = Executors.newFixedThreadPool(8)
		val start = CountDownLatch(1)
		val done = CountDownLatch(8)

		repeat(8) {
			workers.execute {
				start.await()

				repeat(2000) {
					task.advance()
				}

				done.countDown()
			}
		}

		start.countDown()
		assertTrue(done.await(30, TimeUnit.SECONDS))
		workers.shutdown()

		assertEquals(8 * 2000, tracker.tasks.value.first { running -> running.id == task.id }.completed)
		assertEquals(0, tracker.tasks.value.first { running -> running.id == other.id }.completed, "the other task is untouched")
	}
}