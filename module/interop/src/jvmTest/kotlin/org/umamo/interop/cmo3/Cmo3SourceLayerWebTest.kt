package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CLayer
import org.umamo.format.cmo3.model.custom.CModelImage
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.ACLayerEntry
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.CLayerGroup
import org.umamo.format.cmo3.model.gen.CLayerIdentifier
import org.umamo.format.cmo3.model.gen.CLayeredImage
import org.umamo.format.cmo3.model.gen.CModelImageGroup
import org.umamo.format.cmo3.model.gen.CTextureAtlas
import org.umamo.format.cmo3.model.gen.CTextureInputExtension
import org.umamo.format.cmo3.model.gen.CTextureInput_ModelImage
import org.umamo.format.cmo3.model.gen.CTextureManager
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.cmo3.model.gen.GTransform2
import org.umamo.format.cmo3.model.gen.LayeredImageWrapper
import org.umamo.format.cmo3.model.gen.ModelImageEntry
import org.umamo.format.cmo3.model.gen.TextureState
import org.umamo.format.cmo3.model.type.CAffine
import org.umamo.format.cmo3.model.type.CRect
import org.umamo.format.cmo3.model.type.FileRef
import org.umamo.format.cmo3.model.type.GVector2
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.fittedInto
import org.umamo.interop.ExportNotice
import org.umamo.interop.ExportNoticeReason
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
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
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
			worldOriginZ = -50f,
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

	/**
	 * A tile bound to a file the document does not list, or to a layer its file never inventoried, routes as a
	 * flat image of its own that carries the binding it stands in for, and the fresh conversion reports that
	 * binding; a tile bound to nothing routes the same way and carries none.
	 */
	@Test
	fun aBindingNoListedRowResolvesRoutesAsAFlatImageAndIsReported() {
		val unlistedSource = ArtSourceId("art-9")
		val tileUnlisted = AtlasTile(AtlasTileId("art-9/lyid:7"), "Stray", 4, 4, source = SourceLayerRef(unlistedSource, "lyid:7", true))
		val tileLostKey = AtlasTile(AtlasTileId("art-0/lyid:99"), "Lost", 4, 4, source = SourceLayerRef(sourceA, "lyid:99", true))
		val tileUnbound = AtlasTile(AtlasTileId("hit"), "HitArea", 4, 4)
		val widened = puppet.copy(atlas = puppet.atlas.copy(tiles = puppet.atlas.tiles + listOf(tileUnlisted, tileLostKey, tileUnbound)))
		val widenedRasters = rasters + mapOf(tileUnlisted.id to gradient(5), tileLostKey.id to gradient(6), tileUnbound.id to gradient(7))

		val inputs = Cmo3SourceLayerWeb.inputsOf(widened) { tileId -> widenedRasters[tileId] }
		assertEquals(listOf("a.psd", "b.clip", "Stray", "Lost", "HitArea"), inputs.map { image -> image.name }, "the files, then one flat image per tile no row resolves")
		val byName = inputs.associateBy { image -> image.name }
		assertEquals(SourceLayerRef(unlistedSource, "lyid:7", true), byName.getValue("Stray").unresolvedBinding, "the unlisted file's binding rides its flat image")
		assertEquals(SourceLayerRef(sourceA, "lyid:99", true), byName.getValue("Lost").unresolvedBinding, "so does a key the listed file never inventoried")
		assertNull(byName.getValue("HitArea").unresolvedBinding, "a tile bound to nothing stood in for no binding")
		assertNull(byName.getValue("a.psd").unresolvedBinding)
		assertEquals(listOf("name:Stray", "name:Lost", "name:HitArea"), listOf("Stray", "Lost", "HitArea").map { name -> byName.getValue(name).layers.single().layerKey })

		val result =
			Cmo3Conversion.freshCmo3(
				puppet = widened,
				pages = listOf(page),
				pageIndexByDrawableId = widened.drawables.associate { drawable -> drawable.id.raw to 0 },
				modelName = "Unresolved",
				nowMillis = now,
				obfuscateKey = 0x1234ABCD,
				tileRasters = { tileId -> widenedRasters[tileId] },
			)
		val reported = result.report.notices.filterIsInstance<ExportNotice.UnsupportedChange>().filter { notice -> notice.reason is ExportNoticeReason.SourceLayerBindingNotInExport }.map { notice -> notice.subject to notice.reason }
		assertEquals(
			listOf(
				"Stray" to ExportNoticeReason.SourceLayerBindingNotInExport("art-9", "lyid:7"),
				"Lost" to ExportNoticeReason.SourceLayerBindingNotInExport("a.psd", "lyid:99"),
			),
			reported,
			"each binding that did not cross is reported once, by the file's name when the document lists it: ${result.report.notices}",
		)
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
		assertEquals("", (images[1].psdFile as FileRef).textPath, "a record with no path writes none, not its name")
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
		assertEquals(8f, eyeL.inputImageLocalToCanvasTransform!!.m02, 1e-5f)
		assertEquals(8f, eyeCopy.inputImageLocalToCanvasTransform!!.m02, 1e-5f)
		assertTrue("Hair" in chain.bindingByDrawableId && "Wing" in chain.bindingByDrawableId)
		// The unpacked guide binds its model image alone, through a texture over the image's raster that
		// carries the raster's padding scale - the corpus shape of a drawable never packed.
		val guide = chain.bindingByDrawableId.getValue("Guide")
		assertTrue(guide.isUnpacked, "an unpacked tile's drawable has no atlas region")
		assertEquals(imagesA[2].guid, guide.modelImageGuid, "it binds the guide's model image")
		assertSame(imagesA[2]._filteredImage, guide.texture.srcImageResource, "its texture samples that image's raster")
		val guideScale = guide.texture.transformImageResource01toLogical01 as CAffine
		assertEquals(listOf(4f / 64f, 0f, 0f, 0f, 4f / 64f, 0f), listOf(guideScale.m00, guideScale.m01, guideScale.m02, guideScale.m10, guideScale.m11, guideScale.m12), "the 4px raster over its 64px padding")
		assertEquals(listOf(guide.texture), chain.rasterTextures, "one raster texture, for the one unpacked tile")

		// The layer PNG is the tile's raster.
		val eyeResource = eye.imageResource as org.umamo.format.cmo3.model.custom.CImageResource
		val eyeEntryBytes = assertNotNull(chain.pngEntries.firstOrNull { entry -> entry.path == eyeResource.imageFileBuf?.archivePath }, "the eye layer's PNG is an entry").pngBytes
		assertContentEquals(gradient(1).rgba, PngCodec.read(eyeEntryBytes).rgba)

		// The icons: the layer's and the model image's fit the whole raster; each drawable's fits the
		// patch its mesh covers (the whole tile here), one entry per drawable.
		fun iconPixels(icon: Any?): RasterImage {
			val path = assertNotNull(Cmo3Icons.archivePathOf(icon), "the icon names an entry")
			return PngCodec.read(assertNotNull(chain.pngEntries.firstOrNull { entry -> entry.path == path }, "icon entry '$path' was collected").pngBytes)
		}
		assertContentEquals(gradient(1).fittedInto(64).rgba, iconPixels(eye.icon64).rgba, "the eye's 64px icon is the fitted raster")
		assertContentEquals(gradient(1).fittedInto(16).rgba, iconPixels(eye.icon16).rgba, "and its 16px icon")
		assertContentEquals(gradient(1).fittedInto(16).rgba, iconPixels(imagesA[0].icon16).rgba, "the model image's icon too")
		assertContentEquals(gradient(1).fittedInto(32).rgba, iconPixels(eyeL.icon32).rgba, "the drawable's icon shows its patch")
		assertContentEquals(gradient(1).fittedInto(16).rgba, iconPixels(eyeL.icon16).rgba)
		assertNotEquals(Cmo3Icons.archivePathOf(eyeL.icon32), Cmo3Icons.archivePathOf(eyeCopy.icon32), "the duplicate has an icon of its own")

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
		// The conversion's own step: the textures the chain built re-point at the read-back graph's resources.
		Cmo3Conversion.rebindImageResources(chain, model)
		val report = Cmo3Export.apply(puppet, model, chain.bindingByDrawableId)
		assertTrue(report.notices.isEmpty(), "every drawable is written, the unpacked guide included: ${report.notices}")
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
		// Every written drawable carries its icons through the codec, at distinct entries.
		val rereadMeshes = Cmo3Import.elementsOf((rereadRoot.drawableSourceSet as CDrawableSourceSet)._sources).filterIsInstance<CArtMeshSource>()
		val rereadIconPaths = ArrayList<String>()
		for (mesh in rereadMeshes) {
			for ((icon, size) in listOf(mesh.icon32 to 32, mesh.icon16 to 16)) {
				val path = assertNotNull(Cmo3Icons.archivePathOf(icon), "${Cmo3Import.idStrOf(mesh.id)} has a ${size}px icon")
				val decoded = PngCodec.read(assertNotNull(reread.archive.byPath(path), "icon '$path' is embedded").content)
				assertEquals(size to size, decoded.width to decoded.height)
				rereadIconPaths.add(path)
			}
		}
		assertEquals(rereadIconPaths.size, rereadIconPaths.toSet().size, "one icon entry per drawable and size")
		val reimported = Cmo3Import.fromModelSource(rereadRoot)
		for (drawable in puppet.drawables) {
			val back = assertNotNull(reimported.drawables.firstOrNull { candidate -> candidate.id == drawable.id }, "${drawable.name} re-imports")
			if (drawable.id.raw == "Guide") {
				// Through the cache frame and back: within a few ulps, not bit for bit.
				val expected = assertNotNull(drawable.mesh).uvs
				val actual = assertNotNull(back.mesh).uvs
				assertEquals(expected.size, actual.size)
				for (componentIndex in expected.indices) {
					assertEquals(expected[componentIndex], actual[componentIndex], 1e-6f, "Guide uv $componentIndex")
				}
				continue
			}
			assertContentEquals(drawable.mesh?.uvs, back.mesh?.uvs, "${drawable.name} uvs are verbatim")
		}
		// The written guide has the corpus shape, samples the very raster its model image holds, and shows from it.
		val guideMesh = rereadMeshes.single { mesh -> Cmo3Import.idStrOf(mesh.id) == "Guide" }
		assertEquals(TextureState.MODEL_IMAGE, guideMesh.textureState)
		val guideExtension = Cmo3Import.elementsOf(guideMesh._extensions).filterIsInstance<CTextureInputExtension>().single()
		val guideInput = Cmo3Import.elementsOf(guideExtension._textureInputs).single() as CTextureInput_ModelImage
		assertSame(guideInput, guideExtension.currentTextureInputData, "the model image is current")
		val rereadGuideImage =
			Cmo3Import.elementsOf((rereadRoot.textureManager as CTextureManager)._modelImageGroups)
				.filterIsInstance<CModelImageGroup>()
				.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<CModelImage>() }
				.single { modelImage -> Cmo3Import.uuidOf(modelImage.guid) == Cmo3Import.uuidOf(guideInput._modelImageGuid) }
		assertSame(rereadGuideImage._filteredImage, (guideMesh.texture as GTexture2D).srcImageResource, "one resource, shared by reference as the editor writes it")
		assertEquals(Cmo3StoredFrame.Cache, Cmo3TextureFrames(rereadRoot).storedFrameOf(guideMesh), "its coordinates are in the cache frame")
		assertNull(tileByKey.getValue("lyid:9").placement, "and its tile is still unplaced")
		val shownPages = cmo3AtlasPages(rereadRoot, ingest) { resource -> reread.extractLayerPng(resource) }
		val guidePage = shownPages.pageBytes[shownPages.atlasIndexByDrawableId.getValue("Guide")]
		assertContentEquals(gradient(3).rgba, PngCodec.read(guidePage).rgba, "the guide is shown from its own raster")
	}
}