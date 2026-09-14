package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.Cmo3Model
import org.umamo.format.cmo3.caff.CaffArchive
import org.umamo.format.cmo3.caff.CaffCodec
import org.umamo.format.cmo3.model.custom.CImageResource
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.cmo3.model.gen.CImageIcon
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.reloadTileId
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The retained-graph layer web a CMO3-origin export writes: a reloaded tile rewrites its root's
 * layer and model image, a reload's added layer mints a layer in its folder, an added file mints a
 * whole layered image, the created drawables over them bind, and the whole thing reads back through
 * the codec, the ingest, and the layered-art reader.  The retained graph is an artwork-origin export
 * read back, so no corpus is needed.
 */
class Cmo3RetainedLayerWebTest {
	private val pageSize = 16
	private val now = 1_700_000_000_000L
	private val sourceA = ArtSourceId("art-0")

	private val tileEye = AtlasTile(AtlasTileId("art-0/lyid:1576"), "Eye", 4, 4, placement = AtlasPlacement(0, 2f, 2f, 1f, 1f, 0f), source = SourceLayerRef(sourceA, "lyid:1576", true))
	private val tileHair = AtlasTile(AtlasTileId("art-0/lyid:5"), "Hair", 4, 4, placement = AtlasPlacement(0, 8f, 2f, 1f, 1f, 0f), source = SourceLayerRef(sourceA, "lyid:5", true))

	private fun row(key: String, name: String, groupPath: String, left: Int, top: Int, size: Int = 4): ArtSourceLayer = ArtSourceLayer(key, name, groupPath, left, top, size, size, visible = true)

	private fun gradient(size: Int, seed: Int): RasterImage = RasterImage(size, size, ByteArray(size * size * 4) { index -> (index * 7 + seed).toByte() })

	private fun quad(left: Float, top: Float, size: Float = 4f): DrawableMesh =
		DrawableMesh(
			positions = floatArrayOf(left, top, left + size, top, left + size, top + size, left, top + size),
			uvs = floatArrayOf(0.1f, 0.1f, 0.35f, 0.1f, 0.35f, 0.35f, 0.1f, 0.35f),
			indices = intArrayOf(0, 1, 2, 0, 2, 3),
		)

	private fun drawable(id: String, tile: AtlasTile, left: Float, top: Float): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), quad(left, top), null, atlasTileId = tile.id)

	private val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, ByteArray(pageSize * pageSize * 4) { 0x40 })), pageSize, pageSize)

	/**
	 * A retained graph: the H1 artwork fixture (one file, an eye in Head/Eyes and hair at the root,
	 * both placed) exported fresh and read back, with the baseline the reconcile will diff against.
	 *
	 * @return Pair The model and its import.
	 */
	private fun retainedGraph(): Pair<Cmo3Model, PuppetModel> {
		val drawables = listOf(drawable("EyeL", tileEye, 10f, 20f), drawable("Hair", tileHair, 30f, 40f))
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = drawables,
				rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
				rootPartId = null,
				canvasWidth = 100f,
				canvasHeight = 100f,
				worldOriginX = 50f,
				worldOriginY = -50f,
				runtimeTarget = RuntimeTarget.Cubism53,
				atlas = PuppetAtlas(pages = listOf(AtlasPage(pageSize, pageSize)), tiles = listOf(tileEye, tileHair)),
				sources = listOf(ArtSource(sourceA, "a.psd", "/art/a.psd", "psd", listOf(row("lyid:1576", "Eye", "Head/Eyes", 10, 20), row("lyid:5", "Hair", "", 30, 40)), contentHash = null, lastModified = 123L)),
			)
		val rasters = mapOf(tileEye.id to gradient(4, 1), tileHair.id to gradient(4, 2))
		val fresh =
			Cmo3Conversion.freshCmo3(
				puppet = puppet,
				pages = listOf(page),
				pageIndexByDrawableId = mapOf("EyeL" to 0, "Hair" to 0),
				modelName = "Retained",
				nowMillis = now,
				obfuscateKey = 0x1234ABCD,
				tileRasters = { tileId -> rasters[tileId] },
			)
		assertTrue(fresh.report.notices.isEmpty(), "the fixture exports clean: ${fresh.report.notices}")
		val retained = Cmo3.read(Cmo3.write(fresh.model))
		return retained to Cmo3Import.fromModelSource(retained.root as CModelSource)
	}

	private fun mainXmlOf(model: Cmo3Model): ByteArray = CaffCodec.read(Cmo3.write(model)).firstByTag(CaffArchive.TAG_MAIN_XML)!!.content

	private fun iconPath(icon: Any?): String? = (((icon as? CImageIcon)?.image as? CWritableImage)?.image as? FileRef)?.archivePath

	@Test
	fun reloadedAddedAndNewFileArtReachTheRetainedGraphAndReadBack() {
		val (retained, baseline) = retainedGraph()
		val retainedSource = baseline.sources.single()
		val eyeTile = baseline.atlas.tiles.first { tile -> tile.source?.layerKey == "lyid:1576" }
		val hairTile = baseline.atlas.tiles.first { tile -> tile.source?.layerKey == "lyid:5" }
		val eyeDrawable = baseline.drawables.first { drawable -> drawable.id.raw == "EyeL" }

		// A reload of the eye: repainted at 6x6, moved on the canvas, repacked lower on the page.
		val eyeReloaded = AtlasTile(reloadTileId(eyeTile.id, baseline.atlas.tiles.mapTo(HashSet()) { tile -> tile.id }), "Eye", 6, 6, placement = AtlasPlacement(0, 2f, 8f, 1f, 1f, 0f), source = eyeTile.source, replaces = eyeTile.id)
		// The same reload's added layer, in a folder the file never had, with a created drawable over it.
		val browTile = AtlasTile(AtlasTileId("${retainedSource.id.raw}/lyid:77"), "Brow", 4, 4, placement = AtlasPlacement(0, 8f, 8f, 1f, 1f, 0f), source = SourceLayerRef(retainedSource.id, "lyid:77", true))
		// Add Artwork of a second file, with a created drawable over its one tile.
		val sourceC = ArtSourceId("art-9")
		val wingTile = AtlasTile(AtlasTileId("art-9/uuid-w"), "Wing", 4, 4, placement = AtlasPlacement(0, 12f, 12f, 1f, 1f, 0f), source = SourceLayerRef(sourceC, "uuid-w", true))
		val editedSources =
			listOf(
				retainedSource.copy(layers = retainedSource.layers.map { layer -> if (layer.key == "lyid:1576") layer.copy(left = 12, top = 24, width = 6, height = 6) else layer } + row("lyid:77", "Brow", "Head/Brows", 40, 44)),
				ArtSource(sourceC, "c.clip", null, "clip", listOf(row("uuid-w", "Wing", "", 50, 50))),
			)
		val brow = drawable("Brow", browTile, 40f, 44f)
		val wing = drawable("Wing", wingTile, 50f, 50f)
		val edited =
			baseline.copy(
				drawables = baseline.drawables.map { drawable -> if (drawable.id == eyeDrawable.id) drawable.copy(atlasTileId = eyeReloaded.id) else drawable } + brow + wing,
				rootChildren = baseline.rootChildren + OrgChild.Drawable(brow.id) + OrgChild.Drawable(wing.id),
				atlas = baseline.atlas.copy(tiles = listOf(eyeReloaded, hairTile, browTile, wingTile)),
				sources = editedSources,
			)
		val rasters = mapOf(eyeReloaded.id to gradient(6, 11), browTile.id to gradient(4, 12), wingTile.id to gradient(4, 13))

		val report = Cmo3Export.apply(edited, retained, recomposedPages = listOf(page), tileRasters = { tileId -> rasters[tileId] }, nowMillis = now)
		assertTrue(report.notices.isEmpty(), "the reconcile owes nothing: ${report.notices}")

		val reread = Cmo3.read(Cmo3.write(retained))
		val rereadRoot = reread.root as CModelSource
		val textureManager = rereadRoot.textureManager as CTextureManager

		// The inventory: the eye's rect moved and grew, the brow sits in Head/Brows, the new file lists its wing.
		val ingest = cmo3AtlasIngest(rereadRoot)
		val rereadA = assertNotNull(ingest.sources.firstOrNull { source -> source.id == retainedSource.id }, "a.psd keeps its identity")
		assertEquals(listOf("lyid:1576", "lyid:77", "lyid:5"), rereadA.layers.map { layer -> layer.key }, "the brow joins Head beside Eyes, before the root's hair")
		assertEquals(listOf("Head/Eyes", "Head/Brows", ""), rereadA.layers.map { layer -> layer.groupPath })
		assertEquals(listOf(12, 24, 6, 6), rereadA.layers[0].let { layer -> listOf(layer.left, layer.top, layer.width, layer.height) }, "the eye's rect is the refreshed row")
		assertEquals(listOf(40, 44, 4, 4), rereadA.layers[1].let { layer -> listOf(layer.left, layer.top, layer.width, layer.height) })
		val rereadC = assertNotNull(ingest.sources.firstOrNull { source -> source.name == "c.clip" }, "the added file is a layered image: ${ingest.sources.map { source -> source.name }}")
		assertEquals(listOf("name:Wing"), rereadC.layers.map { layer -> layer.key }, "a uuid key reopens as a name key")

		// The tiles: the eye keeps its root identity at the new placement and size; the minted ones are placed.
		val tileByKey = ingest.atlas.tiles.associateBy { tile -> tile.source?.layerKey }
		val eyeBack = tileByKey.getValue("lyid:1576")
		assertEquals(eyeTile.id, eyeBack.id, "the reloaded tile is its root's model image")
		assertEquals(AtlasPlacement(0, 2f, 8f, 1f, 1f, 0f), eyeBack.placement)
		assertEquals(6 to 6, eyeBack.width to eyeBack.height)
		assertEquals("Eye", eyeBack.name)
		assertEquals(AtlasPlacement(0, 8f, 8f, 1f, 1f, 0f), tileByKey.getValue("lyid:77").placement)
		assertEquals(AtlasPlacement(0, 12f, 12f, 1f, 1f, 0f), tileByKey.getValue("name:Wing").placement)
		assertEquals(AtlasPlacement(0, 8f, 2f, 1f, 1f, 0f), tileByKey.getValue("lyid:5").placement, "the untouched hair did not move")

		// The pixels: the layered-art reader decodes the new rasters out of the retained file.
		val artA = assertNotNull(cmo3SourceArtOf(rereadRoot, retainedSource.id) { resource -> reread.extractLayerPng(resource) })
		assertContentEquals(gradient(6, 11).rgba, assertNotNull(artA.layers.firstOrNull { layer -> layer.id.raw == "lyid:1576" }).raster.rgba, "the eye's layer holds the repainted pixels")
		assertContentEquals(gradient(4, 12).rgba, assertNotNull(artA.layers.firstOrNull { layer -> layer.id.raw == "lyid:77" }).raster.rgba, "the brow's layer holds its pixels")
		val artC = assertNotNull(cmo3SourceArtOf(rereadRoot, rereadC.id) { resource -> reread.extractLayerPng(resource) })
		assertContentEquals(gradient(4, 13).rgba, artC.layers.single().raster.rgba)

		// The model image and entry: the canvas placement is the new origin, the entry pair composes to it.
		val modelImages =
			Cmo3Import.elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()
				.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
		val eyeImage = assertNotNull(modelImages.firstOrNull { modelImage -> Cmo3Import.uuidOf(modelImage.guid) == eyeTile.id.raw })
		val eyePlacement = eyeImage._materialLocalToCanvasTransform as CAffine
		assertEquals(listOf(1f, 0f, 12f, 0f, 1f, 24f), listOf(eyePlacement.m00, eyePlacement.m01, eyePlacement.m02, eyePlacement.m10, eyePlacement.m11, eyePlacement.m12))
		assertEquals(6 to 6, (eyeImage._filteredImage as CImageResource).let { resource -> resource.width to resource.height })
		val atlas = Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>().single()
		val entries = Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()
		assertEquals(4, entries.size, "eye, hair, brow, wing")
		val eyeEntry = assertNotNull(entries.firstOrNull { entry -> Cmo3Import.uuidOf(entry.modelImageGuid) == eyeTile.id.raw })
		assertEquals(2f to 8f, ((eyeEntry.materialLocalToAtlasTransform as GTransform2).position as GVector2).let { position -> position.x to position.y })
		val eyeAtlasToCanvas = eyeEntry.atlasLocalToCanvasTransform as CAffine
		assertEquals(10f, eyeAtlasToCanvas.m02, 1e-5f, "translate(12, 24) composed with the placement's inverse")
		assertEquals(16f, eyeAtlasToCanvas.m12, 1e-5f)
		val groups = Cmo3Import.elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()
		assertEquals(listOf("a.psd", "c.clip"), groups.map { group -> group.groupName }, "one group per file")

		// Every icon a minted layer or image references is an archive entry, on a path of its own.
		val images = Cmo3Import.elementsOf(textureManager._rawImages).filterIsInstance<LayeredImageWrapper>().map { wrapper -> wrapper.image as CLayeredImage }
		val iconPaths = ArrayList<String>()
		for (image in images) {
			for (walked in walkLayeredImage(image)) {
				iconPaths.add(assertNotNull(iconPath(walked.layer.icon16), "${walked.layer.name} has a 16px icon"))
				iconPaths.add(assertNotNull(iconPath(walked.layer.icon64), "${walked.layer.name} has a 64px icon"))
			}
		}
		for (modelImage in modelImages) {
			iconPaths.add(assertNotNull(iconPath(modelImage.icon16), "${modelImage.name} has an icon"))
		}
		assertEquals(iconPaths.size, iconPaths.toSet().size, "icon paths are unique: $iconPaths")
		for (path in iconPaths) {
			assertNotNull(reread.archive.byPath(path), "icon '$path' is embedded")
		}

		// The drawables: the created ones bind to their tiles, and every drawable re-imports verbatim.
		assertEquals(tileByKey.getValue("lyid:77").id, ingest.tileIdByDrawableId["Brow"], "the brow binds to its minted tile")
		assertEquals(tileByKey.getValue("name:Wing").id, ingest.tileIdByDrawableId["Wing"])
		assertEquals(eyeTile.id, ingest.tileIdByDrawableId["EyeL"], "the eye's drawable samples the root's model image")
		val reimported = Cmo3Import.fromModelSource(rereadRoot)
		for (drawable in edited.drawables) {
			val back = assertNotNull(reimported.drawables.firstOrNull { candidate -> candidate.id == drawable.id }, "${drawable.name} re-imports")
			assertContentEquals(drawable.mesh?.uvs, back.mesh?.uvs, "${drawable.name} uvs are verbatim")
		}
	}

	@Test
	fun aReloadedTileWithNoPixelsDeclinesTheWebAndLeavesTheLayersAlone() {
		val (retained, baseline) = retainedGraph()
		val eyeTile = baseline.atlas.tiles.first { tile -> tile.source?.layerKey == "lyid:1576" }
		val eyeReloaded = AtlasTile(reloadTileId(eyeTile.id, baseline.atlas.tiles.mapTo(HashSet()) { tile -> tile.id }), "Eye", 6, 6, placement = AtlasPlacement(0, 2f, 8f, 1f, 1f, 0f), source = eyeTile.source, replaces = eyeTile.id)
		val edited =
			baseline.copy(
				drawables = baseline.drawables.map { drawable -> if (drawable.id.raw == "EyeL") drawable.copy(atlasTileId = eyeReloaded.id) else drawable },
				atlas = baseline.atlas.copy(tiles = baseline.atlas.tiles.map { tile -> if (tile.id == eyeTile.id) eyeReloaded else tile }),
			)
		val entryCountBefore = retained.archive.entries.size

		val report = Cmo3Export.apply(edited, retained, recomposedPages = listOf(page), tileRasters = { null }, nowMillis = now)
		val reasons = report.notices.filterIsInstance<ExportNotice.UnsupportedChange>().map { notice -> notice.reason }
		assertTrue(ExportNoticeReason.AtlasPageNotRecomposed in reasons, "the whole web declined, so the moved placement is reported: ${report.notices}")
		assertTrue(ExportNoticeReason.AtlasTileMetadataNotReconcilable in reasons, "the size change is reported rather than half-written")
		assertEquals(entryCountBefore, retained.archive.entries.size, "nothing was embedded")
		val root = retained.root as CModelSource
		val art = assertNotNull(cmo3SourceArtOf(root, baseline.sources.single().id) { resource -> retained.extractLayerPng(resource) })
		assertContentEquals(gradient(4, 1).rgba, assertNotNull(art.layers.firstOrNull { layer -> layer.id.raw == "lyid:1576" }).raster.rgba, "the eye's layer still holds the imported pixels")
	}

	@Test
	fun anUneditedApplyLeavesTheGraphByteIdentical() {
		val (retained, baseline) = retainedGraph()
		val before = mainXmlOf(retained)
		val report = Cmo3Export.apply(baseline, retained, tileRasters = { gradient(4, 99) }, nowMillis = now)
		assertTrue(report.isEmpty, "no-change apply produced notices: ${report.notices}")
		assertContentEquals(before, mainXmlOf(retained), "main.xml is unchanged")
		assertNull(report.notices.firstOrNull(), "nothing reported")
	}
}