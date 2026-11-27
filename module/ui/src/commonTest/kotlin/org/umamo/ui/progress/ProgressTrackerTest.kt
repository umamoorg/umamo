package org.umamo.ui.progress

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The running tasks: a task shows from its start to its end, counts its steps, starts each stage's count
 * afresh, ends once however often it is ended, and a tracked block ends its task however the block ends.
 */
class ProgressTrackerTest {
	@Test
	fun aTaskShowsFromItsStartToItsEnd() {
		val tracker = ProgressTracker()
		val opening = tracker.begin(ProgressLabels.DOCUMENT_OPENING, listOf("rig.uma"))
		val generating = tracker.begin(ProgressLabels.MESH_GENERATING, total = 3)

		assertEquals(listOf(ProgressLabels.DOCUMENT_OPENING, ProgressLabels.MESH_GENERATING), tracker.tasks.value.map { task -> task.labelKey }, "oldest first")
		assertEquals(listOf("rig.uma"), tracker.tasks.value.first().arguments)

		generating.advance()
		generating.advance(count = 2)
		assertEquals(3, tracker.tasks.value.last().completed)
		assertEquals(3, tracker.tasks.value.last().total)

		opening.finish()
		assertEquals(listOf(generating.id), tracker.tasks.value.map { task -> task.id }, "the other keeps running")
		generating.finish()
		assertTrue(tracker.tasks.value.isEmpty())
	}

	@Test
	fun aStageRelabelsTheTaskAndCountsAfresh() {
		val tracker = ProgressTracker()
		val task = tracker.begin(ProgressLabels.MESH_GENERATING, total = 4)
		task.advance(count = 4)

		task.stage(ProgressLabels.ATLAS_PACKING)

		val packing = tracker.tasks.value.single()
		assertEquals(ProgressLabels.ATLAS_PACKING, packing.labelKey)
		assertEquals(0, packing.completed)
		assertEquals(null, packing.total, "packing cannot say how far along it is")
	}

	@Test
	fun endingATaskTwiceDoesNothingTheSecondTime() {
		val tracker = ProgressTracker()
		val first = tracker.begin(ProgressLabels.ATLAS_PACKING)
		val second = tracker.begin(ProgressLabels.ATLAS_PACKING)

		first.finish()
		first.finish()
		first.advance()

		assertEquals(listOf(second.id), tracker.tasks.value.map { task -> task.id })
		assertEquals(0, tracker.tasks.value.single().completed, "a finished task's handle touches no other task")
	}

	@Test
	fun aTrackedBlockEndsItsTaskHoweverItEnds() {
		val tracker = ProgressTracker()

		val result = tracker.track(ProgressLabels.ARTWORK_RELOADING) { handle -> handle?.id }
		assertTrue(result != null && tracker.tasks.value.isEmpty(), "ended after a return")

		assertFailsWith<IllegalStateException> { tracker.track(ProgressLabels.ARTWORK_RELOADING) { error("refused") } }
		assertTrue(tracker.tasks.value.isEmpty(), "ended after a throw")

		val none: ProgressTracker? = null
		assertEquals(null, none.track(ProgressLabels.ARTWORK_RELOADING) { handle -> handle }, "no tracker, no handle, the block still runs")
	}
}