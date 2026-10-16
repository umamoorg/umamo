package org.umamo.ui.app

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.property.setCanvasSize
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.ui.document.BlankDocument
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.document.emptyDocumentBytes
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.document.recentFiles
import org.umamo.ui.document.umaBytesWithUnknownRequiredEntry
import org.umamo.ui.progress.ProgressHandle
import org.umamo.ui.progress.ProgressLabels
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_document_read_only
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.commands.DirtyDocumentPrompt
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what landing a load does, and that replacing the document goes through the unsaved-changes gate.
 * A failure must reach the rigger as an alert and open nothing; a success is recorded and swapped in; a
 * file that opened read-only says so once, up front, since Save is greyed for the document's life.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentOpenControllerTest {
	/**
	 * The open controller over [fixture], with the save controller whose gate it borrows.
	 *
	 * @param AppControllerFixture fixture The collaborators.
	 * @return DocumentOpenController The controller.
	 */
	private fun controllerOver(fixture: AppControllerFixture): DocumentOpenController = DocumentOpenController(fixture.services, DocumentSaveController(fixture.services))

	@Test
	fun aFailedLoadRaisesTheAlertAndOpensNothing() =
		runTest {
			val fixture = AppControllerFixture(this)
			val failure = DocumentOpenFailure(DocumentOpenError.Unrecognized, "notes.bin")

			controllerOver(fixture).applyDocumentLoad(DocumentLoad.Failed(failure))

			assertSame(failure, fixture.argumentsOf("document.openFailed").single(), "the rigger is told why nothing opened")
			assertTrue(fixture.opened.isEmpty(), "and the open document is left alone")
			assertTrue(fixture.settings.recentFiles().isEmpty(), "a file that would not open is not a recent file")
		}

	@Test
	fun aLoadedFileIsRecordedAndSwappedIn() =
		runTest {
			val fixture = AppControllerFixture(this)
			val load = assertIs<DocumentLoad.Loaded>(loadDocument(emptyDocumentBytes(), "rig.uma", "/rigs/rig.uma"))

			controllerOver(fixture).applyDocumentLoad(load)

			assertSame(load.document, fixture.opened.single())
			assertEquals(listOf("/rigs/rig.uma"), fixture.settings.recentFiles())
			assertTrue(fixture.invocations.isEmpty(), "a document that can be saved opens without an alert")
		}

	@Test
	fun aReadOnlyDocumentSaysSoOnceAsItOpens() =
		runTest {
			val fixture = AppControllerFixture(this)
			val load = assertIs<DocumentLoad.Loaded>(loadDocument(umaBytesWithUnknownRequiredEntry(), "rig.uma", "/rigs/rig.uma"))

			controllerOver(fixture).applyDocumentLoad(load)

			assertSame(load.document, fixture.opened.single(), "it still opens: it can be viewed, edited, and exported")
			val alert = assertIs<AlertRequest>(fixture.argumentsOf("document.alert").single())
			assertEquals(Res.string.alert_document_read_only, alert.message)
			assertEquals(listOf<Any>("rig.uma", "future/thing.json (mystery)"), alert.arguments, "naming the file and every entry that blocks saving")
		}

	/**
	 * Opening a stored path is the one entry every file the editor is HANDED comes through - Open Recent, a
	 * document the operating system passes over, a file dropped on the window - so the unsaved-changes gate
	 * has to sit on it and not on each of those callers.
	 */
	@Test
	fun openingAStoredPathOverADirtyDocumentAsksFirst() =
		runTest {
			val fixture = AppControllerFixture(this)
			val open = controllerOver(fixture)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val session = assertNotNull(fixture.context.session)
			session.setCanvasSize(session.model.value.canvasWidth + 64f, session.model.value.canvasHeight)

			open.openStoredPath("/rigs/missing.uma")

			assertTrue(fixture.opened.isEmpty(), "nothing is read until the prompt is answered")
			assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").single()).discard()
			fixture.settle()
			// The path does not exist, so the load fails - which is the proof it was attempted at all.
			assertEquals(1, fixture.argumentsOf("document.openFailed").size, "and then the load runs")
		}

	@Test
	fun aNewDocumentOverADirtyOneAsksFirst() =
		runTest {
			val fixture = AppControllerFixture(this)
			val open = controllerOver(fixture)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val session = assertNotNull(fixture.context.session)
			session.setCanvasSize(session.model.value.canvasWidth + 64f, session.model.value.canvasHeight)

			open.newDocument()

			assertTrue(fixture.opened.isEmpty(), "File > New never discards unsaved edits on its own")
			assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").single()).discard()
			assertIs<BlankDocument>(fixture.opened.single(), "choosing not to save goes on to the empty document")
		}

	@Test
	fun aLoadedDocumentSwapsInOnlyAfterARunningExportEnds() =
		runTest {
			val fixture = AppControllerFixture(this)
			val load = assertIs<DocumentLoad.Loaded>(loadDocument(emptyDocumentBytes(), "rig.uma", "/rigs/rig.uma"))
			// An export started while the file was being read: it reads the document about to be replaced.
			val exportRelease = CompletableDeferred<Unit>()
			fixture.services.modelExports.tryStart(this) { exportRelease.await() }

			controllerOver(fixture).applyDocumentLoad(load)
			runCurrent()
			assertTrue(fixture.opened.isEmpty(), "the document stays while the export reads it")

			exportRelease.complete(Unit)
			runCurrent()
			assertSame(load.document, fixture.opened.single())
		}

	@Test
	fun aFailedLoadReportsAtOnceEvenUnderARunningExport() =
		runTest {
			val fixture = AppControllerFixture(this)
			val exportRelease = CompletableDeferred<Unit>()
			fixture.services.modelExports.tryStart(this) { exportRelease.await() }
			val failure = DocumentOpenFailure(DocumentOpenError.Unrecognized, "notes.bin")

			controllerOver(fixture).applyDocumentLoad(DocumentLoad.Failed(failure))

			assertSame(failure, fixture.argumentsOf("document.openFailed").single(), "nothing is swapped, so nothing waits")
			exportRelease.complete(Unit)
		}

	@Test
	fun aNewDocumentWaitsForARunningExport() =
		runTest {
			val fixture = AppControllerFixture(this)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val exportRelease = CompletableDeferred<Unit>()
			fixture.services.modelExports.tryStart(this) { exportRelease.await() }

			controllerOver(fixture).newDocument()
			runCurrent()
			assertTrue(fixture.opened.isEmpty())

			exportRelease.complete(Unit)
			runCurrent()
			assertIs<BlankDocument>(fixture.opened.single())
		}

	/**
	 * A loader that holds every load until the test lets it go, recording where it ran and the task handle
	 * it was given.
	 *
	 * @property DocumentLoad result What every load returns.
	 */
	private class HeldLoader(private val result: DocumentLoad) {
		/** Completed with the load's task handle once a load has started. */
		val entered = CompletableDeferred<ProgressHandle?>()

		/** Lets the held load return. */
		val release = CompletableDeferred<Unit>()

		/** How many loads ran. */
		val calls = AtomicInteger()

		/** The thread the last load ran on. */
		@Volatile
		var thread: Thread? = null

		/**
		 * One load: recorded, held, then answered.
		 *
		 * @param PlatformFile           file     The file.
		 * @param SourceArtImportOptions options  The import options.
		 * @param ProgressHandle?        progress The load's task.
		 * @return DocumentLoad The result.
		 */
		suspend fun load(file: PlatformFile, options: SourceArtImportOptions, progress: ProgressHandle?): DocumentLoad {
			calls.incrementAndGet()
			thread = Thread.currentThread()
			entered.complete(progress)
			release.await()

			return result
		}
	}

	/**
	 * The open controller over [fixture] with [loader] standing in for the file reader.
	 *
	 * @param AppControllerFixture fixture The collaborators.
	 * @param HeldLoader           loader  The loader.
	 * @return DocumentOpenController The controller.
	 */
	private fun controllerOver(fixture: AppControllerFixture, loader: HeldLoader): DocumentOpenController =
		DocumentOpenController(fixture.services, DocumentSaveController(fixture.services), loader::load)

	@Test
	fun aFileLoadsOffTheUiThreadWithItsTaskOnTheStatusBar() =
		runTest {
			val fixture = AppControllerFixture(this)
			val loaded = assertIs<DocumentLoad.Loaded>(loadDocument(emptyDocumentBytes(), "rig.uma", "/rigs/rig.uma"))
			val loader = HeldLoader(loaded)
			val open = controllerOver(fixture, loader)
			// The thread itself, not its name: the coroutine debug agent names it differently per coroutine.
			val callingThread = Thread.currentThread()

			open.openStoredPath("/rigs/rig.uma")
			runCurrent()
			val progress = loader.entered.await()

			assertNotSame(callingThread, loader.thread, "the load runs on a worker, not the thread that asked")
			assertNotNull(progress, "the load can report its stages")
			val task = fixture.services.progress.tasks.value.single()
			assertEquals(ProgressLabels.DOCUMENT_OPENING, task.labelKey)
			assertEquals(listOf("rig.uma"), task.arguments)
			assertTrue(open.isOpening)

			loader.release.complete(Unit)
			fixture.settle()
			assertSame(loaded.document, fixture.opened.single())
			assertTrue(fixture.services.progress.tasks.value.isEmpty(), "the task ends with the open")
			assertFalse(open.isOpening)
		}

	@Test
	fun anotherOpenOrNewWhileOneRunsIsRefusedWithANotice() =
		runTest {
			val fixture = AppControllerFixture(this)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val session = assertNotNull(fixture.context.session)
			val loaded = assertIs<DocumentLoad.Loaded>(loadDocument(emptyDocumentBytes(), "rig.uma", "/rigs/rig.uma"))
			val loader = HeldLoader(loaded)
			val open = controllerOver(fixture, loader)
			open.openStoredPath("/rigs/rig.uma")
			runCurrent()
			loader.entered.await()

			open.openStoredPath("/rigs/other.uma")
			assertEquals("notice.document.openBusy", session.notice.value?.messageKey, "a second open says why it did nothing")
			session.clearNotice(assertNotNull(session.notice.value).serial)
			open.newDocument()
			assertEquals("notice.document.openBusy", session.notice.value?.messageKey, "and so does New")

			loader.release.complete(Unit)
			fixture.settle()
			assertEquals(1, loader.calls.get(), "only the first open read a file")
			assertSame(loaded.document, fixture.opened.single(), "and only it landed")
		}

	@Test
	fun anEditMadeWhileTheFileLoadsAsksAgainBeforeTheSwap() =
		runTest {
			val fixture = AppControllerFixture(this)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val session = assertNotNull(fixture.context.session)
			val loaded = assertIs<DocumentLoad.Loaded>(loadDocument(emptyDocumentBytes(), "rig.uma", "/rigs/rig.uma"))
			val loader = HeldLoader(loaded)
			val open = controllerOver(fixture, loader)
			open.openStoredPath("/rigs/rig.uma")
			runCurrent()
			loader.entered.await()
			assertTrue(fixture.argumentsOf("document.confirmReplace").isEmpty(), "a clean document opens without asking")

			// The window stays live while the file loads, so the rigger can edit what is about to be replaced.
			session.setCanvasSize(session.model.value.canvasWidth + 64f, session.model.value.canvasHeight)
			loader.release.complete(Unit)
			fixture.settle()

			assertTrue(fixture.opened.isEmpty(), "the edit is not thrown away unasked")
			assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").single()).discard()
			assertSame(loaded.document, fixture.opened.single(), "choosing to discard lands the load")
		}

	@Test
	fun aDocumentLeftAloneWhileTheFileLoadsIsNotAskedAboutTwice() =
		runTest {
			val fixture = AppControllerFixture(this)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val session = assertNotNull(fixture.context.session)
			session.setCanvasSize(session.model.value.canvasWidth + 64f, session.model.value.canvasHeight)
			val loaded = assertIs<DocumentLoad.Loaded>(loadDocument(emptyDocumentBytes(), "rig.uma", "/rigs/rig.uma"))
			val loader = HeldLoader(loaded)
			val open = controllerOver(fixture, loader)

			open.openStoredPath("/rigs/rig.uma")
			assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").single()).discard()
			runCurrent()
			loader.entered.await()
			loader.release.complete(Unit)
			fixture.settle()

			assertEquals(1, fixture.argumentsOf("document.confirmReplace").size, "the answer given covers the document as it stood")
			assertSame(loaded.document, fixture.opened.single())
		}
}