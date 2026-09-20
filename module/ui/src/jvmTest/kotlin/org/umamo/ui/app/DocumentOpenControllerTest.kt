package org.umamo.ui.app

import kotlinx.coroutines.test.runTest
import org.umamo.edit.setCanvasSize
import org.umamo.ui.document.BlankDocument
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.DocumentOpenError
import org.umamo.ui.document.DocumentOpenFailure
import org.umamo.ui.document.emptyDocumentBytes
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.document.recentFiles
import org.umamo.ui.document.umaBytesWithUnknownRequiredEntry
import org.umamo.ui.resources.Res
import org.umamo.ui.resources.alert_document_read_only
import org.umamo.ui.workspace.AlertRequest
import org.umamo.ui.workspace.commands.DirtyDocumentPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what landing a load does, and that replacing the document goes through the unsaved-changes gate.
 * A failure must reach the rigger as an alert and open nothing; a success is recorded and swapped in; a
 * file that opened read-only says so once, up front, since Save is greyed for the document's life.
 */
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
}