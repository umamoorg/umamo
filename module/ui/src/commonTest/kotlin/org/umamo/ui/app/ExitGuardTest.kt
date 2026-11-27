package org.umamo.ui.app

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins the host-side half of the quit guard: who decides, and what happens when nobody does.
 *
 * Both failure modes are silent and bad in opposite directions.  A guard that is never consulted means
 * the window's close button drops unsaved work without asking; a request that goes nowhere when no
 * guard is installed means the close button stops closing the app at all.
 */
class ExitGuardTest {
	@Test
	fun withNoGuardInstalledTheExitRunsAtOnce() {
		val guard = ExitGuard()
		var exitCount = 0

		guard.request { exitCount++ }

		assertEquals(1, exitCount)
	}

	@Test
	fun anInstalledGuardReceivesTheExitAndDecidesWhenItRuns() {
		val guard = ExitGuard()
		var exitCount = 0
		var held: (() -> Unit)? = null
		guard.install { exit -> held = exit }

		guard.request { exitCount++ }

		assertEquals(0, exitCount, "the guard holds the exit while it asks")
		assertNotNull(held, "and keeps it to run later")
		held.invoke()
		assertEquals(1, exitCount)
	}

	@Test
	fun uninstallingRestoresTheDirectExit() {
		val guard = ExitGuard()
		var guardedCount = 0
		var exitCount = 0
		val cleanup = guard.install { guardedCount++ }

		cleanup()
		guard.request { exitCount++ }

		assertEquals(0, guardedCount)
		assertEquals(1, exitCount)
	}

	@Test
	fun aStaleCleanupLeavesAReplacementGuardInPlace() {
		// The shell re-installs before a disposed composition's cleanup runs; a cleanup that cleared the
		// slot blindly would leave the app unguarded from then on.
		val guard = ExitGuard()
		var replacementCount = 0
		var exitCount = 0
		val staleCleanup = guard.install { }
		guard.install { replacementCount++ }

		staleCleanup()
		guard.request { exitCount++ }

		assertEquals(1, replacementCount)
		assertEquals(0, exitCount)
	}

	@Test
	fun theHostSeesTheWorkAnExitWaitsFor() {
		// Android keeps the platform's own back for a clean document; while an export is written it must route
		// back through the guard, which waits for the file to land.
		val guard = ExitGuard()
		var exportRunning = false
		assertFalse(guard.workRunning, "with no guard installed there is nothing to wait for")

		val cleanup = guard.install(workRunning = { exportRunning }) {}
		assertFalse(guard.workRunning)
		exportRunning = true
		assertTrue(guard.workRunning, "the host reads the probe as it is now")

		cleanup()
		assertFalse(guard.workRunning, "an uninstalled guard reports nothing")
	}
}