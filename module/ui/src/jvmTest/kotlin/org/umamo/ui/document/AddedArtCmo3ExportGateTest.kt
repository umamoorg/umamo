package org.umamo.ui.document

import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
import org.umamo.edit.EditorSession
import org.umamo.format.art.LayerBounds
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.identity.Id
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.png.PngCodec
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImportOptions
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasIngest
import org.umamo.interop.cmo3.cmo3SourceArtOf
import org.umamo.ui.model.AddArtworkRequest
import org.umamo.ui.model.AtlasRepackHost
import org.umamo.ui.model.SessionAtlasPages
import org.umamo.ui.model.runAddArtwork
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertTrue

/**
 * A CMO3-origin document with a file added through Add Artwork exports it: the retained graph gains
 * a layered image for the file with a layer per tile, a model image and an entry per tile, and the
 * created drawables over them bind through the minted web.  Read back, the file lists its layers
 * under their keys with their pixels, the drawables re-import verbatim, and a reopened export is a
 * byte-identical no-op - the same gate an unedited corpus file passes.
 *
 * Skips without the corpus sample.
 */
class AddedArtCmo3ExportGateTest {
	private val sample: File? = System.getProperty("cmo3.sample")?.let(::File)?.takeIf { it.isFile }

	@Test
	fun addedArtworkExportsAsALayeredImageAndReopensBoundByKey() =
		runBlocking {
			val file = sample
			if (file == null) {
				println("cmo3.sample not present; skipping the added-art export gate")
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
					report = { report -> error("the add must not refuse: ${report.refusals.joinToString { "${it.tileName}: ${it.reason}" }}") },
					rememberOptions = { _, _ -> },
				)
			val before = session.model.value
			// Two opaque layers keyed the way a PSD keys them, so the export writes Photoshop ids and the
			// reopened file keys them stably.
			val layerA = InMemoryLayer("lyid:9001", "Added A", 0, LayerBounds(10, 20, 24, 16), solidRaster(24, 16, 0x5A))
			val layerB = InMemoryLayer("lyid:9002", "Added B", 1, LayerBounds(40, 60, 12, 12), solidRaster(12, 12, 0x3C))
			val art = InMemoryArt(listOf(layerA, layerB), before.canvasWidth.toInt(), before.canvasHeight.toInt())
			val request = AddArtworkRequest(art, ArtSourceDescriptor("added.psd", "/art/added.psd", "psd"), SourceArtImportOptions())
			assertTrue(runAddArtwork(host, request, areaId = null), "the artwork is added")
			val grown = session.model.value
			val added = grown.sources.first { source -> source.name == "added.psd" }
			val addedTiles = grown.atlas.tiles.filter { tile -> tile.source?.sourceId == added.id }
			assertEquals(2, addedTiles.size)
			val addedDrawables = grown.drawables.filter { drawable -> drawable.atlasTileId in addedTiles.map { tile -> tile.id }.toSet() }
			assertEquals(2, addedDrawables.size, "one drawable per added layer")
			withTimeout(120_000) {
				while (sessionAtlasPages.binding.value.atlas !== grown.atlas) {
					yield()
				}
			}
			val effective = sessionAtlasPages.binding.value.textures
			assertNotSame(document.textures, effective, "the resolver published the add's pages")

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
			for (reason in listOf(ExportNoticeReason.AtlasPageNotRecomposed, ExportNoticeReason.CreatedDrawableHasNoTextureSource, ExportNoticeReason.AtlasTileMetadataNotReconcilable)) {
				assertTrue(prepared.report.notices.none { notice -> notice is ExportNotice.UnsupportedChange && notice.reason == reason }, "the added art reconciled without $reason")
			}
			val exportedBytes = Cmo3.write(prepared.model)

			// Read back: the file, its layers under their keys with their rects and pixels, the tiles,
			// and the drawables.
			val reread = Cmo3.read(exportedBytes)
			val rereadRoot = reread.root as CModelSource
			val ingest = cmo3AtlasIngest(rereadRoot)
			val rereadAdded = assertNotNull(ingest.sources.firstOrNull { source -> source.name == "added.psd" }, "the added file is a layered image: ${ingest.sources.map { source -> source.name }}")
			assertEquals("/art/added.psd", rereadAdded.path)
			assertEquals(listOf("lyid:9001", "lyid:9002"), rereadAdded.layers.map { layer -> layer.key })
			assertEquals(listOf(10, 20, 24, 16), rereadAdded.layers[0].let { layer -> listOf(layer.left, layer.top, layer.width, layer.height) })
			val rereadTiles = ingest.atlas.tiles.filter { tile -> tile.source?.sourceId == rereadAdded.id }
			assertEquals(2, rereadTiles.size, "one tile per added layer")
			assertTrue(rereadTiles.all { tile -> tile.placement != null && tile.source?.stableKey == true }, "both tiles are placed and keyed stably")
			val rereadArt = assertNotNull(cmo3SourceArtOf(rereadRoot, rereadAdded.id) { resource -> reread.extractLayerPng(resource) })
			assertContentEquals(layerA.raster.rgba, assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == "lyid:9001" }).raster.rgba, "the first layer's pixels read back")
			assertContentEquals(layerB.raster.rgba, assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == "lyid:9002" }).raster.rgba)
			// The added drawables carry thumbnails of their art, one 32px and one 16px entry each.
			val rereadMeshes = graphElements((rereadRoot.drawableSourceSet as? CDrawableSourceSet)?._sources).filterIsInstance<CArtMeshSource>()
			for (drawable in addedDrawables) {
				val mesh = assertNotNull(rereadMeshes.firstOrNull { candidate -> (candidate.id as? Id)?.idstr == drawable.id.raw }, "${drawable.name} is written")
				for ((icon, size) in listOf(mesh.icon32 to 32, mesh.icon16 to 16)) {
					val path = assertNotNull((((icon as? CImageIcon)?.image as? CWritableImage)?.image as? FileRef)?.archivePath, "${drawable.name} has a ${size}px icon")
					val decoded = PngCodec.read(assertNotNull(reread.archive.byPath(path), "icon '$path' is embedded").content)
					assertEquals(size to size, decoded.width to decoded.height)
					assertTrue((0 until decoded.width * decoded.height).any { pixel -> (decoded.rgba[pixel * 4 + 3].toInt() and 0xFF) == 255 }, "${drawable.name}'s ${size}px icon shows its opaque art")
				}
			}
			val reimported = Cmo3Import.fromModelSource(rereadRoot)
			for (drawable in addedDrawables) {
				val back = assertNotNull(reimported.drawables.firstOrNull { candidate -> candidate.id == drawable.id }, "${drawable.name} re-imports")
				assertContentEquals(drawable.mesh?.uvs, back.mesh?.uvs, "${drawable.name} uvs are verbatim")
				val key = grown.atlas.tileById.getValue(assertNotNull(drawable.atlasTileId)).source?.layerKey
				val boundTile = assertNotNull(ingest.tileIdByDrawableId[drawable.id.raw]?.let { tileId -> ingest.atlas.tileById[tileId] }, "${drawable.name} binds to a tile")
				assertEquals(key, boundTile.source?.layerKey, "${drawable.name} binds to its layer's tile")
			}

			// Reopened through the loader, an unedited re-export is the byte-identical no-op every
			// unedited CMO3 passes.
			val reopenedLoad = loadDocument(exportedBytes, "gate.cmo3", "/art/gate.cmo3")
			val reopened = assertIs<Cmo3Document>(assertIs<DocumentLoad.Loaded>(reopenedLoad).document)
			assertTrue(reopened.puppet.atlas.tiles.filter { tile -> tile.source?.sourceId?.raw == rereadAdded.id.raw }.all { tile -> tile.source?.stableKey == true }, "the added tiles reopen bound by key")
			val identity = prepareCmo3Export(reopened, reopened.puppet, reopened.textures, "gate", 0L, 0)
			assertTrue(identity.report.isEmpty, "an unedited re-export owes nothing: ${identity.report.notices}")
			val sourceMainXml = CaffCodec.read(exportedBytes).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			val reemittedMainXml = CaffCodec.read(Cmo3.write(identity.model)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content
			assertContentEquals(sourceMainXml, reemittedMainXml, "main.xml survives the reopen byte-identical")
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
}