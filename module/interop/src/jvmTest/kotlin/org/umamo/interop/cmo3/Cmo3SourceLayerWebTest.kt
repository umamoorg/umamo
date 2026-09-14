package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.ExportNotice
import org.umamo.interop.cmo3TargetVersionNo
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
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The real-layer web an artwork-origin export writes: the routing of tiles into per-file inputs, the
 * layered image with its folders and layers, the model images and entries, the bindings, and the
 * whole thing read back through the codec, the ingest, and the layered-art reader.
 */
class Cmo3SourceLayerWebTest {
	private val pageSize = 16
	private val now = 1_700_000_000_000L
	private val sourceA = ArtSourceId("art-0")
	private val sourceB = ArtSourceId("art-1")

	private val tileEye = AtlasTile(AtlasTileId("art-0/lyid:1576"), "Eye", 4, 4, placement = AtlasPlacement(0, 2f, 2f, 1f, 1f, 0f), source = SourceLayerRef(sourceA, "lyid:1576", true))
	private val tileHair = AtlasTile(AtlasTileId("art-0/lyid:5"), "Hair", 4, 4, placement = AtlasPlacement(0, 8f, 2f, 1f, 1f, -90f), source = SourceLayerRef(sourceA, "lyid:5", true))
	private val tileGuide = AtlasTile(AtlasTileId("art-0/lyid:9"), "Guide", 4, 4, placement = null, source = SourceLayerRef(sourceA, "lyid:9", true))
	private val tileWing = AtlasTile(AtlasTileId("art-1/uuid-w"), "Wing", 4, 4, placement = AtlasPlacement(0, 2f, 8f, 1f, 1f, 0f), source = SourceLayerRef(sourceB, "uuid-w", true))

	private fun row(key: String, name: String, groupPath: String, left: Int, top: Int): ArtSourceLayer = ArtSourceLayer(key, name, groupPath, left, top, 4, 4, visible = true)

	private val sources =
		listOf(
			ArtSource(sourceA, "a.psd", "/art/a.psd", "psd", listOf(row("lyid:1576", "Eye", "Head/Eyes", 10, 20), row("lyid:5", "Hair", "", 30, 40), row("lyid:9", "Guide", "", 60, 60)), contentHash = null, lastModified = 123L),
			ArtSource(sourceB, "b.clip", null, "clip", listOf(row("uuid-w", "Wing", "", 50, 50))),
		)

	private fun gradient(seed: Int): RasterImage = RasterImage(4, 4, ByteArray(4 * 4 * 4) { index -> (index * 7 + seed).toByte() })

	private val rasters = mapOf(tileEye.id to gradient(1), tileHair.id to gradient(2), tileGuide.id to gradient(3), tileWing.id to gradient(4))

	private fun quad(left: Float, top: Float): DrawableMesh =
		DrawableMesh(
			positions = floatArrayOf(left, top, left + 4f, top, left + 4f, top + 4f, left, top + 4f),
			uvs = floatArrayOf(0.1f, 0.1f, 0.35f, 0.1f, 0.35f, 0.35f, 0.1f, 0.35f),
			indices = intArrayOf(0, 1, 2, 0, 2, 3),
		)

	private fun drawable(id: String, tile: AtlasTile, left: Float, top: Float): Drawable =
		Drawable(DrawableId(id), id, null, BlendMode.Normal, emptyList(), quad(left, top), null, atlasTileId = tile.id)

	private val drawables =
		listOf(
			drawable("EyeL", tileEye, 10f, 20f),
			drawable("EyeCopy", tileEye, 40f, 20f),
			drawable("Hair", tileHair, 30f, 40f),
			drawable("Guide", tileGuide, 60f, 60f),
			drawable("Wing", tileWing, 50f, 50f),
		)

	private val puppet =
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
			atlas = PuppetAtlas(pages = listOf(AtlasPage(pageSize, pageSize)), tiles = listOf(tileEye, tileHair, tileGuide, tileWing)),
			sources = sources,
		)

	private val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, ByteArray(pageSize * pageSize * 4) { 0x40 })), pageSize, pageSize)

	private fun names(entries: Any?): List<String> = Cmo3Import.elementsOf(entries).filterIsInstance<ACLayerEntry>().map { entry -> entry.name.orEmpty() }

	@Test
	fun theRoutingTakesEveryTileWithARasterAndARowInInventoryOrder() {
		val inputs = Cmo3SourceLayerWeb.inputsOf(puppet) { tileId -> rasters[tileId] }
		assertEquals(listOf("a.psd", "b.clip"), inputs.map { image -> image.name })
		val imageA = inputs[0]
		assertEquals("/art/a.psd", imageA.path)
		assertEquals(123L, imageA.lastModified)
		assertEquals(100 to 100, imageA.width to imageA.height, "the frame is the document canvas")
		assertEquals(listOf("Eye", "Hair", "Guide"), imageA.layers.map { layer -> layer.name }, "the file's layer order")
		assertEquals(listOf("EyeL", "EyeCopy"), imageA.layers[0].drawableIds, "both drawables over the tile")
		assertEquals("Head/Eyes", imageA.layers[0].groupPath)
		assertNull(imageA.layers[2].placement, "the guide is unpacked")
		assertNull(inputs[1].path)
		assertNull(inputs[1].lastModified)

		val withoutWing = Cmo3SourceLayerWeb.inputsOf(puppet) { tileId -> if (tileId == tileWing.id) null else rasters[tileId] }
		assertEquals(listOf("a.psd"), withoutWing.map { image -> image.name }, "a tile with no raster leaves its file out when it was the only one")
	}

	@Test
	fun theWebWritesRealLayersFoldersEntriesAndBindingsAndReadsBack() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Layer Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(page),
				listOf(emptyList()),
				nowMillis = now,
				fromSourceLayers = true,
				sourceImages = Cmo3SourceLayerWeb.inputsOf(puppet) { tileId -> rasters[tileId] },
			)
		assertEquals(0, chain.cropDrawableCount, "nothing took the crop path")
		val textureManager = skeleton.root.textureManager as CTextureManager
		val images = Cmo3Import.elementsOf(textureManager._rawImages).filterIsInstance<LayeredImageWrapper>().map { wrapper -> wrapper.image as CLayeredImage }
		assertEquals(listOf("a.psd", "b.clip"), images.map { image -> image.name }, "one layered image per file, no stand-in")
		val imageA = images[0]
		assertEquals("/art/a.psd", (imageA.psdFile as FileRef).textPath)
		assertEquals(123L, imageA.psdFileLastModified)
		assertEquals(100 to 100, imageA.width to imageA.height)
		assertEquals("b.clip", (images[1].psdFile as FileRef).textPath, "a record with no path writes its name")
		assertEquals(now, images[1].psdFileLastModified, "a record with no time writes now")

		// The folder tree: Head/Eyes holds the eye, the rest sits at the root, in file order.
		val rootA = imageA._rootLayer as CLayerGroup
		assertEquals(listOf("Head", "Hair", "Guide"), names(rootA._children))
		val head = Cmo3Import.elementsOf(rootA._children).filterIsInstance<CLayerGroup>().single()
		val eyes = Cmo3Import.elementsOf(head._children).filterIsInstance<CLayerGroup>().single()
		assertEquals("Eyes", eyes.name)
		assertEquals(listOf("Eye"), names(eyes._children))
		assertEquals(listOf("root", "Head", "Eyes", "Eye", "Hair", "Guide"), names(imageA.layerSet?.let { set -> (set as org.umamo.format.cmo3.model.gen.LayerSet)._layerEntryList }), "the flat set lists groups and layers in walk order")

		// Each layer's rect is the art frame at the row's origin; the identifier carries the lyid.
		val eye = Cmo3Import.elementsOf(eyes._children).filterIsInstance<CLayer>().single()
		val eyeBounds = eye.boundsOnImageDoc as CRect
		assertEquals(listOf(10, 20, 4, 4), listOf(eyeBounds.x, eyeBounds.y, eyeBounds.width, eyeBounds.height))
		val eyeIdentifier = eye.layerIdentifier as CLayerIdentifier
		assertEquals("00-00-06-28", eyeIdentifier.layerId, "1576 as four hex bytes")
		assertEquals(1576, eyeIdentifier.layerIdValue_testImpl)
		assertEquals("Eye", eyeIdentifier.layerName)
		val wing = Cmo3Import.elementsOf((images[1]._rootLayer as CLayerGroup)._children).filterIsInstance<CLayer>().single()
		val wingIdentifier = wing.layerIdentifier as CLayerIdentifier
		assertNull(wingIdentifier.layerId, "a uuid key has no Photoshop id to write")
		assertEquals(-1, wingIdentifier.layerIdValue_testImpl)

		// One model image per layer at a pure translation; one group per file.
		val groups = Cmo3Import.elementsOf(textureManager._modelImageGroups).filterIsInstance<CModelImageGroup>()
		assertEquals(listOf("a.psd", "b.clip"), groups.map { group -> group.groupName })
		val imagesA = Cmo3Import.elementsOf(groups[0]._modelImages).filterIsInstance<CModelImage>()
		assertEquals(listOf("Eye", "Hair", "Guide"), imagesA.map { modelImage -> modelImage.name as String })
		val eyePlacement = imagesA[0]._materialLocalToCanvasTransform as CAffine
		assertEquals(listOf(1f, 0f, 10f, 0f, 1f, 20f), listOf(eyePlacement.m00, eyePlacement.m01, eyePlacement.m02, eyePlacement.m10, eyePlacement.m11, eyePlacement.m12))

		// Entries for the placed tiles only, off their placements; the guide has none.
		val atlas = Cmo3Import.elementsOf(textureManager._textureAtlases).filterIsInstance<CTextureAtlas>().single()
		val entries = Cmo3Import.elementsOf(atlas.modelImages).filterIsInstance<ModelImageEntry>()
		assertEquals(3, entries.size, "eye, hair, wing")
		val eyeEntry = assertNotNull(entries.firstOrNull { entry -> entry.modelImageGuid == imagesA[0].guid }, "the eye has an entry")
		val eyePacking = eyeEntry.materialLocalToAtlasTransform as GTransform2
		assertEquals(2f to 2f, (eyePacking.position as GVector2).let { position -> position.x to position.y })
		assertEquals(0f, eyePacking.eulerAngle)
		val eyeAtlasToCanvas = eyeEntry.atlasLocalToCanvasTransform as CAffine
		assertEquals(8f, eyeAtlasToCanvas.m02, 1e-5f, "translate(10, 20) composed with the placement's inverse")
		assertEquals(18f, eyeAtlasToCanvas.m12, 1e-5f)
		val hairEntry = assertNotNull(entries.firstOrNull { entry -> entry.modelImageGuid == imagesA[1].guid }, "the hair has an entry")
		assertEquals(-90f, (hairEntry.materialLocalToAtlasTransform as GTransform2).eulerAngle, "the quarter turn rides the entry")
		assertTrue(entries.none { entry -> entry.modelImageGuid == imagesA[2].guid }, "an unpacked tile has no entry")

		// Bindings: both drawables over the eye share its model image and the entry's transform.
		val eyeL = chain.bindingByDrawableId.getValue("EyeL")
		val eyeCopy = chain.bindingByDrawableId.getValue("EyeCopy")
		assertEquals(imagesA[0].guid, eyeL.modelImageGuid, "the eye binding names the eye's model image")
		assertEquals(eyeL.modelImageGuid, eyeCopy.modelImageGuid, "the duplicate shares it")
		assertEquals(8f, eyeL.inputImageLocalToCanvasTransform.m02, 1e-5f)
		assertEquals(8f, eyeCopy.inputImageLocalToCanvasTransform.m02, 1e-5f)
		assertTrue("Hair" in chain.bindingByDrawableId && "Wing" in chain.bindingByDrawableId)
		assertNull(chain.bindingByDrawableId["Guide"], "an unpacked tile's drawable gets no binding")

		// The layer PNG is the tile's raster.
		val eyeResource = eye.imageResource as org.umamo.format.cmo3.model.custom.CImageResource
		val eyeEntryBytes = assertNotNull(chain.pngEntries.firstOrNull { entry -> entry.path == eyeResource.imageFileBuf?.archivePath }, "the eye layer's PNG is an entry").pngBytes
		assertContentEquals(gradient(1).rgba, PngCodec.read(eyeEntryBytes).rgba)

		// Through the codec, the reconcile, and back: the ingest reads the same web, the layered-art
		// reader the same pixels, the import the same uvs.
		val model =
			Cmo3.read(
				Cmo3FreshFile.assemble(
					skeleton.root,
					skeleton.iconEntries.map { icon -> Cmo3FreshFile.PngEntry(icon.path, icon.pngBytes) } + chain.pngEntries,
					obfuscateKey = 0x1234ABCD,
				),
			)
		val report = Cmo3Export.apply(puppet, model, chain.bindingByDrawableId)
		assertTrue(report.notices.all { notice -> notice is ExportNotice.UnsupportedChange && notice.subject == "Guide" }, "only the unpacked guide is reported: ${report.notices}")
		val reread = Cmo3.read(Cmo3.write(model))
		val rereadRoot = reread.root as CModelSource
		val ingest = cmo3AtlasIngest(rereadRoot)
		val rereadA = assertNotNull(ingest.sources.firstOrNull { source -> source.name == "a.psd" }, "a.psd reads back: ${ingest.sources.map { source -> source.name }}")
		assertEquals("/art/a.psd", rereadA.path)
		assertEquals(123L, rereadA.lastModified)
		assertEquals(listOf("lyid:1576", "lyid:5", "lyid:9"), rereadA.layers.map { layer -> layer.key })
		assertEquals(listOf("Head/Eyes", "", ""), rereadA.layers.map { layer -> layer.groupPath })
		assertEquals(listOf(10, 20, 4, 4), rereadA.layers[0].let { layer -> listOf(layer.left, layer.top, layer.width, layer.height) })
		val rereadB = assertNotNull(ingest.sources.firstOrNull { source -> source.name == "b.clip" }, "b.clip reads back")
		assertEquals(listOf("name:Wing"), rereadB.layers.map { layer -> layer.key }, "a uuid key reopens as a name key")
		val tileByKey = ingest.atlas.tiles.associateBy { tile -> tile.source?.layerKey }
		assertEquals(AtlasPlacement(0, 2f, 2f, 1f, 1f, 0f), tileByKey.getValue("lyid:1576").placement)
		assertEquals(-90f, assertNotNull(tileByKey.getValue("lyid:5").placement).rotationDegrees)
		assertNull(tileByKey.getValue("lyid:9").placement)
		assertEquals(AtlasPlacement(0, 2f, 8f, 1f, 1f, 0f), tileByKey.getValue("name:Wing").placement)
		assertEquals(4 to 4, tileByKey.getValue("lyid:1576").let { tile -> tile.width to tile.height })
		assertEquals(ingest.tileIdByDrawableId["EyeL"], ingest.tileIdByDrawableId["EyeCopy"], "the duplicates share their tile")
		val rereadArt = assertNotNull(cmo3SourceArtOf(rereadRoot, rereadA.id) { resource -> reread.extractLayerPng(resource) })
		val rereadEye = assertNotNull(rereadArt.layers.firstOrNull { layer -> layer.id.raw == "lyid:1576" }, "the eye reads back as a source layer: ${rereadArt.layers.map { layer -> layer.id.raw }}")
		assertContentEquals(gradient(1).rgba, rereadEye.raster.rgba, "the eye's pixels read back")
		val reimported = Cmo3Import.fromModelSource(rereadRoot)
		for (drawable in puppet.drawables) {
			if (drawable.id.raw == "Guide") {
				// An unpacked tile's drawable has no texture to bind and is reported rather than written,
				// as in the crop path; the unpacked-drawable input shape is the follow-up.
				assertNull(reimported.drawables.firstOrNull { candidate -> candidate.id == drawable.id }, "the unpacked guide is reported, not written")
				continue
			}
			val back = assertNotNull(reimported.drawables.firstOrNull { candidate -> candidate.id == drawable.id }, "${drawable.name} re-imports")
			assertContentEquals(drawable.mesh?.uvs, back.mesh?.uvs, "${drawable.name} uvs are verbatim")
		}
	}
}