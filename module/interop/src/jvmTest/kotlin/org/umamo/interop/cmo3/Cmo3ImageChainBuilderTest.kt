package org.umamo.interop.cmo3

import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.cmo3.model.gen.CArtMeshSource
import org.umamo.format.cmo3.model.gen.CDrawableSourceSet
import org.umamo.format.cmo3.model.gen.GTexture2D
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.interop.cmo3TargetVersionNo
import org.umamo.interop.diffPuppetModels
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RuntimeTarget
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Gates the fresh-graph image chain: atlas pages embed as by-name CAFF entries the read-back
 * model resolves (the extractPuppetTextures contract - texture -> srcImageResource -> archive
 * path), and a drawable bound through Cmo3DrawableTextureBinding round-trips with VERBATIM UVs
 * (the atlas-region input keeps the importer on the packed, no-remap path).
 */
class Cmo3ImageChainBuilderTest {
	private fun solidPage(size: Int, value: Byte): Cmo3ImageChainBuilder.AtlasPage {
		val rgba = ByteArray(size * size * 4) { value }
		return Cmo3ImageChainBuilder.AtlasPage(PngCodec.write(RasterImage(size, size, rgba)), size, size)
	}

	@Test
	fun pageChainEmbedsAndResolvesPagesByName() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Chain Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val pages = listOf(solidPage(4, 0x40), solidPage(8, 0x7F))
		val chain =
			Cmo3ImageChainBuilder.populate(skeleton.root, pages, listOf(emptyList(), emptyList()), nowMillis = 1_700_000_000_000L)
		val bytes =
			Cmo3FreshFile.assemble(
				skeleton.root,
				skeleton.iconEntries.map { icon -> Cmo3FreshFile.PngEntry(icon.path, icon.pngBytes) } + chain.pngEntries,
				obfuscateKey = 0x1234ABCD,
			)
		val model = Cmo3.read(bytes)
		val resources = model.imageResources()
		assertEquals(2, resources.size, "one CImageResource per page")
		// The reachability walk's order is unspecified; the by-name path attribute is the contract.
		// pngEntries also carries the per-page icon placeholders (image_N.png), which have no
		// CImageResource - only the page buffers do.
		val resourcePaths = resources.map { resource -> resource.imageFileBuf?.archivePath }.toSet()
		val pagePaths = chain.pngEntries.map { entry -> entry.path }.filter { path -> path.startsWith("imageFileBuf") }.toSet()
		assertEquals(pagePaths, resourcePaths, "one resource per page path")
		val entriesByPath = chain.pngEntries.associateBy { entry -> entry.path }
		for ((pageIndex, page) in pages.withIndex()) {
			val pagePath = if (pageIndex == 0) "imageFileBuf.png" else "imageFileBuf_${pageIndex - 1}.png"
			assertContentEquals(page.pngBytes, entriesByPath.getValue(pagePath).pngBytes, "page $pageIndex entry bytes")
			val resource = resources.single { candidate -> candidate.imageFileBuf?.archivePath == pagePath }
			assertContentEquals(page.pngBytes, model.extractLayerPng(resource), "page $pageIndex bytes resolve by name")
		}
	}

	@Test
	fun boundDrawableRoundTripsWithVerbatimUvs() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Bound Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val uvs = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.5f, 0.8f)
		val positions = floatArrayOf(10f, 10f, 90f, 10f, 50f, 90f)
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(solidPage(4, 0x11)),
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("BoundDrawable", uvs, positions, intArrayOf(0, 1, 2)))),
				nowMillis = 1_700_000_000_000L,
			)
		val model =
			Cmo3.read(
				Cmo3FreshFile.assemble(
					skeleton.root,
					skeleton.iconEntries.map { icon -> Cmo3FreshFile.PngEntry(icon.path, icon.pngBytes) } + chain.pngEntries,
					obfuscateKey = 0x1234ABCD,
				),
			)
		val drawableId = DrawableId("BoundDrawable")
		val puppet =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables =
					listOf(
						Drawable(
							id = drawableId,
							name = "Bound Drawable",
							parentDeformerId = null,
							blendMode = BlendMode.Normal,
							maskedBy = emptyList(),
							mesh =
								DrawableMesh(
									positions = positions,
									uvs = uvs,
									indices = intArrayOf(0, 1, 2),
								),
							geometryGrid = null,
						),
					),
				rootChildren = listOf(OrgChild.Drawable(drawableId)),
				rootPartId = null,
				canvasWidth = 100f,
				canvasHeight = 100f,
				worldOriginX = 50f,
				worldOriginY = -50f,
				runtimeTarget = RuntimeTarget.Cubism53,
			)
		val report = Cmo3Export.apply(puppet, model, mapOf(drawableId.raw to chain.bindingByDrawableId.getValue(drawableId.raw)))
		assertTrue(report.isEmpty, "expected no notices, got ${report.notices}")

		val reimportedSource = Cmo3.read(Cmo3.write(model)).root as? CModelSource ?: error("re-read root is not a CModelSource")
		val reimported = Cmo3Import.fromModelSource(reimportedSource)
		val residual = diffPuppetModels(reimported, puppet)
		assertTrue(residual.isEmpty, "bound drawable lost through export/import: $residual")
		assertContentEquals(uvs, reimported.drawables.single().mesh?.uvs, "UVs are verbatim (atlas frame)")

		// The texture web the renderer's extraction walks: drawable -> GTexture2D -> page resource.
		val drawableSource =
			(Cmo3Import.elementsOf((reimportedSource.drawableSourceSet as CDrawableSourceSet)._sources))
				.filterIsInstance<CArtMeshSource>()
				.single()
		val texture = drawableSource.texture as? GTexture2D ?: error("bound drawable has no GTexture2D")
		assertTrue(texture.srcImageResource != null, "texture reaches the page resource")
	}

	@Test
	fun cropClearsPagePixelsTheMeshDoesNotCover() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Mask Test", 32, 32, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// A fully opaque page: every pixel the crop keeps or clears is distinguishable from padding.
		val pageSize = 32
		val pageRgba = ByteArray(pageSize * pageSize * 4) { 0x7F }
		val page = Cmo3ImageChainBuilder.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		// A right triangle over page (4,4)-(28,4)-(4,28), laid down unrotated so canvas equals page:
		// its bounding box is the full 24-square rect, half of which the mesh never samples.
		val uvs = floatArrayOf(4f / 32f, 4f / 32f, 28f / 32f, 4f / 32f, 4f / 32f, 28f / 32f)
		val positions = FloatArray(uvs.size) { index -> uvs[index] * pageSize }
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(page),
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("MaskDrawable", uvs, positions, intArrayOf(0, 1, 2)))),
				nowMillis = 1_700_000_000_000L,
			)
		val crop = PngCodec.read(chain.pngEntries.single { entry -> entry.path == "imageFileBuf_0.png" }.pngBytes)
		// The rect itself is untouched - masking must not move the crop, or every placement
		// transform computed against it would be wrong.
		assertEquals(24, crop.width, "crop width is still the mesh bounding box")
		assertEquals(24, crop.height, "crop height is still the mesh bounding box")
		// Well inside the triangle.
		assertEquals(0x7F.toByte(), crop.rgba[(2 * 24 + 2) * 4 + 3], "a covered pixel keeps the page's alpha")
		// Well past the hypotenuse (x + y = 24 in crop-local pixels), far outside the bleed margin.
		for (channel in 0 until 4) {
			assertEquals(0, crop.rgba[(22 * 24 + 22) * 4 + channel], "an uncovered pixel is cleared, channel $channel")
		}
	}

	@Test
	fun patchWebCarriesPlacementAndCropBytes() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Patch Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// A gradient page so the crop subregion is verifiable byte-for-byte.
		val pageSize = 8
		val pageRgba = ByteArray(pageSize * pageSize * 4) { index -> index.toByte() }
		val page = Cmo3ImageChainBuilder.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		// uv bbox [0.25, 0.5] x [0.5, 1.0] -> page x 2..4, y 4..8; positions via the exact translation
		// canvas = (pageX + 3, pageY + 10), so the packing is unit-scale and the resample is a copy.
		val uvs = floatArrayOf(0.25f, 0.5f, 0.5f, 0.5f, 0.25f, 1.0f, 0.5f, 1.0f)
		val positions =
			FloatArray(uvs.size) { index ->
				val vertexIndex = index / 2
				if (index % 2 == 0) {
					uvs[2 * vertexIndex] * pageSize + 3f
				} else {
					uvs[2 * vertexIndex + 1] * pageSize + 10f
				}
			}
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(page),
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("PatchDrawable", uvs, positions, intArrayOf(0, 1, 2, 1, 2, 3)))),
				nowMillis = 1_700_000_000_000L,
			)
		val binding = chain.bindingByDrawableId.getValue("PatchDrawable")
		assertTrue(binding.modelImageGuid != null, "drawable gets its own model image")
		assertEquals(1f, binding.inputImageLocalToCanvasTransform.m00, 1e-4f, "fitted x scale")
		assertEquals(1f, binding.inputImageLocalToCanvasTransform.m11, 1e-4f, "fitted y scale")
		assertEquals(3f, binding.inputImageLocalToCanvasTransform.m02, 1e-3f, "fitted x offset")
		assertEquals(10f, binding.inputImageLocalToCanvasTransform.m12, 1e-3f, "fitted y offset")

		val textureManager = skeleton.root.textureManager as org.umamo.format.cmo3.model.gen.CTextureManager
		val atlas =
			Cmo3Import.elementsOf(textureManager._textureAtlases)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.CTextureAtlas>()
				.single()
		val entry =
			Cmo3Import.elementsOf(atlas.modelImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.ModelImageEntry>()
				.single()
		assertEquals(binding.modelImageGuid, entry.modelImageGuid, "entry joins the drawable's model image")
		// The packing takes UPRIGHT layer pixels into the page: the mesh's canvas bbox is x 5..7,
		// y 14..18, and undoing the fit's translation lands it back on the page at (2, 4).
		val packing = entry.materialLocalToAtlasTransform as org.umamo.format.cmo3.model.gen.GTransform2
		val packingPosition = packing.position as org.umamo.format.cmo3.model.type.GVector2
		val packingScale = packing.scale as org.umamo.format.cmo3.model.type.GVector2
		assertEquals(2f, packingPosition.x, 1e-4f, "packing origin x on the page")
		assertEquals(4f, packingPosition.y, 1e-4f, "packing origin y on the page")
		assertEquals(1f, packingScale.x, 1e-4f, "packing x scale")
		assertEquals(1f, packingScale.y, 1e-4f, "packing y scale")
		assertEquals(0f, packing.eulerAngle, 1e-4f, "packing rotation")
		val entryPlacement = entry.atlasLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(1f, entryPlacement.m00, 1e-4f, "entry placement matches the fit")
		assertEquals(10f, entryPlacement.m12, 1e-3f, "entry placement matches the fit")

		val modelImage =
			Cmo3Import.elementsOf(textureManager._modelImageGroups)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.CModelImageGroup>()
				.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<org.umamo.format.cmo3.model.custom.CModelImage>() }
				.single()
		// THE format invariant: a model image sits on the canvas by PURE TRANSLATION (its layer
		// origin), and the entry's packing is exactly what atlasLocalToCanvasTransform undoes.  All
		// 1046 corpus model images satisfy this, rotated and scaled packings included.
		val materialToCanvas = modelImage._materialLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(1f, materialToCanvas.m00, 1e-4f, "model image placement is a pure translation")
		assertEquals(0f, materialToCanvas.m01, 1e-4f, "model image placement is a pure translation")
		assertEquals(0f, materialToCanvas.m10, 1e-4f, "model image placement is a pure translation")
		assertEquals(1f, materialToCanvas.m11, 1e-4f, "model image placement is a pure translation")
		assertEquals(5f, materialToCanvas.m02, 1e-3f, "layer canvas origin x")
		assertEquals(14f, materialToCanvas.m12, 1e-3f, "layer canvas origin y")

		// The synthetic source doc is the PAGE's frame (official docs carry the source image's own
		// size, not the canvas), and the layer's boundsOnImageDoc is its rect within that doc.
		val layeredImage =
			Cmo3Import.elementsOf(textureManager._rawImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.LayeredImageWrapper>()
				.single()
				.image as org.umamo.format.cmo3.model.gen.CLayeredImage
		assertEquals(pageSize, layeredImage.width, "doc width is the page")
		assertEquals(pageSize, layeredImage.height, "doc height is the page")
		val patchLayer =
			Cmo3Import.elementsOf((layeredImage._rootLayer as org.umamo.format.cmo3.model.gen.CLayerGroup)._children)
				.filterIsInstance<org.umamo.format.cmo3.model.custom.CLayer>()
				.single()
		// The layer rect: origin repeating the model image's placement, size equal to the layer
		// image's own dimensions - the shape all 905 corpus layers have.  A zero rect describes a
		// zero-size layer and the editor's composite over it comes out empty.
		val bounds = patchLayer.boundsOnImageDoc as org.umamo.format.cmo3.model.type.CRect
		assertEquals(5, bounds.x, "boundsOnImageDoc origin repeats the layer's canvas origin")
		assertEquals(14, bounds.y, "boundsOnImageDoc origin repeats the layer's canvas origin")
		assertEquals(2, bounds.width, "boundsOnImageDoc width equals the layer image width")
		assertEquals(4, bounds.height, "boundsOnImageDoc height equals the layer image height")

		// A unit-scale axis-aligned packing resamples to exact page pixel centers, so the crop is
		// still the page subregion byte-for-byte (x 2..4, y 4..8).
		val cropEntry = chain.pngEntries.single { pngEntry -> pngEntry.path == "imageFileBuf_0.png" }
		val crop = PngCodec.read(cropEntry.pngBytes)
		assertEquals(2, crop.width, "crop width is the mesh's canvas bbox")
		assertEquals(4, crop.height, "crop height is the mesh's canvas bbox")
		for (rowIndex in 0 until crop.height) {
			for (columnIndex in 0 until crop.width) {
				val cropOffset = (rowIndex * crop.width + columnIndex) * 4
				val pageOffset = ((4 + rowIndex) * pageSize + (2 + columnIndex)) * 4
				for (channel in 0 until 4) {
					assertEquals(
						pageRgba[pageOffset + channel],
						crop.rgba[cropOffset + channel],
						"crop pixel ($columnIndex, $rowIndex) channel $channel",
					)
				}
			}
		}
	}

	@Test
	fun aQuarterTurnPackingProducesUprightArtAndCarriesTheRotationOnTheEntry() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Rotated Test", 200, 200, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		val pageSize = 64
		val pageRgba = ByteArray(pageSize * pageSize * 4) { index -> (index / 4).toByte() }
		val page = Cmo3ImageChainBuilder.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		// The packer laid this patch down a quarter turn over: it spans 32x16 on the page while the
		// drawable is 16 wide and 32 tall on the canvas.  canvas = (pageY + 100, -pageX + 150).
		val corners = arrayOf(intArrayOf(8, 40), intArrayOf(40, 40), intArrayOf(8, 56), intArrayOf(40, 56))
		val uvs = FloatArray(8)
		val positions = FloatArray(8)
		for (cornerIndex in corners.indices) {
			val pageX = corners[cornerIndex][0]
			val pageY = corners[cornerIndex][1]
			uvs[2 * cornerIndex] = pageX.toFloat() / pageSize
			uvs[2 * cornerIndex + 1] = pageY.toFloat() / pageSize
			positions[2 * cornerIndex] = (pageY + 100).toFloat()
			positions[2 * cornerIndex + 1] = (-pageX + 150).toFloat()
		}
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(page),
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("Rotated", uvs, positions, intArrayOf(0, 1, 2, 1, 2, 3)))),
				nowMillis = 1_700_000_000_000L,
			)
		val textureManager = skeleton.root.textureManager as org.umamo.format.cmo3.model.gen.CTextureManager
		val modelImage =
			Cmo3Import.elementsOf(textureManager._modelImageGroups)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.CModelImageGroup>()
				.flatMap { group -> Cmo3Import.elementsOf(group._modelImages).filterIsInstance<org.umamo.format.cmo3.model.custom.CModelImage>() }
				.single()
		// The crop is the drawable's CANVAS extent (16x32 upright), never the atlas footprint (32x16).
		val crop = PngCodec.read(chain.pngEntries.single { entry -> entry.path == "imageFileBuf_0.png" }.pngBytes)
		assertEquals(16, crop.width, "upright art width")
		assertEquals(32, crop.height, "upright art height")
		// The rotation is on the entry, and the model image still places by pure translation.
		val entry =
			Cmo3Import.elementsOf(
				Cmo3Import.elementsOf(textureManager._textureAtlases)
					.filterIsInstance<org.umamo.format.cmo3.model.gen.CTextureAtlas>()
					.single()
					.modelImages,
			).filterIsInstance<org.umamo.format.cmo3.model.gen.ModelImageEntry>().single()
		val packing = entry.materialLocalToAtlasTransform as org.umamo.format.cmo3.model.gen.GTransform2
		assertEquals(90f, kotlin.math.abs(packing.eulerAngle), 1e-3f, "the quarter turn lands on eulerAngle")
		val materialToCanvas = modelImage._materialLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(1f, materialToCanvas.m00, 1e-4f, "model image placement stays a pure translation")
		assertEquals(0f, materialToCanvas.m01, 1e-4f, "model image placement stays a pure translation")
		assertEquals(0f, materialToCanvas.m10, 1e-4f, "model image placement stays a pure translation")
		assertEquals(1f, materialToCanvas.m11, 1e-4f, "model image placement stays a pure translation")
		// atlasLocalToCanvas . packing == materialLocalToCanvas, the invariant every corpus file holds.
		val atlasToCanvas = entry.atlasLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		val packingPosition = packing.position as org.umamo.format.cmo3.model.type.GVector2
		assertEquals(
			materialToCanvas.m02.toDouble(),
			(atlasToCanvas.m00 * packingPosition.x + atlasToCanvas.m01 * packingPosition.y + atlasToCanvas.m02).toDouble(),
			1e-2,
			"composition reproduces the layer canvas origin x",
		)
		assertEquals(
			materialToCanvas.m12.toDouble(),
			(atlasToCanvas.m10 * packingPosition.x + atlasToCanvas.m11 * packingPosition.y + atlasToCanvas.m12).toDouble(),
			1e-2,
			"composition reproduces the layer canvas origin y",
		)
	}
}
