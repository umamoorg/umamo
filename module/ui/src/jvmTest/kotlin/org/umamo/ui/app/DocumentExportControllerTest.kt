package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.umamo.edit.EditorSession
import org.umamo.interop.moc3.Moc3ExportOptions
import org.umamo.storage.FilePicker
import org.umamo.ui.action.Command
import org.umamo.ui.document.Moc3ExportSessionOptions
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.progress.ProgressTask
import org.umamo.ui.workspace.export.ExportOptionsRequest
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins that an export shows as a task on the status bar while it works, and leaves none behind however it
 * ends: written, failed, or never started because the rigger cancelled the picker.
 *
 * Every value the tracker's task list takes is recorded, since a small document exports in milliseconds and a
 * poll would miss the task the strip shows.
 */
class DocumentExportControllerTest {
	/** A picker that answers every save with [destination]; a cancelled one is the fixture's default. */
	private class SavingPicker(private val destination: PlatformFile) : FilePicker {
		override suspend fun openFile(extensions: List<String>): PlatformFile? = null

		override suspend fun saveFile(suggestedName: String, extension: String): PlatformFile? = destination
	}

	/**
	 * Records every value the fixture's task list takes, from now until the test ends.
	 *
	 * Collected unconfined, so each update is seen as it is made rather than conflated with the next.
	 *
	 * @param CoroutineScope       scope   The test's background scope, cancelled with the test.
	 * @param AppControllerFixture fixture The fixture whose tracker is read.
	 * @return List The task lists seen, oldest first, beginning with the empty one.
	 */
	private fun recordTasks(scope: CoroutineScope, fixture: AppControllerFixture): List<List<ProgressTask>> {
		val seen = ArrayList<List<ProgressTask>>()
		scope.launch(Dispatchers.Unconfined) { fixture.services.progress.tasks.collect { tasks -> seen.add(tasks) } }

		return seen
	}

	/**
	 * The export controller over a blank document, with the shell commands an export raises recorded.
	 *
	 * @param AppControllerFixture fixture The collaborators.
	 * @return DocumentExportController The controller.
	 */
	private fun controllerOver(fixture: AppControllerFixture): DocumentExportController {
		val document = newBlankDocument()
		val puppet = OpenPuppet(document, EditorSession(document.puppet, document.liveParams.values), atlasPages = null)

		for (commandId in listOf("document.exportOptions", "document.exportReport")) {
			fixture.registry.register(Command(commandId, title = null) { argument -> fixture.invocations.add(commandId to argument) })
		}

		return DocumentExportController(fixture.services, puppet, file = null, moc3ExportOptions = Moc3ExportSessionOptions())
	}

	/**
	 * Runs [body] with a scratch directory that is removed afterward.
	 *
	 * @param Function body The test's work, handed the directory.
	 */
	private inline fun withScratchDirectory(body: (File) -> Unit) {
		val directory = createTempDirectory("export-progress").toFile()

		try {
			body(directory)
		} finally {
			directory.deleteRecursively()
		}
	}

	/**
	 * The exporting tasks among [seen]: each distinct task, as it was first seen.
	 *
	 * @param List seen The recorded task lists.
	 * @return List The exporting tasks.
	 */
	private fun exportingTasks(seen: List<List<ProgressTask>>): List<ProgressTask> =
		seen.flatten().filter { task -> task.labelKey == ProgressLabels.MODEL_EXPORTING }.distinctBy { task -> task.id }

	@Test
	fun aCmo3ExportShowsItsTaskWhileItWorksAndLeavesNoneBehind() =
		runTest {
			withScratchDirectory { directory ->
				val destination = File(directory, "rig.cmo3")
				val fixture = AppControllerFixture(this, filePicker = SavingPicker(PlatformFile(destination)))
				val seen = recordTasks(backgroundScope, fixture)

				controllerOver(fixture).exportCmo3()
				fixture.settle()

				val tasks = exportingTasks(seen)
				assertEquals(1, tasks.size, "one task covers the render and the write")
				assertEquals(listOf("rig.cmo3"), tasks.single().arguments)
				assertEquals(null, tasks.single().total, "its length is not knowable, so the bar sweeps")
				assertTrue(destination.length() > 0, "the file landed")
				assertTrue(fixture.services.progress.tasks.value.isEmpty(), "the task ends with the export")
				assertEquals(emptyList(), fixture.argumentsOf("document.alert"))
			}
		}

	@Test
	fun aMoc3ExportShowsItsTaskWhileItBuildsAndWrites() =
		runTest {
			withScratchDirectory { directory ->
				val destination = File(directory, "rig.moc3")
				val fixture = AppControllerFixture(this, filePicker = SavingPicker(PlatformFile(destination)))
				val seen = recordTasks(backgroundScope, fixture)
				val controller = controllerOver(fixture)

				controller.exportMoc3()
				val request = assertIs<ExportOptionsRequest.Moc3>(assertNotNull(fixture.argumentsOf("document.exportOptions").singleOrNull(), "the options dialog opened"))
				request.onConfirm(Moc3ExportOptions.Default)
				fixture.settle()

				val tasks = exportingTasks(seen)
				assertTrue(tasks.isNotEmpty(), "the build and the write each show their task")
				assertTrue(tasks.all { task -> task.arguments == listOf("rig.moc3") })
				assertTrue(destination.length() > 0, "the moc landed")
				assertTrue(fixture.services.progress.tasks.value.isEmpty(), "no task outlives the export")
				assertEquals(emptyList(), fixture.argumentsOf("document.alert"))
			}
		}

	@Test
	fun aFailedWriteEndsItsTaskBeforeTheAlert() =
		runTest {
			withScratchDirectory { directory ->
				val destination = File(File(directory, "missing"), "rig.cmo3")
				val fixture = AppControllerFixture(this, filePicker = SavingPicker(PlatformFile(destination)))
				val seen = recordTasks(backgroundScope, fixture)

				controllerOver(fixture).exportCmo3()
				fixture.settle()

				assertEquals(1, exportingTasks(seen).size, "the export began")
				assertFalse(destination.exists())
				assertEquals(1, fixture.argumentsOf("document.alert").size, "the failure is reported")
				assertTrue(fixture.services.progress.tasks.value.isEmpty(), "and its task is gone, so the alert is not behind one")
			}
		}

	@Test
	fun aCancelledPickerBeginsNoTask() =
		runTest {
			val fixture = AppControllerFixture(this)
			val seen = recordTasks(backgroundScope, fixture)

			controllerOver(fixture).exportCmo3()
			fixture.settle()

			assertEquals(emptyList(), exportingTasks(seen))
			assertTrue(fixture.services.progress.tasks.value.isEmpty())
		}
}