package org.umamo.ui.document

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.FileKind
import org.umamo.format.art.LayerBounds
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.cmo3.import.cmo3SourceArtOf
import org.umamo.render.PuppetTextures
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.artwork.ReloadArtworkRequest
import org.umamo.ui.model.artwork.ReloadArtworkResult
import org.umamo.ui.model.artwork.ReloadEntry
import org.umamo.ui.model.artwork.runReloadArtwork
import org.umamo.ui.model.repack.AtlasRepackHost
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A CMO3 export of a CMO3-origin document lowers onto a working copy read from the document's archive,
 * never onto the document's own retained graph.  After exports - one, a repeat, and one that fails part
 * way through the reconcile - an imported tile's embedded PNG is still the imported bytes (a reload
 * supersedes that tile, so its old id must keep showing the old art to undo and to a UMA save), the
 * retained graph re-emits the main.xml it opened with, and the archive is the instance it opened with.
 *
 * The document is in-memory art exported fresh and reopened as a CMO3, with layer A reloaded repainted,
 * so the export has a retained layer to rewrite and no corpus is needed.
 */
class Cmo3ExportIsolationTest {
	private val options = SourceArtImportOptions(alphaThreshold = 1, birthMeshMargin = 2)
	private val layerA = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 1))
	private val layerB = InMemoryLayer("lyid:2", "B", 1, LayerBounds(40, 40, 8, 8), solidRaster(8, 8, 2))

	/** A guid's uuid attribute in main.xml; CMO3: every *Guid element carries its value as attribute uuid. */
	private val uuidAttribute = Regex("uuid=\"([0-9a-fA-F-]{36})\"")

	/** Layer A repainted and grown under the same key: a reload that changes its size along with its pixels. */
	private val layerARepainted = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 12, 12), solidRaster(12, 12, 9))

	/**
	 * Layer A repainted in place: the same key, bounds, and size, so only its pixels change - the reload that
	 * leaves nothing else in the model different.
	 */
	private val layerARepaintedInPlace = InMemoryLayer("lyid:1", "A", 0, LayerBounds(10, 10, 8, 8), solidRaster(8, 8, 9))

	/**
	 * What a CMO3-origin document retains, captured as it opened to compare against after exports.
	 *
	 * @property CaffArchive archive         The retained model's archive instance.
	 * @property ByteArray   originalTilePng Layer A's embedded PNG as imported.
	 * @property ByteArray   mainXml         The main.xml the retained graph re-emits.
	 */
	private class RetainedState(val archive: CaffArchive, val originalTilePng: ByteArray, val mainXml: ByteArray)

	/**
	 * A reopened CMO3 whose layer A a reload superseded, ready to export.
	 *
	 * @property Cmo3Document   document          The CMO3-origin document.
	 * @property PuppetModel    edited            The session's model after the reload.
	 * @property PuppetTextures effectiveTextures The pages the session published for it.
	 * @property AtlasTileId    originalTileId    Layer A's tile as imported.
	 * @property AtlasTileId    replacementTileId The tile the reload minted over the repainted art.
	 * @property ArtSourceId    sourceId          The artwork file's id.
	 * @property RetainedState  opened            The document's retained state as it opened.
	 */
	private class ReloadedCmo3(
		val document: Cmo3Document,
		val edited: PuppetModel,
		val effectiveTextures: PuppetTextures,
		val originalTileId: AtlasTileId,
		val replacementTileId: AtlasTileId,
		val sourceId: ArtSourceId,
		val opened: RetainedState,
	)

	/**
	 * Builds the in-memory art document, exports it to a CMO3, reopens that as a CMO3-origin document, and
	 * reloads layer A repainted, waiting for the session's pages to follow the reload.
	 *
	 * @param CoroutineScope scope     The test's scope, which the repack host and the page resolver run in.
	 * @param InMemoryLayer  repainted Layer A as the reload reads it.
	 * @return ReloadedCmo3 The reloaded document, ready to export.
	 */
	private suspend fun reloadedCmo3(scope: CoroutineScope, repainted: InMemoryLayer = layerARepainted): ReloadedCmo3 {
		val artLoad = buildArtDocument(InMemoryArt(listOf(layerA, layerB)), FileKind.Psd, "a.psd", "/art/a.psd", options)
		val artDocument = assertIs<ArtDocument>(assertIs<DocumentLoad.Loaded>(artLoad).document)
		val cmo3Bytes = renderCmo3Export(artDocument, artDocument.puppet, artDocument.textures, "isolation", nowMillis = 0L, obfuscateKey = 0).bytes
		val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(loadDocument(cmo3Bytes, "a.cmo3", "/art/a.cmo3")).document)
		val before = document.puppet
		val originalTile = before.atlas.tiles.first { tile -> tile.source?.layerKey == layerA.id.raw }
		assertEquals(true, originalTile.source?.stableKey, "the reopened layer is bound by its lyid")
		val opened = retainedStateOf(document, originalTile.id)
		val sourceId = before.sources.single().id
		val session = EditorSession(before, document.liveParams.values)
		val sessionAtlasPages = SessionAtlasPages(session, before.atlas, document.textures, document.artRasters)
		val follower = scope.launch { sessionAtlasPages.follow() }
		val host =
			AtlasRepackHost(
				session = session,
				artRasters = document.artRasters,
				sessionAtlasPages = sessionAtlasPages,
				premultipliedAlpha = document.textures.premultipliedAlpha,
				scope = scope,
				report = { report -> error("the reload must not refuse: ${report.refusals.joinToString { refusal -> "${refusal.tileName}: ${refusal.reason}" }}") },
				rememberOptions = { _, _ -> },
			)
		val request = ReloadArtworkRequest(listOf(ReloadEntry(sourceId, InMemoryArt(listOf(repainted, layerB)), contentHash = "v2")), options)
		assertEquals(ReloadArtworkResult.Applied, runReloadArtwork(host, request, areaId = null), "the reload lands")
		val reloaded = session.model.value
		val replacement = reloaded.atlas.tiles.first { tile -> tile.replaces == originalTile.id }
		withTimeout(120_000) {
			while (sessionAtlasPages.binding.value.atlas !== reloaded.atlas) {
				yield()
			}
		}
		val effectiveTextures = sessionAtlasPages.binding.value.textures
		assertNotSame(document.textures, effectiveTextures, "the resolver published the reload's pages")
		follower.cancel()

		return ReloadedCmo3(document, exportedModelFor(document, session), effectiveTextures, originalTile.id, replacement.id, sourceId, opened)
	}

	/**
	 * Captures what [document] retains, for [assertRetainedAsOpened] to compare against.
	 *
	 * @param Cmo3Document document       The CMO3-origin document.
	 * @param AtlasTileId  originalTileId Layer A's tile as imported.
	 * @return RetainedState The captured state.
	 */
	private fun retainedStateOf(document: Cmo3Document, originalTileId: AtlasTileId): RetainedState =
		RetainedState(
			archive = document.cmo3.archive,
			originalTilePng = assertNotNull(document.tilePng(originalTileId), "the imported tile has an embedded PNG").copyOf(),
			mainXml = mainXmlOf(Cmo3.write(document.cmo3)),
		)

	/**
	 * Asserts the document retains exactly what it opened with: layer A's imported PNG and pixels under
	 * its original tile id, the main.xml its graph re-emits, and the archive instance.
	 *
	 * @param ReloadedCmo3 fixture The reloaded document with its state as opened.
	 * @param String       context What just ran, for the failure messages.
	 */
	private fun assertRetainedAsOpened(fixture: ReloadedCmo3, context: String) {
		val document = fixture.document
		assertContentEquals(fixture.opened.originalTilePng, document.tilePng(fixture.originalTileId), "$context: the imported tile's PNG is the imported bytes")
		assertContentEquals(layerA.raster.rgba, document.artRasters.decodeRaster(fixture.originalTileId)?.rgba, "$context: the imported tile decodes to the imported pixels")
		assertContentEquals(fixture.opened.mainXml, mainXmlOf(Cmo3.write(document.cmo3)), "$context: the retained graph re-emits the main.xml it opened with")
		assertSame(fixture.opened.archive, document.cmo3.archive, "$context: the retained archive is the instance the document opened with")
	}

	/**
	 * The decompressed main.xml out of a written `.cmo3`.
	 *
	 * @param ByteArray cmo3Bytes The whole file.
	 * @return ByteArray The main.xml bytes.
	 */
	private fun mainXmlOf(cmo3Bytes: ByteArray): ByteArray = assertNotNull(CaffCodec.read(cmo3Bytes).firstByTag(CaffArchive.TAG_MAIN_XML), "the file has a main_xml entry").content

	/**
	 * A main.xml with every guid replaced by the order it first appears in, so two exports of one edit
	 * compare equal when they write the same graph: the reconcile mints a fresh random guid for each object
	 * it creates (a resized page's atlas and texture, for instance), which no two runs share.
	 *
	 * @param ByteArray mainXml The main.xml bytes.
	 * @return String The main.xml with its guids numbered.
	 */
	private fun withGuidsNumbered(mainXml: ByteArray): String {
		val ordinalByUuid = HashMap<String, Int>()

		return uuidAttribute.replace(mainXml.decodeToString()) { match -> "uuid=\"#${ordinalByUuid.getOrPut(match.groupValues[1]) { ordinalByUuid.size }}\"" }
	}

	/**
	 * Two exports in a row each lower onto a working copy of their own and leave the document as it
	 * opened, so the second diffs against the file as imported and writes the graph the first did, which
	 * carries the reloaded pixels.
	 */
	@Test
	fun repeatedExportsLeaveTheOpenDocumentAsItOpened() =
		runBlocking {
			val fixture = reloadedCmo3(this)
			val document = fixture.document
			val first = prepareCmo3Export(document, fixture.edited, fixture.effectiveTextures, "isolation", nowMillis = 0L, obfuscateKey = 0)
			assertRetainedAsOpened(fixture, "after the first export")
			assertNotSame(document.cmo3, first.model, "the first export lowers onto a working copy")
			val firstMainXml = mainXmlOf(Cmo3.write(first.model))

			val second = prepareCmo3Export(document, fixture.edited, fixture.effectiveTextures, "isolation", nowMillis = 0L, obfuscateKey = 0)
			assertRetainedAsOpened(fixture, "after the second export")
			assertNotSame(document.cmo3, second.model, "the second export lowers onto a working copy")
			assertNotSame(first.model, second.model, "each export reads a working copy of its own")
			assertEquals(withGuidsNumbered(firstMainXml), withGuidsNumbered(mainXmlOf(Cmo3.write(second.model))), "the second export writes the graph the first did")
			assertEquals(first.report, second.report, "and owes the same report")

			// The export carries the reloaded art: layer A reads back out of the written file repainted.
			val reread = Cmo3.read(Cmo3.write(first.model))
			val rereadArt = assertNotNull(cmo3SourceArtOf(reread.root as CModelSource, fixture.sourceId) { resource -> reread.extractLayerPng(resource) }, "the written file's layers read back")
			val rereadLayerA = assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == layerA.id.raw }, "layer A is listed under its key")
			assertContentEquals(layerARepainted.raster.rgba, rereadLayerA.raster.rgba, "the export writes the repainted pixels")
		}

	/**
	 * An export that fails part way through the reconcile - after the layer rewrite has written into its
	 * target - leaves the document as it opened, and the next export still writes the reloaded art.
	 */
	@Test
	fun anExportThatFailsPartWayLeavesTheOpenDocumentAsItOpened() =
		runBlocking {
			val fixture = reloadedCmo3(this)
			val document = fixture.document

			// The same document over a raster store whose decode of the reloaded tile succeeds once and then
			// throws: the reconcile's validation reads the tile, the layer rewrite writes it into the target,
			// and the drawable-icon pass that reads it again fails - part way through the mutation.
			var replacementReads = 0
			val failingRasters =
				SourceArtRasters { tileId ->
					if (tileId == fixture.replacementTileId) {
						replacementReads++

						if (replacementReads > 1) {
							throw IllegalStateException("the raster store failed mid-export")
						}
					}

					document.artRasters.decodeRaster(tileId)
				}
			val failing = Cmo3Document(document.path, document.cmo3, document.puppet, document.textures, failingRasters, document.liveParams, document.pageSet, document.tilePng)
			assertFailsWith<IllegalStateException> {
				prepareCmo3Export(failing, fixture.edited, fixture.effectiveTextures, "isolation", nowMillis = 0L, obfuscateKey = 0)
			}
			assertEquals(2, replacementReads, "the export failed on its second read of the reloaded tile, after the first fed the layer rewrite")
			assertRetainedAsOpened(fixture, "after the failed export")

			// Nothing of the failed reconcile carries over: the next export diffs against the file as imported,
			// so it still has the reloaded layer to rewrite.
			val after = prepareCmo3Export(document, fixture.edited, fixture.effectiveTextures, "isolation", nowMillis = 0L, obfuscateKey = 0)
			assertRetainedAsOpened(fixture, "after the export that followed the failure")
			val reread = Cmo3.read(Cmo3.write(after.model))
			val rereadArt = assertNotNull(cmo3SourceArtOf(reread.root as CModelSource, fixture.sourceId) { resource -> reread.extractLayerPng(resource) }, "the written file's layers read back")
			val rereadLayerA = assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == layerA.id.raw }, "layer A is listed under its key")
			assertContentEquals(layerARepainted.raster.rgba, rereadLayerA.raster.rgba, "the export after the failure writes the repainted pixels")
		}

	/**
	 * A reload that repaints layer A without changing its size or place leaves nothing in the model different
	 * but the tile's pixels, and the export still writes them.
	 */
	@Test
	fun aRepaintThatKeepsItsSizeAndPlaceReachesTheExport() =
		runBlocking {
			val fixture = reloadedCmo3(this, repainted = layerARepaintedInPlace)
			val original = fixture.document.puppet.atlas.tiles.first { tile -> tile.id == fixture.originalTileId }
			val replacement = fixture.edited.atlas.tiles.first { tile -> tile.id == fixture.replacementTileId }
			assertEquals(original.width to original.height, replacement.width to replacement.height, "the repaint kept its size")
			assertEquals(original.placement, replacement.placement, "and its place on the page")

			val prepared = prepareCmo3Export(fixture.document, fixture.edited, fixture.effectiveTextures, "isolation", nowMillis = 0L, obfuscateKey = 0)
			assertTrue(prepared.report.notices.isEmpty(), "the repaint is written, not owed: ${prepared.report.notices}")
			assertRetainedAsOpened(fixture, "after the export")

			val reread = Cmo3.read(Cmo3.write(prepared.model))
			val rereadArt = assertNotNull(cmo3SourceArtOf(reread.root as CModelSource, fixture.sourceId) { resource -> reread.extractLayerPng(resource) }, "the written file's layers read back")
			val rereadLayerA = assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == layerA.id.raw }, "layer A is listed under its key")
			assertContentEquals(layerARepaintedInPlace.raster.rgba, rereadLayerA.raster.rgba, "the export writes the repainted pixels")
		}
}