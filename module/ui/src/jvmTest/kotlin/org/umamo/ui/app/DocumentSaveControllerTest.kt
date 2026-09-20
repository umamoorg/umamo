package org.umamo.ui.app

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.umamo.edit.setCanvasSize
import org.umamo.ui.document.DocumentLoad
import org.umamo.ui.document.UmaDocument
import org.umamo.ui.document.loadDocument
import org.umamo.ui.document.newBlankDocument
import org.umamo.ui.document.umaBytesWithUnknownRequiredEntry
import org.umamo.ui.workspace.commands.DirtyDocumentPrompt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the unsaved-changes gate in front of a document replace and a quit.
 *
 * The case that matters most is the first.  The file commands register once and the exit guard installs
 * once, at launch, when no document is open - so a gate that remembered the document it was made with
 * would find nothing to ask about for the rest of the run, and Ctrl+O would replace a dirty document with
 * no prompt at all while the File menu's own row asked correctly.  The controller reads the document that
 * is open NOW, and that is what these hold it to.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class DocumentSaveControllerTest {
	/**
	 * Opens a new document in [fixture] and edits it, so it has changes to lose.
	 *
	 * @param AppControllerFixture fixture The fixture whose open document is replaced.
	 */
	private fun openADirtyDocument(fixture: AppControllerFixture) {
		fixture.context = AppControllerFixture.contextFor(newBlankDocument())
		val session = assertNotNull(fixture.context.session)
		session.setCanvasSize(session.model.value.canvasWidth + 64f, session.model.value.canvasHeight)
		assertTrue(session.dirty.value, "the edit dirtied the document")
	}

	@Test
	fun aGateMadeAtLaunchAsksAboutTheDocumentOpenNow() =
		runTest {
			val fixture = AppControllerFixture(this)
			// Made with no document open, as the app makes it at launch.
			val save = DocumentSaveController(fixture.services)
			var proceeded = 0

			openADirtyDocument(fixture)
			save.confirmIfDirty { proceeded++ }

			assertEquals(0, proceeded, "a dirty document is never replaced without asking")
			val prompt = assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").single())
			prompt.discard()
			assertEquals(1, proceeded, "choosing not to save goes on with the replace")
		}

	@Test
	fun quittingAsksAboutTheDocumentOpenNowToo() =
		runTest {
			val fixture = AppControllerFixture(this)
			val save = DocumentSaveController(fixture.services)
			var exited = 0

			openADirtyDocument(fixture)
			save.confirmExit { exited++ }

			assertEquals(0, exited, "a dirty document is never quit without asking")
			assertEquals(1, fixture.argumentsOf("document.confirmExit").size)
			assertTrue(fixture.argumentsOf("document.confirmReplace").isEmpty(), "the quit prompt is its own, worded for quitting")
		}

	@Test
	fun aCleanDocumentIsReplacedWithoutAsking() =
		runTest {
			val fixture = AppControllerFixture(this)
			val save = DocumentSaveController(fixture.services)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			var proceeded = 0

			save.confirmIfDirty { proceeded++ }

			assertEquals(1, proceeded)
			assertTrue(fixture.invocations.isEmpty(), "with nothing to lose, nothing is asked")
		}

	@Test
	fun thePromptOffersSaveOnlyWhenTheDocumentCanBeSaved() =
		runTest {
			val fixture = AppControllerFixture(this)
			val save = DocumentSaveController(fixture.services)

			openADirtyDocument(fixture)
			assertTrue(save.canSaveNow())
			save.confirmIfDirty {}
			assertNotNull(assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").single()).save, "a saveable document offers Save")

			// A document that opened read-only can be edited but never saved, so its prompt is discard-or-cancel.
			val load = loadDocument(umaBytesWithUnknownRequiredEntry(), "rig.uma", "/rigs/rig.uma")
			val readOnly = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(load).document)
			fixture.context = AppControllerFixture.contextFor(readOnly)
			val session = assertNotNull(fixture.context.session)
			session.setCanvasSize(session.model.value.canvasWidth + 64f, session.model.value.canvasHeight)

			assertEquals(false, save.canSaveNow())
			save.confirmIfDirty {}
			assertNull(assertIs<DirtyDocumentPrompt>(fixture.argumentsOf("document.confirmReplace").last()).save, "a read-only document has no Save to offer")
		}

	@Test
	fun theGateWaitsForASaveStillBeingWritten() =
		runTest {
			val fixture = AppControllerFixture(this)
			val save = DocumentSaveController(fixture.services)
			fixture.context = AppControllerFixture.contextFor(newBlankDocument())
			val writing = CompletableDeferred<Boolean>()
			assertNotNull(fixture.context.file).saveJob = writing
			var exited = 0

			save.confirmExit { exited++ }
			runCurrent()
			assertEquals(0, exited, "nothing ends the process while the file is being written")

			writing.complete(true)
			runCurrent()
			assertEquals(1, exited, "the file landed, so the quit goes on")
		}
}