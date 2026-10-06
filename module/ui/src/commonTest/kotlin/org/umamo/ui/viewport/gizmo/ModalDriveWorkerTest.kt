package org.umamo.ui.viewport.gizmo

import androidx.compose.ui.geometry.Offset
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the modal drive worker's rules: a detached submit publishes inline, an attached one
 * only as the worker gets to it; the latest request wins and one compute runs at a time; a settle publishes
 * the latest request on the spot and nothing older publishes after it; and nothing publishes after the
 * gesture's epoch moves on or the worker is cancelled.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModalDriveWorkerTest {
	/**
	 * A worker over a recording compute: the off-thread compute waits at [gate] while one is set, so a case
	 * can hold a compute in flight.
	 */
	private class Rig {
		val gesture = ModalGestureState<String>()
		val published = ArrayList<String>()
		val sequentialRequests = ArrayList<Int>()
		val offThreadRequests = ArrayList<Int>()
		var inFlight = 0
		var mostInFlight = 0
		var gate: CompletableDeferred<Unit>? = null

		val worker =
			ModalDriveWorker<Int, String>(
				gesture = gesture,
				computeSequential = { request ->
					sequentialRequests.add(request)
					"result $request"
				},
				computeOffThread = { request ->
					offThreadRequests.add(request)
					inFlight++
					mostInFlight = maxOf(mostInFlight, inFlight)
					gate?.await()
					inFlight--
					"result $request"
				},
				publish = { request, result ->
					assertEquals("result $request", result)
					published.add(result)
				},
			)

		init {
			gesture.begin("capture", Offset.Zero)
		}
	}

	/**
	 * Attaches the rig's worker on the test's scheduler and lets it reach its first receive.
	 *
	 * @param Rig rig The rig.
	 * @return Job The worker's job.
	 */
	private fun TestScope.attach(rig: Rig): Job {
		val job = backgroundScope.launch { rig.worker.run(StandardTestDispatcher(testScheduler)) }
		runCurrent()
		return job
	}

	/** With no worker attached, a submit computes and publishes before it returns. */
	@Test
	fun aDetachedSubmitPublishesInline() {
		val rig = Rig()
		rig.worker.submit(1)
		assertEquals(listOf("result 1"), rig.published)
		assertEquals(listOf(1), rig.sequentialRequests)
		assertFalse(rig.worker.isPending)
	}

	/** With a worker attached, a submit returns at once and the worker publishes on its next turn. */
	@Test
	fun anAttachedSubmitPublishesOnlyAsTheWorkerGetsToIt() =
		runTest {
			val rig = Rig()
			attach(rig)
			rig.worker.submit(1)
			assertTrue(rig.published.isEmpty(), "an attached submit leaves the caller before anything publishes")
			assertTrue(rig.worker.isPending)
			runCurrent()
			assertEquals(listOf("result 1"), rig.published)
			assertEquals(listOf(1), rig.offThreadRequests)
			assertTrue(rig.sequentialRequests.isEmpty(), "the worker computes off the caller")
			assertFalse(rig.worker.isPending)
		}

	/** A burst of submits before the worker runs costs one compute, the latest. */
	@Test
	fun theLatestRequestWins() =
		runTest {
			val rig = Rig()
			attach(rig)
			rig.worker.submit(1)
			rig.worker.submit(2)
			rig.worker.submit(3)
			runCurrent()
			assertEquals(listOf(3), rig.offThreadRequests, "a burst costs one compute, the latest")
			assertEquals(listOf("result 3"), rig.published)
		}

	/** A compute in flight holds the next back, which is the latest submitted while it ran. */
	@Test
	fun oneComputeRunsAtATime() =
		runTest {
			val rig = Rig()
			val gate = CompletableDeferred<Unit>()
			rig.gate = gate
			attach(rig)
			rig.worker.submit(1)
			runCurrent()
			rig.worker.submit(2)
			rig.worker.submit(3)
			runCurrent()
			assertEquals(listOf(1), rig.offThreadRequests, "nothing starts while a compute is in flight")
			gate.complete(Unit)
			runCurrent()
			assertEquals(listOf(1, 3), rig.offThreadRequests)
			assertEquals(1, rig.mostInFlight)
			assertEquals(listOf("result 1", "result 3"), rig.published)
		}

	/** A settle publishes the latest request on the spot, and neither the held compute nor the queued request publishes after it. */
	@Test
	fun aSettlePublishesTheLatestAndNothingOlderFollows() =
		runTest {
			val rig = Rig()
			val gate = CompletableDeferred<Unit>()
			rig.gate = gate
			attach(rig)
			rig.worker.submit(1)
			runCurrent()
			rig.worker.submit(2)
			rig.worker.settle()
			assertEquals(listOf("result 2"), rig.published, "the settle publishes the latest on the spot")
			assertFalse(rig.worker.isPending)
			gate.complete(Unit)
			runCurrent()
			assertEquals(listOf("result 2"), rig.published, "the held compute and the queued request publish nothing after it")
			assertEquals(listOf(1), rig.offThreadRequests, "the request the settle answered is never computed again")
		}

	/** A settle with nothing submitted, or with the latest already published, computes and publishes nothing. */
	@Test
	fun aSettleWithNothingPendingDoesNothing() =
		runTest {
			val rig = Rig()
			rig.worker.settle()
			assertTrue(rig.published.isEmpty())
			attach(rig)
			rig.worker.submit(1)
			runCurrent()
			rig.worker.settle()
			assertEquals(listOf("result 1"), rig.published)
			assertTrue(rig.sequentialRequests.isEmpty(), "a settle over a published request computes nothing")
		}

	/** A gesture that ends while a compute is held never sees that compute publish, and the next gesture drives as usual. */
	@Test
	fun anEpochMoveWhileAComputeIsHeldPublishesNothing() =
		runTest {
			val rig = Rig()
			val gate = CompletableDeferred<Unit>()
			rig.gate = gate
			attach(rig)
			rig.worker.submit(1)
			runCurrent()
			rig.gesture.end()
			assertFalse(rig.worker.isPending, "an ended gesture has nothing pending")
			rig.worker.settle()
			gate.complete(Unit)
			runCurrent()
			assertTrue(rig.published.isEmpty(), "a result for an ended gesture never publishes")
			rig.gesture.begin("next", Offset.Zero)
			rig.worker.submit(2)
			runCurrent()
			assertEquals(listOf("result 2"), rig.published, "the next gesture drives as usual")
		}

	/** A worker cancelled mid-compute publishes nothing, and a submit after it computes inline. */
	@Test
	fun aCancelledWorkerPublishesNothingAndTheNextSubmitRunsInline() =
		runTest {
			val rig = Rig()
			val gate = CompletableDeferred<Unit>()
			rig.gate = gate
			val job = attach(rig)
			rig.worker.submit(1)
			runCurrent()
			job.cancel()
			runCurrent()
			gate.complete(Unit)
			runCurrent()
			assertTrue(rig.published.isEmpty(), "a cancelled compute never publishes")
			rig.worker.submit(2)
			assertEquals(listOf("result 2"), rig.published, "with no worker attached, a submit computes inline")
			assertEquals(listOf(2), rig.sequentialRequests)
		}
}