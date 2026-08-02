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
		val chain = Cmo3ImageChainBuilder.populate(skeleton.root, pages, nowMillis = 1_700_000_000_000L)
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
		val chain = Cmo3ImageChainBuilder.populate(skeleton.root, listOf(solidPage(4, 0x11)), nowMillis = 1_700_000_000_000L)
		val model =
			Cmo3.read(
				Cmo3FreshFile.assemble(
					skeleton.root,
					skeleton.iconEntries.map { icon -> Cmo3FreshFile.PngEntry(icon.path, icon.pngBytes) } + chain.pngEntries,
					obfuscateKey = 0x1234ABCD,
				),
			)
		val drawableId = DrawableId("BoundDrawable")
		val uvs = floatArrayOf(0.1f, 0.2f, 0.9f, 0.2f, 0.5f, 0.8f)
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
									positions = floatArrayOf(10f, 10f, 90f, 10f, 50f, 90f),
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
		val report = Cmo3Export.apply(puppet, model, mapOf(drawableId.raw to chain.pageBindings[0]))
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
}
