package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.art.LayerBounds
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.render.encodeAtlasPng
import org.umamo.runtime.model.lineageRoot
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.RelinkArtworkRequest
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runRelinkArtwork
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A CMO3-origin document with a reloaded tile still exports through its retained graph: the
 * reloaded tile is a NEW tile in the model, and the atlas web has to read it as the entry its lineage
 * root imported from - moved to the replacement's placement - rather than as a pack-in with no model
 * image, which would decline the whole reconcile.  The pages carry the new pixels; the report names
 * the tile whose retained layer image is now stale.
 *
 * Skips without the corpus sample.
 */
class ReloadedCmo3ExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun aReloadedTileExportsAsItsLineageRootMoving() =
		runBlocking {
			val file = sample
			if (file == null) {
				println("cmo3.sample not present; skipping the reloaded export gate")
				return@runBlocking
			}
			val load = loadDocument(file.readBytes(), file.name, file.path)
			val document = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(load).document)
			val session = EditorSession(document.puppet, document.liveParams.values)
			val sessionAtlasPages = SessionAtlasPages(session, document.puppet.atlas, document.textures, document.artRasters)
			val follower = launch { sessionAtlasPages.follow() }
			val host =
				AtlasRepackHost(
					session = session,
					artRasters = document.artRasters,
					sessionAtlasPages = sessionAtlasPages,
					premultipliedAlpha = document.textures.premultipliedAlpha,
					scope = this,
					report = { report -> error("the relink must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			// A placed tile bound by a stable key with a drawable over it: the art the reload replaces.
			val tile =
				before.atlas.tiles.first { candidate ->
					candidate.placement != null && candidate.source?.stableKey == true && before.drawables.any { drawable -> drawable.atlasTileId == candidate.id }
				}
			val ref = assertNotNull(tile.source)
			val row = before.sources.first { source -> source.id == ref.sourceId }.layers.first { layer -> layer.key == ref.layerKey }
			// The same layer, the same size, repainted: only the pixels differ, so the graph reconcile
			// owes nothing but the page patch.
			val repainted = InMemoryLayer(ref.layerKey, row.name, 0, LayerBounds(row.left, row.top, tile.width, tile.height), solidRaster(tile.width, tile.height, 0x5A))
			val request = RelinkArtworkRequest(tile.id, ref, InMemoryArt(listOf(repainted), before.canvasWidth.toInt(), before.canvasHeight.toInt()), SourceArtImportOptions())
			assertTrue(runRelinkArtwork(host, request, areaId = null), "the reload pulls the repainted art")
			val reloaded = session.model.value
			val replacement = reloaded.atlas.tiles.first { candidate -> candidate.replaces == tile.id }
			assertEquals(tile.id, replacement.id.lineageRoot)
			val newPlacement = assertNotNull(replacement.placement, "the replacement is placed")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== reloaded.atlas) {
					yield()
				}
			}
			val effective = sessionAtlasPages.binding.value.textures
			assertNotSame(document.textures, effective, "the resolver published the reload's pages")

			val prepared =
				prepareCmo3Export(
					document = document,
					edited = exportedModelFor(document, session),
					effectiveTextures = effective,
					modelName = "gate",
					nowMillis = 0L,
					obfuscateKey = 0,
				)
			follower.cancel()
			println("export report: ${prepared.report}")
			assertTrue(
				prepared.report.notices.none { notice -> notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.AtlasPageNotRecomposed },
				"the atlas web reconciled the reloaded tile rather than declining",
			)
			assertTrue(
				prepared.report.notices.none { notice -> notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.AtlasTileRebindingNotLowered },
				"the drawables over the replacement are not rebindings as far as the graph knows",
			)
			val stale = assertNotNull(prepared.report.notices.filterIsInstance<ExportNotice.ReloadedTileImagesStale>().singleOrNull(), "the report names the stale retained image")
			assertEquals(listOf(replacement.name), stale.tileNames)

			val reread = Cmo3.read(Cmo3.write(prepared.model))
			val reimported = Cmo3Import.fromModelSource(reread.root as CModelSource)
			assertEquals(newPlacement, reimported.atlas.tileById.getValue(tile.id).placement, "the root's entry moved to the replacement's placement")
			val exportedPages = cmo3AtlasPages(reread.root as CModelSource) { resource -> reread.extractLayerPng(resource) }.pageBytes
			val expectedPages = effective.atlases.map { page -> encodeAtlasPng(page) }
			assertEquals(expectedPages.size, exportedPages.size, "exported page count")
			for ((pageIndex, bytes) in exportedPages.withIndex()) {
				assertTrue(expectedPages.any { expected -> expected.contentEquals(bytes) }, "exported page $pageIndex is not one of the reload's pages")
			}
		}
}