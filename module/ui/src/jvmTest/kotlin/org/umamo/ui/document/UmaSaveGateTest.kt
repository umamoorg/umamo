package org.umamo.ui.document

import io.github.vinceglb.filekit.PlatformFile
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.edit.EditorSession
import org.umamo.edit.PartChange
import org.umamo.edit.withPartVisibility
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.uma.UmaModel
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.PartId
import org.umamo.ui.model.AddArtworkRequest
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.repackPageSizeOf
import org.umamo.ui.model.runAddArtwork
import org.umamo.ui.model.runAtlasRepack
import org.umamo.ui.viewport.AtlasPageBinding
import org.umamo.ui.workspace.AreaViewStates
import org.umamo.ui.workspace.EDITOR_STATE_AREAS
import org.umamo.ui.workspace.PersistentSpaceState
import java.io.File
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The save the app runs, driven end to end on real documents: open, edit through a session, write the
 * `.uma` with the same writer File > Save uses, mark the session saved with the same snapshot, reopen
 * through the same loader, and find the edit there.  Every origin the editor can hold takes the trip - a
 * CMO3, a MOC3 family, an artwork file, and a new document with art imported into it - plus the two rules
 * the design pins: a save after a repack derives its pages (D20), and a save of an unedited reopen writes
 * the same bytes (D4).  Gated on the corpus samples; each case self-skips without its file.
 */
class UmaSaveGateTest {
	private val cmo3Sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }
	private val moc3Sample: File? = System.getProperty("moc3.sample")?.let(::File)?.takeIf { it.isFile }
	private val psdSample: File? = System.getProperty("psd.sample")?.let(::File)?.takeIf { it.isFile }

	/** A fresh directory for one case's files. */
	private fun temporaryDirectory(): File = createTempDirectory("uma-save-gate").toFile()

	/**
	 * Opens [file] the way the shell does.
	 *
	 * @param File file The sample.
	 * @return PuppetDocument The document.
	 */
	private suspend fun open(file: File): PuppetDocument = assertIs<PuppetDocument>(assertIs<DocumentLoad.Loaded>(loadDocument(PlatformFile(file))).document)

	/**
	 * The one edit every case makes: the first part hidden, which the puppet entry carries.
	 *
	 * @param EditorSession session The session.
	 * @return PartId The part hidden.
	 */
	private fun hideFirstPart(session: EditorSession): PartId {
		val partId = assertNotNull(session.model.value.parts.firstOrNull()?.id, "the sample has a part to edit")
		session.mutate(PartChange.SetVisibility(partId, false)) { model -> model.withPartVisibility(partId, false) }
		assertTrue(session.dirty.value, "the edit dirtied the document")
		return partId
	}

	/**
	 * Saves [document]'s session to a `.uma` in [directory] and reopens it.
	 *
	 * @param PuppetDocument   document  The open document.
	 * @param EditorSession    session   Its session.
	 * @param AtlasPageBinding binding   Its atlas pages.
	 * @param UmaModel         base      The document the save lays over.
	 * @param File             directory Where to write.
	 * @param String           name        The file name.
	 * @param JsonObject       editorState The editor entry's merge patch; empty for a save with no UI behind it.
	 * @return Triple The written model, the file, and the reopened document.
	 */
	private suspend fun saveAndReopen(
		document: PuppetDocument,
		session: EditorSession,
		binding: AtlasPageBinding,
		base: UmaModel,
		directory: File,
		name: String = "saved.uma",
		editorState: JsonObject = JsonObject(emptyMap()),
	): Triple<UmaModel, File, UmaDocument> {
		val target = File(directory, name)
		val snapshot = session.model.value
		val outcome = writeUmaDocument(document, base, snapshot, binding, editorState, PlatformFile(target))
		val written = assertIs<UmaWriteOutcome.Written>(outcome, "the save succeeds: ${(outcome as? UmaWriteOutcome.Failed)?.reason}")
		session.markSaved(snapshot)
		assertFalse(session.dirty.value, "the save clears the dirty marker")
		assertFalse(directory.listFiles().orEmpty().any { file -> file.name.contains(".tmp-") }, "no temporary is left behind")
		val reopened = assertIs<UmaDocument>(assertIs<DocumentLoad.Loaded>(loadDocument(target.readBytes(), target.name, target.path)).document)
		return Triple(written.uma, target, reopened)
	}

	/**
	 * The whole trip for a loaded document: edit, save, reopen with the edit, and a second save of the
	 * reopen writing the same bytes.
	 *
	 * @param PuppetDocument document The open document.
	 * @param String         label    The case, for the summary line.
	 */
	private suspend fun roundTrip(document: PuppetDocument, label: String) {
		val directory = temporaryDirectory()
		val session = EditorSession(document.puppet, document.liveParams.values)
		val hidden = hideFirstPart(session)
		val binding = AtlasPageBinding(document.puppet.atlas, document.textures)

		val (_, file, reopened) = saveAndReopen(document, session, binding, UmaModel.create(umamoWriterInfo()), directory)

		assertFalse(reopened.puppet.partById.getValue(hidden).isVisible, "the edit is in the file")
		assertEquals(session.model.value.drawables.size, reopened.puppet.drawables.size, "every drawable came back")
		assertEquals(session.model.value.atlas.tiles.size, reopened.puppet.atlas.tiles.size, "and every tile")

		// A save of the unedited reopen writes the same bytes (D4): nothing about a document depends on how it got here.
		val reopenedSession = EditorSession(reopened.puppet, reopened.liveParams.values)
		val (_, second, _) = saveAndReopen(reopened, reopenedSession, AtlasPageBinding(reopened.puppet.atlas, reopened.textures), reopened.uma, directory, "again.uma")
		assertContentEquals(file.readBytes(), second.readBytes(), "an unedited reopen saves byte for byte")
		println("uma save gate: $label -> ${file.length()} bytes, ${reopened.puppet.drawables.size} drawables")
	}

	@Test
	fun aCmo3DocumentSavesAndReopensWithItsEdit() =
		runBlocking {
			val file = cmo3Sample ?: return@runBlocking println("cmo3.sample not present; skipping the CMO3 save gate")
			roundTrip(open(file), "cmo3")
		}

	@Test
	fun aMoc3DocumentSavesAndReopensWithItsEdit() =
		runBlocking {
			val file = moc3Sample ?: return@runBlocking println("moc3.sample not present; skipping the MOC3 save gate")
			roundTrip(open(file), "moc3")
		}

	@Test
	fun anArtworkDocumentSavesAndReopensWithItsEdit() =
		runBlocking {
			val file = psdSample ?: return@runBlocking println("psd.sample not present; skipping the artwork save gate")
			roundTrip(open(file), "psd")
		}

	/**
	 * Editor state rides a save and comes back in its own area (UMA §7, D31), never dirties (D7), and is never an
	 * input to the model (Goal 6, D29): the same document saved with and without it loads the same puppet.
	 */
	@Test
	fun editorStateSurvivesASaveAndNeverTouchesTheModel() =
		runBlocking {
			val file = psdSample ?: return@runBlocking println("psd.sample not present; skipping the editor-state save gate")
			val document = open(file)
			val directory = temporaryDirectory()
			val session = EditorSession(document.puppet, document.liveParams.values)
			val binding = AtlasPageBinding(document.puppet.atlas, document.textures)

			// One area folds a branch open the way the outliner does, through the scope its leaf would be handed.
			val areaViewStates = AreaViewStates()
			areaViewStates.layoutAreaIds = listOf("area-test")
			val folds = areaViewStates.scopeFor("area-test").spaceState("outliner") { FoldProbe() }
			folds.opened = listOf("part:probe")
			assertFalse(session.dirty.value, "a view toggle is not an edit")

			val editorState = buildJsonObject { put(EDITOR_STATE_AREAS, areaViewStates.gather()) }
			val (_, _, withState) = saveAndReopen(document, session, binding, UmaModel.create(umamoWriterInfo()), directory, "with-state.uma", editorState)
			val (_, _, withoutState) = saveAndReopen(document, session, binding, UmaModel.create(umamoWriterInfo()), directory, "without-state.uma")

			val restoredAreas = assertNotNull(withState.uma.editorState)[EDITOR_STATE_AREAS]!!.jsonObject
			val reopenedFolds = AreaViewStates(restoredAreas).scopeFor("area-test").spaceState("outliner") { FoldProbe() }
			assertEquals(listOf("part:probe"), reopenedFolds.opened, "the area gets its own state back")
			assertEquals(emptyList(), AreaViewStates(restoredAreas).scopeFor("area-other").spaceState("outliner") { FoldProbe() }.opened, "and no other area does")

			assertEquals(null, withoutState.uma.editorState, "an untouched UI mints no entry")
			assertTrue(diffPuppetModels(withoutState.puppet, withState.puppet).isEmpty, "the editor entry is no input to the puppet")
			assertEquals(withoutState.puppet.atlas, withState.puppet.atlas)
			assertEquals(withoutState.puppet.sources, withState.puppet.sources)
		}

	/**
	 * The path every rig starts on now: a new document, artwork imported into it, saved.  The document has no
	 * file of its own and no base, so every tile is encoded from its raster.
	 */
	@Test
	fun aNewDocumentWithImportedArtSavesAndReopens() =
		runBlocking {
			val file = psdSample ?: return@runBlocking println("psd.sample not present; skipping the new-document save gate")
			val document = newBlankDocument()
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host = AtlasRepackHost(session, document.artRasters, sessionAtlasPages, document.textures.premultipliedAlpha, this, { report -> error("refused: ${report.refusals}") }, { _, _ -> })
			val read = assertNotNull(readArtwork(file.readBytes(), file.name))
			assertTrue(runAddArtwork(host, AddArtworkRequest(read.art, ArtSourceDescriptor(file.name, file.path, read.kind.extension), artworkImportOptions()), areaId = null))
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== session.model.value.atlas) {
					yield()
				}
			}
			val hidden = hideFirstPart(session)

			val (_, _, reopened) = saveAndReopen(document, session, sessionAtlasPages.binding.value, UmaModel.create(umamoWriterInfo()), temporaryDirectory())

			follower.cancel()
			assertFalse(reopened.puppet.partById.getValue(hidden).isVisible, "the edit is in the file")
			assertEquals(session.model.value.drawables.size, reopened.puppet.drawables.size, "every imported drawable came back")
			assertEquals(session.model.value.sources.size, reopened.puppet.sources.size, "with its artwork file listed")
			assertTrue(reopened.storedPages == null, "a packed document derives its pages")
		}

	/**
	 * A repack moves the atlas off the document's baseline, so the save derives its pages rather than
	 * storing the origin's (D20); the reopened document composes the same pages the session showed.
	 */
	@Test
	fun aSaveAfterARepackDerivesItsPages() =
		runBlocking {
			val file = cmo3Sample ?: return@runBlocking println("cmo3.sample not present; skipping the repack save gate")
			val document = assertIs<Cmo3Document>(open(file))
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host = AtlasRepackHost(session, document.artRasters, sessionAtlasPages, document.textures.premultipliedAlpha, this, { report -> error("refused: ${report.refusals}") }, { _, _ -> })
			runAtlasRepack(host, AtlasPackOptions(maxPageSize = repackPageSizeOf(document.puppet)), areaId = null)
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== session.model.value.atlas) {
					yield()
				}
			}
			val binding = sessionAtlasPages.binding.value
			assertTrue(binding.textures !== document.textures, "the repack moved the pages off the baseline")

			val (_, _, reopened) = saveAndReopen(document, session, binding, UmaModel.create(umamoWriterInfo()), temporaryDirectory())

			follower.cancel()
			assertTrue(reopened.storedPages == null, "a repacked atlas stores no render pages")
			assertEquals(binding.textures.atlases.size, reopened.textures.atlases.size, "the reopened document composes the same page count")
			for ((pageIndex, page) in reopened.textures.atlases.withIndex()) {
				assertTrue(page.rgba.contentEquals(binding.textures.atlases[pageIndex].rgba), "page $pageIndex composes as the session showed it")
			}
		}

	/**
	 * A save snapshots the model before writing; an edit that lands meanwhile is not on disk, so the document
	 * stays dirty once the save is marked.
	 */
	@Test
	fun anEditDuringASaveStaysDirty() =
		runBlocking {
			val file = cmo3Sample ?: return@runBlocking println("cmo3.sample not present; skipping the dirty-during-save gate")
			val document = open(file)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val hidden = hideFirstPart(session)
			val snapshot = session.model.value
			val target = File(temporaryDirectory(), "saved.uma")

			val outcome = writeUmaDocument(document, UmaModel.create(umamoWriterInfo()), snapshot, AtlasPageBinding(document.puppet.atlas, document.textures), JsonObject(emptyMap()), PlatformFile(target))
			assertIs<UmaWriteOutcome.Written>(outcome)
			// The edit that lands while the file is being written.
			session.mutate(PartChange.SetVisibility(hidden, true)) { model -> model.withPartVisibility(hidden, true) }
			session.markSaved(snapshot)

			assertTrue(session.dirty.value, "what is on screen is not what is on disk")
			session.undo()
			assertFalse(session.dirty.value, "undoing back to the saved instance is clean")
		}
}

/** A stand-in for a space's view state: one fold list, written and restored the way the real ones are. */
private class FoldProbe : PersistentSpaceState {
	var opened: List<String> = emptyList()

	/**
	 * The probe's member of its area block.
	 *
	 * @return JsonObject The member.
	 */
	override fun toJson(): JsonObject = buildJsonObject { put("expanded", JsonArray(opened.map(::JsonPrimitive))) }

	/**
	 * Takes the saved member.
	 *
	 * @param JsonObject tree The member as the file held it.
	 */
	override fun restore(tree: JsonObject) {
		opened = tree["expanded"]?.jsonArray?.map { element -> element.jsonPrimitive.content }.orEmpty()
	}
}