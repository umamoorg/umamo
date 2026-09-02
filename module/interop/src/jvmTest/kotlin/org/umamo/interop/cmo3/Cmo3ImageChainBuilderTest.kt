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
	private fun solidPage(size: Int, value: Byte): Cmo3Conversion.AtlasPage {
		val rgba = ByteArray(size * size * 4) { value }
		return Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(size, size, rgba)), size, size)
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
		val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		// A right triangle over page (4,4)-(28,4)-(4,28): its uv bounding box is the full 24-square
		// patch, so half of that rect is page pixels the mesh never samples.
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
		// The page is fully opaque and the triangle's legs run along the rect's edges, so the
		// opaque-bbox trim is a no-op here and the rect stays the uv bounding box.
		assertEquals(24, crop.width, "crop width is still the uv bounding box")
		assertEquals(24, crop.height, "crop height is still the uv bounding box")
		// Well inside the triangle.
		assertEquals(0x7F.toByte(), crop.rgba[(2 * 24 + 2) * 4 + 3], "a covered pixel keeps the page's alpha")
		// Well past the hypotenuse (x + y = 24 in crop-local pixels), far outside the bleed margin.
		for (channel in 0 until 4) {
			assertEquals(0, crop.rgba[(22 * 24 + 22) * 4 + channel], "an uncovered pixel is cleared, channel $channel")
		}
	}

	@Test
	fun cropTrimsToOpaquePixelBounds() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Trim Test", 32, 32, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// A transparent page with one opaque block at x 10..14, y 12..18: the mesh's uv bbox is
		// much larger, so the trim must shrink the crop to the block - what an official layer
		// rect records (the art's bounds, not the mesh's reach).
		val pageSize = 32
		val pageRgba = ByteArray(pageSize * pageSize * 4)
		for (rowIndex in 12 until 19) {
			for (columnIndex in 10 until 15) {
				pageRgba.fill(0xFF.toByte(), (rowIndex * pageSize + columnIndex) * 4, (rowIndex * pageSize + columnIndex) * 4 + 4)
			}
		}
		val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		// A quad over uv bbox [4, 28] squared, positions = page pixels (identity fit).
		val uvs = floatArrayOf(4f / 32f, 4f / 32f, 28f / 32f, 4f / 32f, 4f / 32f, 28f / 32f, 28f / 32f, 28f / 32f)
		val positions = FloatArray(uvs.size) { index -> uvs[index] * pageSize }
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(page),
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("TrimDrawable", uvs, positions, intArrayOf(0, 1, 2, 1, 2, 3)))),
				nowMillis = 1_700_000_000_000L,
			)
		val crop = PngCodec.read(chain.pngEntries.single { entry -> entry.path == "imageFileBuf_0.png" }.pngBytes)
		assertEquals(5, crop.width, "crop width is the opaque block's width")
		assertEquals(7, crop.height, "crop height is the opaque block's height")
		val textureManager = skeleton.root.textureManager as org.umamo.format.cmo3.model.gen.CTextureManager
		val atlas =
			Cmo3Import.elementsOf(textureManager._textureAtlases)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.CTextureAtlas>()
				.single()
		val entry =
			Cmo3Import.elementsOf(atlas.modelImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.ModelImageEntry>()
				.single()
		val packing = entry.materialLocalToAtlasTransform as org.umamo.format.cmo3.model.gen.GTransform2
		val packingPosition = packing.position as org.umamo.format.cmo3.model.type.GVector2
		assertEquals(10f, packingPosition.x, 1e-6f, "packing origin x is the block's page origin")
		assertEquals(12f, packingPosition.y, 1e-6f, "packing origin y is the block's page origin")
		val layeredImage =
			Cmo3Import.elementsOf(textureManager._rawImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.LayeredImageWrapper>()
				.single()
				.image as org.umamo.format.cmo3.model.gen.CLayeredImage
		val patchLayer =
			Cmo3Import.elementsOf((layeredImage._rootLayer as org.umamo.format.cmo3.model.gen.CLayerGroup)._children)
				.filterIsInstance<org.umamo.format.cmo3.model.custom.CLayer>()
				.single()
		val bounds = patchLayer.boundsOnImageDoc as org.umamo.format.cmo3.model.type.CRect
		assertEquals(10, bounds.x, "bounds origin x is the block's canvas position (identity fit)")
		assertEquals(12, bounds.y, "bounds origin y is the block's canvas position (identity fit)")
		assertEquals(5, bounds.width, "bounds width is the trimmed crop's width")
		assertEquals(7, bounds.height, "bounds height is the trimmed crop's height")
		// The cache transform records the raw dims over the 64-aligned cache raster (5/64, 7/64);
		// identity here stretches the art by the padding fraction in the editor's source-image mode.
		val modelImage =
			Cmo3Import.elementsOf(
				Cmo3Import.elementsOf(textureManager._modelImageGroups)
					.filterIsInstance<org.umamo.format.cmo3.model.gen.CModelImageGroup>()
					.single()
					._modelImages,
			).filterIsInstance<org.umamo.format.cmo3.model.custom.CModelImage>().single()
		val cachedImage =
			Cmo3Import.elementsOf((modelImage.cachedImageManager as org.umamo.format.cmo3.model.gen.CCachedImageManager).cachedImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.CCachedImage>()
				.single()
		val cacheTransform = cachedImage.transformRawImageToCachedImage as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(5f / 64f, cacheTransform.m00, "cache transform x is raw width over the 64-padded raster")
		assertEquals(7f / 64f, cacheTransform.m11, "cache transform y is raw height over the 64-padded raster")
	}

	@Test
	fun sameSlotSameMeshSharesOneMaterial() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Twin Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// Two drawables sampling the SAME atlas slot with the SAME mesh share one CModelImage at
		// the builder level (duplicating a material over one slot makes the editor's atlas
		// recomposite stack the shared art's alpha).  Different-placement twins only reach the
		// builder like this when the caller skips Cmo3AtlasUndedup - the conversion routes them
		// to their own slots first, because the shared image carries a single canvas placement
		// and the second twin would go missing in source-image mode.
		val pageSize = 32
		val pageRgba = ByteArray(pageSize * pageSize * 4)
		for (rowIndex in 12 until 19) {
			for (columnIndex in 10 until 15) {
				pageRgba.fill(0xFF.toByte(), (rowIndex * pageSize + columnIndex) * 4, (rowIndex * pageSize + columnIndex) * 4 + 4)
			}
		}
		val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		val uvs = floatArrayOf(4f / 32f, 4f / 32f, 28f / 32f, 4f / 32f, 4f / 32f, 28f / 32f, 28f / 32f, 28f / 32f)
		val indices = intArrayOf(0, 1, 2, 1, 2, 3)
		val straightPositions = FloatArray(uvs.size) { index -> uvs[index] * pageSize }
		val mirroredPositions =
			FloatArray(uvs.size) { index ->
				if (index % 2 == 0) {
					-(uvs[index] * pageSize) + 100f
				} else {
					uvs[index] * pageSize
				}
			}
		val chain =
			Cmo3ImageChainBuilder.populate(
				skeleton.root,
				listOf(page),
				listOf(
					listOf(
						Cmo3ImageChainBuilder.DrawableRegion("TwinA", uvs, straightPositions, indices),
						Cmo3ImageChainBuilder.DrawableRegion("TwinB", uvs, mirroredPositions, indices),
					),
				),
				nowMillis = 1_700_000_000_000L,
			)
		val bindingA = chain.bindingByDrawableId.getValue("TwinA")
		val bindingB = chain.bindingByDrawableId.getValue("TwinB")
		assertEquals(bindingA.modelImageGuid, bindingB.modelImageGuid, "twins share one model image")
		assertEquals(1f, bindingA.inputImageLocalToCanvasTransform.m00, 1e-4f, "twin A keeps its own fit")
		assertEquals(-1f, bindingB.inputImageLocalToCanvasTransform.m00, 1e-4f, "twin B keeps its mirrored fit")
		val textureManager = skeleton.root.textureManager as org.umamo.format.cmo3.model.gen.CTextureManager
		val modelImages =
			Cmo3Import.elementsOf(
				Cmo3Import.elementsOf(textureManager._modelImageGroups)
					.filterIsInstance<org.umamo.format.cmo3.model.gen.CModelImageGroup>()
					.single()
					._modelImages,
			).filterIsInstance<org.umamo.format.cmo3.model.custom.CModelImage>()
		assertEquals(1, modelImages.size, "one shared material, not one per twin")
		val entries =
			Cmo3Import.elementsOf(
				Cmo3Import.elementsOf(textureManager._textureAtlases)
					.filterIsInstance<org.umamo.format.cmo3.model.gen.CTextureAtlas>()
					.single()
					.modelImages,
			).filterIsInstance<org.umamo.format.cmo3.model.gen.ModelImageEntry>()
		assertEquals(1, entries.size, "one atlas entry for the shared material")
		// The shared image keeps the FIRST drawable's placement (identity fit -> the block origin).
		val placement = modelImages.single()._materialLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(10f, placement.m02, "shared placement is twin A's")
		assertEquals(12f, placement.m12, "shared placement is twin A's")
	}

	@Test
	fun placementSnapsToIntegerCanvasOrigin() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Snap Test", 32, 32, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// The trim fixture's opaque block, but positions carry a FRACTIONAL translation
		// (+0.4, -0.3): the layer placement must snap to whole canvas pixels (every official
		// _materialLocalToCanvasTransform translation is integral), bounds must equal the
		// snapped transform without a second rounding, and the declared packing origin must
		// carry the complementary fraction so the web still composes exactly.
		val pageSize = 32
		val pageRgba = ByteArray(pageSize * pageSize * 4)
		for (rowIndex in 12 until 19) {
			for (columnIndex in 10 until 15) {
				pageRgba.fill(0xFF.toByte(), (rowIndex * pageSize + columnIndex) * 4, (rowIndex * pageSize + columnIndex) * 4 + 4)
			}
		}
		val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		val uvs = floatArrayOf(4f / 32f, 4f / 32f, 28f / 32f, 4f / 32f, 4f / 32f, 28f / 32f, 28f / 32f, 28f / 32f)
		val positions =
			FloatArray(uvs.size) { index ->
				if (index % 2 == 0) {
					uvs[index] * pageSize + 0.4f
				} else {
					uvs[index] * pageSize - 0.3f
				}
			}
		Cmo3ImageChainBuilder.populate(
			skeleton.root,
			listOf(page),
			listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("SnapDrawable", uvs, positions, intArrayOf(0, 1, 2, 1, 2, 3)))),
			nowMillis = 1_700_000_000_000L,
		)
		val textureManager = skeleton.root.textureManager as org.umamo.format.cmo3.model.gen.CTextureManager
		val modelImage =
			Cmo3Import.elementsOf(
				Cmo3Import.elementsOf(textureManager._modelImageGroups)
					.filterIsInstance<org.umamo.format.cmo3.model.gen.CModelImageGroup>()
					.single()
					._modelImages,
			).filterIsInstance<org.umamo.format.cmo3.model.custom.CModelImage>().single()
		val placement = modelImage._materialLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(10f, placement.m02, "placement x snaps to the whole canvas pixel (10.4 -> 10)")
		assertEquals(12f, placement.m12, "placement y snaps to the whole canvas pixel (11.7 -> 12)")
		val atlas =
			Cmo3Import.elementsOf(textureManager._textureAtlases)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.CTextureAtlas>()
				.single()
		val entry =
			Cmo3Import.elementsOf(atlas.modelImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.ModelImageEntry>()
				.single()
		val packing = entry.materialLocalToAtlasTransform as org.umamo.format.cmo3.model.gen.GTransform2
		val packingPosition = packing.position as org.umamo.format.cmo3.model.type.GVector2
		assertEquals(9.6f, packingPosition.x, 1e-4f, "packing origin carries the complementary x fraction")
		assertEquals(12.3f, packingPosition.y, 1e-4f, "packing origin carries the complementary y fraction")
		val layeredImage =
			Cmo3Import.elementsOf(textureManager._rawImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.LayeredImageWrapper>()
				.single()
				.image as org.umamo.format.cmo3.model.gen.CLayeredImage
		val patchLayer =
			Cmo3Import.elementsOf((layeredImage._rootLayer as org.umamo.format.cmo3.model.gen.CLayerGroup)._children)
				.filterIsInstance<org.umamo.format.cmo3.model.custom.CLayer>()
				.single()
		val bounds = patchLayer.boundsOnImageDoc as org.umamo.format.cmo3.model.type.CRect
		assertEquals(10, bounds.x, "bounds origin x equals the snapped placement")
		assertEquals(12, bounds.y, "bounds origin y equals the snapped placement")
	}

	@Test
	fun patchWebCarriesPlacementAndCropBytes() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Patch Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// A gradient page so the crop subregion is verifiable byte-for-byte.
		val pageSize = 8
		val pageRgba = ByteArray(pageSize * pageSize * 4) { index -> index.toByte() }
		val page = Cmo3Conversion.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
		// uv bbox [0.25, 0.5] x [0.5, 1.0] -> patch x 2..4, y 4..8; positions via the exact affine
		// canvas = (2*pageX + 3, -1*pageY + 10) so the fit recovers it.
		val uvs = floatArrayOf(0.25f, 0.5f, 0.5f, 0.5f, 0.25f, 1.0f, 0.5f, 1.0f)
		val positions =
			FloatArray(uvs.size) { index ->
				val vertexIndex = index / 2
				if (index % 2 == 0) {
					2f * (uvs[2 * vertexIndex] * pageSize) + 3f
				} else {
					-1f * (uvs[2 * vertexIndex + 1] * pageSize) + 10f
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
		assertEquals(2f, binding.inputImageLocalToCanvasTransform.m00, 1e-4f, "fitted x scale")
		assertEquals(-1f, binding.inputImageLocalToCanvasTransform.m11, 1e-4f, "fitted y scale (flip)")
		assertEquals(3f, binding.inputImageLocalToCanvasTransform.m02, 1e-3f, "fitted x offset")
		assertEquals(10f, binding.inputImageLocalToCanvasTransform.m12, 1e-3f, "fitted y offset")

		// The atlas entry carries the patch origin and the same fitted placement.
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
		val packing = entry.materialLocalToAtlasTransform as org.umamo.format.cmo3.model.gen.GTransform2
		val packingPosition = packing.position as org.umamo.format.cmo3.model.type.GVector2
		assertEquals(2f, packingPosition.x, 1e-6f, "patch origin x on the page")
		assertEquals(4f, packingPosition.y, 1e-6f, "patch origin y on the page")
		val entryPlacement = entry.atlasLocalToCanvasTransform as org.umamo.format.cmo3.model.type.CAffine
		assertEquals(2f, entryPlacement.m00, 1e-4f, "entry placement matches the fit")
		assertEquals(10f, entryPlacement.m12, 1e-3f, "entry placement matches the fit")

		// The synthetic source doc is the PAGE's frame (official docs carry the source image's own
		// size, not the canvas), and the layer's boundsOnImageDoc carries the patch's canvas
		// placement as its origin with the crop dims as its size - every corpus layer's rect
		// equals its model image's _materialLocalToCanvasTransform translation plus its
		// imageResource dims.
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
		val bounds = patchLayer.boundsOnImageDoc as org.umamo.format.cmo3.model.type.CRect
		assertEquals(7, bounds.x, "bounds origin x is the fitted patch-origin placement (2*2 + 3)")
		assertEquals(6, bounds.y, "bounds origin y is the fitted patch-origin placement (-1*4 + 10)")
		assertEquals(2, bounds.width, "bounds width is the crop width")
		assertEquals(4, bounds.height, "bounds height is the crop height")

		// The crop PNG is the page subregion (x 2..4, y 4..8).
		val cropEntry = chain.pngEntries.single { pngEntry -> pngEntry.path == "imageFileBuf_0.png" }
		val crop = PngCodec.read(cropEntry.pngBytes)
		assertEquals(2, crop.width, "crop width")
		assertEquals(4, crop.height, "crop height")
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
}