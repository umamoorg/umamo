package org.umamo.ui.app

import kotlinx.coroutines.test.runTest
import okio.IOException
import org.jetbrains.compose.resources.getString
import org.umamo.storage.LogLevel
import org.umamo.storage.UmamoLog
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_export_failed
import org.umamo.ui.resources.export_failed_unexpected
import org.umamo.ui.workspace.AlertRequest
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins that a failed export costs the export and never the session: whatever the export throws becomes an
 * alert and a logged stack instead of reaching Compose's window exception handler, which would show a bare Java
 * error and ask the window to close.
 */
class ExportFailureAlertTest {
	private val jarLaunch = HostHeap(2L * 1024 * 1024 * 1024, packagedLaunch = false, jarFileName = "umamo-linux-x64-0.4.0.jar")

	/**
	 * The error the guard logged for [destinationName], found by the name each test gives its own export so the
	 * app-wide log's other lines never match.
	 *
	 * @param String destinationName The export's file name.
	 * @return String? The logged message, or null when none was logged.
	 */
	private fun loggedErrorFor(destinationName: String): String? = UmamoLog.entries.value.lastOrNull { entry -> entry.level == LogLevel.Error && entry.message.contains(destinationName) }?.message

	@Test
	fun runningOutOfMemoryOnAJarLaunchNamesTheWayOut() =
		runTest {
			val fixture = AppControllerFixture(this, hostHeap = jarLaunch)

			val result = fixture.services.alertingExportFailures("oom-jar.cmo3") { throw OutOfMemoryError("Java heap space") }

			assertNull(result)
			assertEquals(listOf<Any?>(exportOutOfMemoryAlert("oom-jar.cmo3", jarLaunch)), fixture.argumentsOf("document.alert"))
			assertTrue(loggedErrorFor("oom-jar.cmo3").orEmpty().contains("ran out of memory"))
		}

	@Test
	fun runningOutOfMemoryUnderTheLauncherGetsThePlainAlert() =
		runTest {
			val packaged = HostHeap(8L * 1024 * 1024 * 1024, packagedLaunch = true, jarFileName = null)
			val fixture = AppControllerFixture(this, hostHeap = packaged)

			fixture.services.alertingExportFailures("oom-packaged.cmo3") { throw OutOfMemoryError("Java heap space") }

			assertEquals(listOf<Any?>(exportOutOfMemoryAlert("oom-packaged.cmo3", packaged)), fixture.argumentsOf("document.alert"))
		}

	@Test
	fun aFailedWriteCarriesItsOwnMessage() =
		runTest {
			val fixture = AppControllerFixture(this)

			fixture.services.alertingExportFailures("io.cmo3") { throw IOException("no space left on device") }

			assertEquals(listOf<Any?>(AlertRequest(Res.string.alert_export_failed, listOf("io.cmo3", "no space left on device"))), fixture.argumentsOf("document.alert"))
			assertTrue(loggedErrorFor("io.cmo3") != null)
		}

	@Test
	fun anExportBugBecomesAnAlert() =
		runTest {
			val fixture = AppControllerFixture(this)

			fixture.services.alertingExportFailures("bug.cmo3") { throw IllegalStateException("index 7 past the end") }

			assertEquals(
				listOf<Any?>(AlertRequest(Res.string.alert_export_failed, listOf("bug.cmo3", getString(Res.string.export_failed_unexpected)))),
				fixture.argumentsOf("document.alert"),
			)
			// The developer's message stays out of the alert and goes to the log, where a report picks it up.
			assertTrue(loggedErrorFor("bug.cmo3").orEmpty().contains("index 7 past the end"))
		}

	@Test
	fun cancellationPassesThroughWithoutAnAlert() =
		runTest {
			val fixture = AppControllerFixture(this)

			assertFailsWith<CancellationException> {
				fixture.services.alertingExportFailures("cancelled.cmo3") { throw CancellationException("the shell went away") }
			}

			assertEquals(emptyList(), fixture.argumentsOf("document.alert"))
		}

	@Test
	fun aSuccessfulExportReturnsItsResultAndRaisesNothing() =
		runTest {
			val fixture = AppControllerFixture(this)

			assertEquals(42, fixture.services.alertingExportFailures("fine.cmo3") { 42 })
			assertEquals(emptyList(), fixture.argumentsOf("document.alert"))
		}
}