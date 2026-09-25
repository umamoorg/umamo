package org.umamo.ui.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the one-export-at-a-time rule: a second export is refused while one runs, and whatever would replace the
 * document or quit waits for the running export however it ends - two exports at once, or an export under a
 * document that is already loading its successor, is how a large model runs out of memory.
 */
class ModelExportGateTest {
	@Test
	fun aSecondExportIsRefusedUntilTheFirstEnds() =
		runTest {
			val gate = ModelExportGate()
			val release = CompletableDeferred<Unit>()

			assertTrue(gate.tryStart(this) { release.await() }, "the first export starts")
			assertTrue(gate.isBusy)
			assertFalse(gate.tryStart(this) { }, "a second export is refused while the first runs")

			release.complete(Unit)
			advanceUntilIdle()
			assertFalse(gate.isBusy)
			assertTrue(gate.tryStart(this) { }, "the next export starts once the first has ended")
		}

	@Test
	fun anExportThatEndsWithoutWritingReleasesTheGate() =
		runTest {
			val gate = ModelExportGate()

			// A cancelled file dialog, or a failure its alert has already reported: the export returns early.
			gate.tryStart(this) { }
			advanceUntilIdle()

			assertFalse(gate.isBusy)
		}

	@Test
	fun withNoExportRunningTheActionRunsAtOnce() =
		runTest {
			val gate = ModelExportGate()
			var waited = false
			var ran = false

			gate.afterPendingExport(this, onWaiting = { waited = true }) { ran = true }

			assertTrue(ran)
			assertFalse(waited, "nothing to wait for, so no waiting notice")
		}

	@Test
	fun aReplaceOrQuitWaitsForTheRunningExport() =
		runTest {
			val gate = ModelExportGate()
			val release = CompletableDeferred<Unit>()
			val order = ArrayList<String>()
			gate.tryStart(this) {
				release.await()
				order += "export"
			}

			gate.afterPendingExport(this, onWaiting = { order += "waiting" }) { order += "action" }
			advanceUntilIdle()
			assertEquals(listOf("waiting"), order, "the action waits while the export runs")

			release.complete(Unit)
			advanceUntilIdle()
			assertEquals(listOf("waiting", "export", "action"), order)
		}

	@Test
	fun aCancelledExportStillLetsTheWaitingActionRun() =
		runTest {
			val gate = ModelExportGate()
			val exportScope = CoroutineScope(coroutineContext + Job())
			var ran = false
			gate.tryStart(exportScope) { CompletableDeferred<Unit>().await() }
			gate.afterPendingExport(this) { ran = true }
			advanceUntilIdle()
			assertFalse(ran, "the action waits while the export runs")

			// The scope an export runs in ends with the window, and the export with it.
			exportScope.cancel()
			advanceUntilIdle()

			assertFalse(gate.isBusy)
			assertTrue(ran, "the waiting action runs once the export has ended, however it ended")
		}
}