package org.umamo.ui.app

import kotlinx.coroutines.test.runTest
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_log_folder_failed
import org.umamo.ui.workspace.AlertRequest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what Open Log Folder shows: nothing when the host handed the folder over, and one alert naming the folder
 * when it could not, so the rigger still has a path to copy.
 */
class LogFolderTest {
	@Test
	fun aFolderTheHostOpenedRaisesNoAlert() =
		runTest {
			val fixture = AppControllerFixture(this)

			openLogFolderOrAlert(fixture.services) { null }
			fixture.settle()

			assertTrue(fixture.argumentsOf("document.alert").isEmpty())
		}

	@Test
	fun aFolderTheHostCouldNotOpenIsNamedInOneAlert() =
		runTest {
			val fixture = AppControllerFixture(this)

			openLogFolderOrAlert(fixture.services) { "/home/rigger/.local/share/umamo/logs" }
			fixture.settle()

			assertEquals(
				listOf<Any?>(AlertRequest(Res.string.alert_log_folder_failed, listOf("/home/rigger/.local/share/umamo/logs"))),
				fixture.argumentsOf("document.alert"),
			)
		}
}