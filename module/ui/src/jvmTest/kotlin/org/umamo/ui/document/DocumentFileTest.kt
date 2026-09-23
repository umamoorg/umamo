package org.umamo.ui.document

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins what a quit or a document replace does about a save that is still being written: it waits.
 *
 * The write runs on a background thread the process does not wait for, so an exit that goes ahead
 * mid-save kills it - the file never lands and a partial temporary is left on disk.  Nothing about the
 * document stops that on its own, because an import that was never edited is clean, with nothing unsaved
 * to ask about.  So the wait is the guard, and these are its four outcomes.
 *
 * Also pins the name an export suggests, which follows where the document saves.
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

	/**
	 * A document born from a file of [path], for the export-name cases: an artwork document over the blank
	 * document's empty rig, since only the path matters here.
	 *
	 * @param String path The origin file's path.
	 * @return DocumentFile The document's file holder.
	 */
	private fun fileFrom(path: String): DocumentFile {
		val blank = newBlankDocument()
		return DocumentFile(ArtDocument(path, blank.puppet, blank.textures, blank.artRasters, blank.liveParams, emptyList()))
	}

	/** An export names itself after the file the document came from, minus whatever extension that carries. */
	@Test
	fun anExportIsNamedAfterTheOriginFileWithoutItsExtension() {
		assertEquals("hero", fileFrom("/art/hero.psd").exportBaseName, "never hero.psd.png")
		assertEquals("Model", fileFrom("/rigs/Model.cmo3").exportBaseName)
		assertEquals("Model", fileFrom("C:\\rigs\\Model.MOC3").exportBaseName)
		assertEquals("v1.2 sketch", fileFrom("/art/v1.2 sketch").exportBaseName, "a dot inside the name is not an extension")
	}

	/** Once the document saves to a `.uma`, an export is named after that file instead. */
	@Test
	fun anExportFollowsTheFileTheDocumentSavesTo() {
		val file = fileFrom("/art/hero.psd")
		file.umaPath = "/rigs/hero final.uma"
		assertEquals("hero final", file.exportBaseName)
	}

	/** A document never saved and born from no file has no name to suggest; the caller supplies the untitled one. */
	@Test
	fun aNewDocumentHasNoExportName() {
		val file = DocumentFile(newBlankDocument())
		assertNull(file.exportBaseName)
		file.umaPath = "/rigs/hero.uma"
		assertEquals("hero", file.exportBaseName, "its first Save As names it")
	}
}