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
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("BoundDrawable", uvs, positions))),
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
	fun patchWebCarriesPlacementAndCropBytes() {
		val skeleton = Cmo3SkeletonBuilder.buildBlank("Patch Test", 100, 100, RuntimeTarget.Cubism53.cmo3TargetVersionNo())
		// A gradient page so the crop subregion is verifiable byte-for-byte.
		val pageSize = 8
		val pageRgba = ByteArray(pageSize * pageSize * 4) { index -> index.toByte() }
		val page = Cmo3ImageChainBuilder.AtlasPage(PngCodec.write(RasterImage(pageSize, pageSize, pageRgba)), pageSize, pageSize)
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
				listOf(listOf(Cmo3ImageChainBuilder.DrawableRegion("PatchDrawable", uvs, positions))),
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

		// The layer's bounds are the patch's CANVAS rect: fit-mapped corners (7,6) and (11,2),
		// normalized across the y flip.
		val layeredImage =
			Cmo3Import.elementsOf(textureManager._rawImages)
				.filterIsInstance<org.umamo.format.cmo3.model.gen.LayeredImageWrapper>()
				.single()
				.image as org.umamo.format.cmo3.model.gen.CLayeredImage
		assertEquals(100, layeredImage.width, "doc width is the canvas")
		assertEquals(100, layeredImage.height, "doc height is the canvas")
		val patchLayer =
			Cmo3Import.elementsOf((layeredImage._rootLayer as org.umamo.format.cmo3.model.gen.CLayerGroup)._children)
				.filterIsInstance<org.umamo.format.cmo3.model.custom.CLayer>()
				.single()
		val bounds = patchLayer.boundsOnImageDoc as org.umamo.format.cmo3.model.type.CRect
		assertEquals(7, bounds.x, "canvas bounds x")
		assertEquals(2, bounds.y, "canvas bounds y (flip-normalized)")
		assertEquals(4, bounds.width, "canvas bounds width")
		assertEquals(4, bounds.height, "canvas bounds height")

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
