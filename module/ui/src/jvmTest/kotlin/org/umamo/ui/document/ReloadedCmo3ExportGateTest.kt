package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.art.LayerBounds
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.ACLayerGroup
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.identity.Guid
import org.umamo.format.png.PngCodec
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.interop.cmo3.cmo3SourceArtOf
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
 * image, which would decline the whole reconcile.  The pages carry the new pixels, and so does the
 * retained layer the root's model image composites, so the official editor's layered view shows the
 * reloaded art.
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
			assertTrue(
				prepared.report.notices.none { notice -> notice is ExportNotice.UnsupportedChange && notice.reason == ExportNoticeReason.AtlasTileMetadataNotReconcilable },
				"the reloaded tile's metadata is reconciled by the layer rewrite",
			)

			val reread = Cmo3.read(Cmo3.write(prepared.model))
			val reimported = Cmo3Import.fromModelSource(reread.root as CModelSource)
			assertEquals(newPlacement, reimported.atlas.tileById.getValue(tile.id).placement, "the root's entry moved to the replacement's placement")
			// The retained layer holds the repainted pixels: the layered-art reader decodes them back
			// out of the root's own layer, keyed as the reload keyed it.
			val rereadArt = assertNotNull(cmo3SourceArtOf(reread.root as CModelSource, ref.sourceId) { resource -> reread.extractLayerPng(resource) }, "the file reads back")
			val rereadLayer = assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == ref.layerKey }, "the reloaded layer is listed under its key")
			assertEquals(tile.width to tile.height, rereadLayer.raster.width to rereadLayer.raster.height)
			assertTrue(rereadLayer.raster.rgba.contentEquals(repainted.raster.rgba), "the layer's pixels are the repainted ones")
			// So is the layer's icon: every opaque pixel of the 64px thumbnail is the repainted gray.
			val rereadImage =
				assertNotNull(
					graphElements((reread.root as CModelSource).textureManager.let { manager -> (manager as CTextureManager)._rawImages })
						.mapNotNull { wrapper -> (wrapper as? LayeredImageWrapper)?.image as? CLayeredImage }
						.firstOrNull { image -> (image.guid as? Guid)?.uuid == ref.sourceId.raw },
					"the file's layered image reads back",
				)
			val rereadCLayer = assertNotNull(layersOf(rereadImage).firstOrNull { layer -> layer.name == row.name }, "the reloaded layer is in the tree")
			val iconPath = assertNotNull(((rereadCLayer.icon64 as? CImageIcon)?.image as? CWritableImage)?.image?.archivePath, "the layer has a 64px icon")
			val icon = PngCodec.read(assertNotNull(reread.archive.byPath(iconPath), "the icon is embedded").content)
			var opaquePixels = 0
			for (pixel in 0 until icon.width * icon.height) {
				if ((icon.rgba[pixel * 4 + 3].toInt() and 0xFF) == 255) {
					opaquePixels += 1
					assertEquals(listOf(0x5A, 0x5A, 0x5A), (0 until 3).map { channel -> icon.rgba[pixel * 4 + channel].toInt() and 0xFF }, "icon pixel $pixel is the repainted gray")
				}
			}
			assertTrue(opaquePixels > 0, "the icon shows the repainted art")
			val exportedPages = cmo3AtlasPages(reread.root as CModelSource) { resource -> reread.extractLayerPng(resource) }.pageBytes
			val expectedPages = effective.atlases.map { page -> encodeAtlasPng(page) }
			assertEquals(expectedPages.size, exportedPages.size, "exported page count")
			for ((pageIndex, bytes) in exportedPages.withIndex()) {
				assertTrue(expectedPages.any { expected -> expected.contentEquals(bytes) }, "exported page $pageIndex is not one of the reload's pages")
			}
		}

	/**
	 * Every image layer under a layered image's root, depth first.
	 *
	 * @param CLayeredImage image The layered image.
	 * @return List<CLayer> The layers.
	 */
	private fun layersOf(image: CLayeredImage): List<CLayer> {
		val layers = ArrayList<CLayer>()

		fun walk(group: ACLayerGroup) {
			for (entry in graphElements(group._children)) {
				when (entry) {
					is ACLayerGroup -> walk(entry)
					is CLayer -> layers.add(entry)
				}
			}
		}
		(image._rootLayer as? ACLayerGroup)?.let(::walk)
		return layers
	}

	/**
	 * A CMO3 collection field as its elements, whichever container shape the serializer used.
	 *
	 * @param Any? collection The raw field.
	 * @return List The elements.
	 */
	private fun graphElements(collection: Any?): List<Any?> =
		when (collection) {
			is Map<*, *> -> collection.values.toList()
			is Iterable<*> -> collection.toList()
			is Array<*> -> collection.toList()
			else -> emptyList()
		}

	/**
	 * A relink on a CMO3-origin document whose file is not on this machine reads the target layer
	 * from the CMO3's own decomposed layer image: the tile is replaced with that layer's pixels at
	 * the layer's size, not merely rebound.
	 */
	@Test
	fun aRelinkReadsTheDecomposedLayerWhenTheFileIsMissing() =
		runBlocking {
			val file = sample
			if (file == null) {
				println("cmo3.sample not present; skipping the decomposed relink gate")
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
			// Two placed, stably bound tiles of one file: the first is rebound to the second's layer.
			val bound = before.atlas.tiles.filter { tile -> tile.placement != null && tile.source?.stableKey == true }
			val tile = bound.first()
			val target = bound.first { candidate -> candidate.source?.sourceId == tile.source?.sourceId && candidate.source?.layerKey != tile.source?.layerKey }
			val targetRef = assertNotNull(target.source)
			val root = assertIs<CModelSource>(document.cmo3.root)
			val art = assertNotNull(cmo3SourceArtOf(root, targetRef.sourceId) { resource -> document.cmo3.extractLayerPng(resource) }, "the file reads back from the CMO3")
			assertTrue(runRelinkArtwork(host, RelinkArtworkRequest(tile.id, targetRef, art, SourceArtImportOptions()), areaId = null), "the relink pulls the decomposed layer")
			val relinked = session.model.value
			val replacement = relinked.atlas.tiles.first { candidate -> candidate.replaces == tile.id }
			assertEquals(targetRef, replacement.source)
			val targetLayer = art.layers.first { layer -> layer.id.raw == targetRef.layerKey }
			assertEquals(targetLayer.raster.width, replacement.width, "the replacement is the target layer's size")
			assertEquals(targetLayer.raster.height, replacement.height)
			val pulled = assertNotNull(document.artRasters.decodeRaster(replacement.id), "the target layer's pixels joined the store")
			assertTrue(pulled.rgba.contentEquals(targetLayer.raster.rgba), "with the decomposed layer's own bytes")
			follower.cancel()
		}
}