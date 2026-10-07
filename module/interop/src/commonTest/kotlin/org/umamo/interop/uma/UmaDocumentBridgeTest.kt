package org.umamo.interop.uma

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriteException
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasComposition
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the whole-document bridge (docs/format/UMA.md §5, §6) on synthetic models: every atlas and source field
 * survives a save exactly for every atlas shape, a reopened document samples the render pages it was saved with or
 * derives them, and the comparer the corpus gate relies on sees a one-bit change.
 */
class UmaDocumentBridgeTest {
	private val writer = UmaWriterInfo("Umamo", "test")

	private val digest = "0123456789abcdef".repeat(4)

	/**
	 * A PNG of one flat color.
	 *
	 * @param Int width  The width.
	 * @param Int height The height.
	 * @param Int value  The byte every channel takes.
	 * @return ByteArray The PNG.
	 */
	private fun png(width: Int, height: Int, value: Int): ByteArray = PngCodec.write(RasterImage(width, height, ByteArray(width * height * 4) { value.toByte() }))

	/**
	 * A drawable bound to [tile], or to a page when [texturePage] is given.
	 *
	 * @param String  id          The drawable's id.
	 * @param String? tile        The tile it samples.
	 * @param Int     texturePage The page it samples, or -1.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, tile: String?, texturePage: Int = -1): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			texturePage = texturePage,
			atlasTileId = tile?.let(::AtlasTileId),
		)

	/**
	 * A model over [drawables] with [atlas] and [sources].
	 *
	 * @param List        drawables The drawables.
	 * @param PuppetAtlas atlas     The atlas.
	 * @param List        sources   The linked source art.
	 * @return PuppetModel The model.
	 */
	private fun modelOf(drawables: List<Drawable>, atlas: PuppetAtlas, sources: List<ArtSource> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { item -> OrgChild.Drawable(item.id) },
			rootPartId = null,
			atlas = atlas,
			sources = sources,
		).withDerivedRenderRoot()

	/**
	 * An atlas using every field: layer-addressed coordinates, a composition off the defaults, a placed and pinned
	 * tile with a stable binding and a placement whose floats a decimal round trip is most likely to bend, a
	 * reloaded tile with a weak binding and a lineage, and a bare tile.
	 *
	 * @return PuppetAtlas The atlas.
	 */
	private fun fullAtlas(): PuppetAtlas =
		PuppetAtlas(
			pages = listOf(AtlasPage(16, 16)),
			tiles =
				listOf(
					AtlasTile(AtlasTileId("art-0/lyid:1"), "Eye", 4, 3, AtlasPlacement(0, 1.5f, -0.0f, -1f, Float.MIN_VALUE, 90.25f), SourceLayerRef(ArtSourceId("art-0"), "lyid:1", true), pinned = true),
					AtlasTile(AtlasTileId("art-0/name:Mouth#2~1"), "Mouth", 2, 2, source = SourceLayerRef(ArtSourceId("art-0"), "name:Mouth#2", false), replaces = AtlasTileId("art-0/name:Mouth#2")),
					AtlasTile(AtlasTileId("guid-3"), "Hair", 5, 1),
				),
			storedUvsAddressPages = false,
			composition = AtlasComposition(alphaThreshold = 8, extrude = 0),
		)

	/**
	 * Sources using every field and flag.
	 *
	 * @return List<ArtSource> The sources.
	 */
	private fun fullSources(): List<ArtSource> =
		listOf(
			ArtSource(
				id = ArtSourceId("art-0"),
				name = "character.clip",
				path = "C:/work/character.clip",
				format = "clip",
				layers =
					listOf(
						ArtSourceLayer("lyid:1", "Eye", "Head/Face", -12, 180, 4, 3, visible = false, present = false, contentHash = digest, empty = true, replaced = true, ignored = true),
						ArtSourceLayer("name:Mouth#2", "Mouth", "", 0, 0, 2, 2, visible = true),
					),
				contentHash = digest,
				lastModified = 1_757_894_400_123L,
				offsetX = 64,
				offsetZ = -32,
			),
			ArtSource(ArtSourceId("guid-7"), "Erica.psd", null, "psd"),
		)

	/** The PNG per tile of [fullAtlas], by id. */
	private val tilePngs: Map<String, ByteArray> =
		mapOf("art-0/lyid:1" to png(4, 3, 10), "art-0/name:Mouth#2~1" to png(2, 2, 20), "guid-3" to png(5, 1, 30))

	/**
	 * [model] saved as a whole document and read back.
	 *
	 * @param PuppetModel         model       The model.
	 * @param UmaRenderPagePixels renderPages What its drawables sample.
	 * @return UmaModel The reopened document.
	 */
	private fun saved(model: PuppetModel, renderPages: UmaRenderPagePixels): UmaModel =
		Uma.read(Uma.write(UmaDocumentBridge.documentOf(UmaModel.create(writer), model, UmaPixelSource({ id -> tilePngs[id] }, renderPages, null))))

	/**
	 * Every atlas and source field survives a save exactly, and the render pages come back with each drawable's image.
	 */
	@Test
	fun everyAtlasAndSourceFieldRoundTrips() {
		val model = modelOf(listOf(drawable("D0", "art-0/lyid:1"), drawable("D1", "guid-3")), fullAtlas(), fullSources())
		val reducedCopy = png(2, 2, 60)
		val document = saved(model, UmaRenderPagePixels.Stored(listOf(reducedCopy, tilePngs.getValue("guid-3")), mapOf("D0" to 0, "D1" to 1)))
		val reopened = UmaDocumentBridge.modelOf(document)
		val differences = documentDifferences(model, reopened)
		assertTrue(differences.isEmpty(), "differences: $differences")
		assertEquals(model.atlas, reopened.atlas)
		assertEquals(model.sources, reopened.sources)

		val pages = UmaDocumentBridge.pagesOf(document)
		val pageSet = assertNotNull(pages.pageSet, "stored render pages come back as a page set")
		assertEquals(mapOf("D0" to 0, "D1" to 1), pageSet.atlasIndexByDrawableId)
		assertContentEquals(reducedCopy, pageSet.pageBytes[0])
		assertContentEquals(tilePngs.getValue("guid-3"), pageSet.pageBytes[1])
		assertContentEquals(tilePngs.getValue("art-0/name:Mouth#2~1"), pages.tilePng(AtlasTileId("art-0/name:Mouth#2~1")), "an unbound tile's pixels are there")
	}

	/**
	 * A document whose pages derive stores no render pages, so the loader composes them.
	 */
	@Test
	fun derivedPagesStoreNoPageSet() {
		val model = modelOf(listOf(drawable("D0", "art-0/lyid:1")), fullAtlas().copy(storedUvsAddressPages = true))
		val document = saved(model, UmaRenderPagePixels.Derived)
		assertNull(UmaDocumentBridge.pagesOf(document).pageSet)
		assertEquals(model.atlas, UmaDocumentBridge.modelOf(document).atlas)
	}

	/**
	 * Every atlas shape round-trips exactly: pages with no tiles (a CMO3 whose atlas holds no model image), and no
	 * pages or tiles at all beside stored render pages the drawables name by page number (a MOC3-origin rig).
	 */
	@Test
	fun everyAtlasShapeRoundTrips() {
		val pagesOnly = modelOf(emptyList(), PuppetAtlas(pages = listOf(AtlasPage(1024, 1024))))
		assertEquals(pagesOnly.atlas, UmaDocumentBridge.modelOf(saved(pagesOnly, UmaRenderPagePixels.Derived)).atlas, "pages without tiles")

		val tileless = modelOf(listOf(drawable("ArtMesh0", null, texturePage = 1), drawable("ArtMesh1", null, texturePage = 0)), PuppetAtlas.Empty)
		val pagePngs = listOf(png(16, 16, 1), png(8, 8, 2))
		val document = saved(tileless, UmaRenderPagePixels.Stored(pagePngs, mapOf("ArtMesh0" to 1, "ArtMesh1" to 0)))
		val reopened = UmaDocumentBridge.modelOf(document)
		assertEquals(PuppetAtlas.Empty, reopened.atlas, "the render pages do not enter an empty atlas")
		assertEquals(listOf(1, 0), reopened.drawables.map { item -> item.texturePage })
		val pageSet = assertNotNull(UmaDocumentBridge.pagesOf(document).pageSet)
		assertContentEquals(pagePngs[1], pageSet.pageBytes[pageSet.atlasIndexByDrawableId.getValue("ArtMesh0")])
		assertContentEquals(pagePngs[0], pageSet.pageBytes[pageSet.atlasIndexByDrawableId.getValue("ArtMesh1")])
	}

	/**
	 * The comparer sees a one-bit change in a placement, a flipped inventory flag, and a changed hash.
	 */
	@Test
	fun comparerSeesSmallDifferences() {
		val model = modelOf(listOf(drawable("D0", "art-0/lyid:1")), fullAtlas(), fullSources())
		val placement = model.atlas.tiles[0].placement!!
		val nudged = placement.copy(positionX = Float.fromBits(placement.positionX.toRawBits() + 1))
		val movedTile = model.copy(atlas = model.atlas.copy(tiles = listOf(model.atlas.tiles[0].copy(placement = nudged)) + model.atlas.tiles.drop(1)))
		assertEquals(1, atlasDifferences(model, movedTile).size, "a one-bit placement change")
		val source = model.sources[0]
		val flipped = model.copy(sources = listOf(source.copy(layers = listOf(source.layers[0].copy(ignored = false)) + source.layers.drop(1))) + model.sources.drop(1))
		assertEquals(1, sourcesDifferences(model, flipped).size, "a flipped ignore mark")
		val rehashed = model.copy(sources = listOf(source.copy(contentHash = digest.reversed())) + model.sources.drop(1))
		assertEquals(1, sourcesDifferences(model, rehashed).size, "a changed file hash")
	}

	/**
	 * A save refuses a placement JSON cannot hold, naming where.
	 */
	@Test
	fun unwritableAtlasesAreRefused() {
		val atlas = fullAtlas()
		val nanTile = atlas.tiles[0].copy(placement = atlas.tiles[0].placement!!.copy(rotationDegrees = Float.NaN))
		val failure = assertFailsWith<UmaWriteException> { UmaTexturesBridge.texturesOf(modelOf(emptyList(), atlas.copy(tiles = listOf(nanTile)))) }
		assertTrue(failure.path.contains("tiles[art-0/lyid:1].placement.rotationDegrees"), "the failure names ${failure.path}")
	}
}