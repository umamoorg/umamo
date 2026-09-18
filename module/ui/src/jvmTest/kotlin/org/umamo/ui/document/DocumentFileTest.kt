package org.umamo.ui.document

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what a quit or a document replace does about a save that is still being written: it waits.
 *
 * The write runs on a background thread the process does not wait for, so an exit that goes ahead
 * mid-save kills it - the file never lands and a partial temporary is left on disk.  Nothing about the
 * document stops that on its own, because an import that was never edited is clean, with nothing unsaved
 * to ask about.  So the wait is the guard, and these are its four outcomes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentFileTest {
	@Test
	fun withNoSaveInFlightTheActionRunsAtOnce() =
		runTest {
			val file = DocumentFile(newBlankDocument())
			var waitCount = 0
			var actionCount = 0

			file.afterPendingSave(this, onWaiting = { waitCount++ }, action = { actionCount++ })

			assertEquals(1, actionCount)
			assertEquals(0, waitCount, "with nothing to wait for, nobody is told to wait")
		}

	@Test
	fun anActionWaitsForTheSaveInFlightAndRunsOnceItLands() =
		runTest {
			val file = DocumentFile(newBlankDocument())
			val save = CompletableDeferred<Boolean>()
			file.saveJob = save
			var waitCount = 0
			var actionCount = 0

			file.afterPendingSave(this, onWaiting = { waitCount++ }, action = { actionCount++ })
			runCurrent()

			assertEquals(1, waitCount, "the rigger is told the quit is waiting")
			assertEquals(0, actionCount, "and nothing ends the process while the file is being written")

			save.complete(true)
			runCurrent()

			assertEquals(1, actionCount, "the file landed, so the action goes on")
		}

	@Test
	fun aSaveThatFailsAbandonsTheActionThatWaitedOnIt() =
		runTest {
			val file = DocumentFile(newBlankDocument())
			val save = CompletableDeferred<Boolean>()
			file.saveJob = save
			var actionCount = 0

			file.afterPendingSave(this, action = { actionCount++ })
			save.complete(false)
			runCurrent()

			assertEquals(0, actionCount, "the rigger asked for a file and did not get one; quitting is theirs to ask for again")
		}

	@Test
	fun aSaveThatAlreadyEndedHoldsNothingBack() =
		runTest {
			val file = DocumentFile(newBlankDocument())
			file.saveJob = CompletableDeferred(false)
			var actionCount = 0

			file.afterPendingSave(this, action = { actionCount++ })

			assertEquals(1, actionCount, "a save that failed a while ago is not a save in flight")
		}
}