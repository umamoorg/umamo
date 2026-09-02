package org.umamo.render

import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.packAtlas
import org.umamo.runtime.model.AtlasPage
import org.umamo.runtime.model.AtlasPlacement
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the generated-page derivation against the packer itself: packing a tile set and deriving the
 * pages back from the lowered placements must produce byte-identical pixels and the same page
 * numbering.  This is the gate on the trim/extrude coupling - the derivation re-runs the packer's
 * trim analysis under the same policy, and any drift between the two shows up here as a byte diff.
 * Beyond the packer's own placements, a hand-authored one (moved, scaled, rotated, off the pixel
 * grid) derives through the affine composer instead of being refused.
 */
class DerivedAtlasTexturesTest {
	private val tileIds = listOf(AtlasTileId("t0"), AtlasTileId("t1"), AtlasTileId("t2"))

	/**
	 * A raster with a transparent margin around a filled core, so the pack genuinely trims.
	 *
	 * @param Int width  The raster width in pixels.
	 * @param Int height The raster height in pixels.
	 * @param Int margin The transparent border width on every side.
	 * @param Int red    The core's red byte.
	 * @return ByteArray RGBA8888 pixels.
	 */
	private fun tileRaster(width: Int, height: Int, margin: Int, red: Int): ByteArray {
		val rgba = ByteArray(width * height * 4)
		for (rowIndex in margin until height - margin) {
			for (columnIndex in margin until width - margin) {
				val offset = (rowIndex * width + columnIndex) * 4
				rgba[offset] = red.toByte()
				rgba[offset + 1] = (rowIndex * 16 + columnIndex).toByte()
				rgba[offset + 2] = 0x40
				rgba[offset + 3] = 0xFF.toByte()
			}
		}
		return rgba
	}

	private class Fixture(
		val model: PuppetModel,
		val store: SourceArtRasters,
		val packedPages: List<DecodedImage>,
		val rasters: List<DecodedImage>,
	)

	/**
	 * Three tiles packed by the packer, lowered onto a model, and a store serving their pixels.
	 *
	 * @param List extraTiles Tiles appended to the atlas AFTER the pack, with the raster the store serves for each.
	 * @return Fixture The packed model, its store, the packer's pages, and the three tile rasters.
	 */
	private fun packedFixture(extraTiles: List<Pair<AtlasTile, DecodedImage>> = emptyList()): Fixture {
		val rasters =
			listOf(
				DecodedImage(tileRaster(12, 10, 2, 0x10), 12, 10),
				DecodedImage(tileRaster(8, 16, 1, 0x20), 8, 16),
				DecodedImage(tileRaster(10, 10, 0, 0x30), 10, 10),
			)
		val items =
			tileIds.mapIndexed { tileIndex, tileId ->
				AtlasPackItem(tileId.raw, rasters[tileIndex].width, rasters[tileIndex].height, rasters[tileIndex].rgba)
			}
		val result = packAtlas(items, AtlasPackOptions(maxPageSize = 64))
		assertEquals(tileIds.size, result.placements.size, "every fixture tile packs")

		val placementByKey = result.placements.associateBy { placement -> placement.key }
		val tiles =
			tileIds.mapIndexed { tileIndex, tileId ->
				AtlasTile(
					tileId,
					tileId.raw,
					rasters[tileIndex].width,
					rasters[tileIndex].height,
					atlasPlacementFromPack(placementByKey.getValue(tileId.raw)),
				)
			} + extraTiles.map { (tile, _) -> tile }
		val drawables =
			tileIds.mapIndexed { tileIndex, tileId ->
				Drawable(
					id = DrawableId("d$tileIndex"),
					name = "d$tileIndex",
					parentDeformerId = null,
					blendMode = BlendMode.Normal,
					maskedBy = emptyList(),
					mesh = null,
					geometryGrid = null,
					atlasTileId = tileId,
				)
			}
		val model =
			PuppetModel(
				parameters = emptyList(),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = drawables,
				rootChildren = emptyList(),
				rootPartId = null,
				canvasWidth = 100f,
				canvasHeight = 100f,
				worldOriginX = 50f,
				worldOriginY = 50f,
				atlas =
					PuppetAtlas(
						pages = result.pages.map { page -> AtlasPage(page.width, page.height) },
						tiles = tiles,
					),
			)
		val pngByTile = HashMap<AtlasTileId, ByteArray>()
		for ((tileIndex, tileId) in tileIds.withIndex()) {
			pngByTile[tileId] = encodeAtlasPng(rasters[tileIndex])
		}
		for ((tile, raster) in extraTiles) {
			pngByTile[tile.id] = encodeAtlasPng(raster)
		}
		val store = SourceArtRasters { tileId -> pngByTile[tileId] }
		return Fixture(model, store, result.pages.map { page -> DecodedImage(page.rgba, page.width, page.height) }, rasters)
	}

	private fun alphaAt(page: DecodedImage, x: Int, y: Int): Int = page.rgba[(y * page.width + x) * 4 + 3].toInt() and 0xFF

	private fun pixelBytes(image: DecodedImage, x: Int, y: Int): ByteArray {
		val offset = (y * image.width + x) * 4
		return image.rgba.copyOfRange(offset, offset + 4)
	}

	private fun PuppetModel.withTilePlacement(tileIndex: Int, placement: AtlasPlacement): PuppetModel =
		copy(atlas = atlas.copy(tiles = atlas.tiles.mapIndexed { index, tile -> if (index == tileIndex) tile.copy(placement = placement) else tile }))

	@Test
	fun derivedPagesAreByteIdenticalToThePackersAndShareItsNumbering() {
		val fixture = packedFixture()
		val model = fixture.model

		val derived = assertNotNull(deriveAtlasTextures(model, fixture.store, premultipliedAlpha = true))

		assertEquals(fixture.packedPages.size, derived.atlases.size, "one derived page per packed page")
		for (pageIndex in fixture.packedPages.indices) {
			assertEquals(fixture.packedPages[pageIndex].width, derived.atlases[pageIndex].width)
			assertEquals(fixture.packedPages[pageIndex].height, derived.atlases[pageIndex].height)
			assertContentEquals(
				fixture.packedPages[pageIndex].rgba,
				derived.atlases[pageIndex].rgba,
				"page $pageIndex pixels re-derive byte-identically",
			)
		}
		assertTrue(derived.premultipliedAlpha, "the convention flag passes through")
		for ((tileIndex, tileId) in tileIds.withIndex()) {
			val placement = assertNotNull(model.atlas.tiles[tileIndex].placement)
			assertEquals(
				placement.pageIndex,
				derived.atlasIndexByDrawableId["d$tileIndex"],
				"drawable d$tileIndex maps to its tile '$${tileId.raw}' page under the MODEL's numbering",
			)
		}
	}

	@Test
	fun aScaledPlacementDerivesThroughTheAffinePath() {
		val fixture = packedFixture()
		val original = assertNotNull(fixture.model.atlas.tiles[0].placement)
		val scaled = fixture.model.withTilePlacement(0, original.copy(scaleX = 0.5f, scaleY = 0.5f))

		val derived = assertNotNull(deriveAtlasTextures(scaled, fixture.store, false), "a scaled placement resamples rather than refusing")

		val page = derived.atlases[original.pageIndex]
		// Tile 0's opaque core is its 8x6 trim at (2, 2); halved about the tile origin it covers
		// [origin + 1, origin + 5) by [origin + 1, origin + 4), and its old far corner is vacated.
		val originX = original.positionX.toInt()
		val originY = original.positionY.toInt()
		assertEquals(255, alphaAt(page, originX + 2, originY + 2), "inside the halved footprint")
		assertEquals(0, alphaAt(page, originX + 8, originY + 6), "the unscaled footprint's far corner is empty now")
		assertFalse(page.rgba.contentEquals(fixture.packedPages[original.pageIndex].rgba), "the page changed")
	}

	@Test
	fun aMovedPlacementComposesTheTileAtItsNewSpotAndVacatesTheOld() {
		val fixture = packedFixture()
		val original = assertNotNull(fixture.model.atlas.tiles[0].placement)
		val packedPage = fixture.packedPages[original.pageIndex]
		// The trim origin on the page, and a move of three pixels toward whichever side has room.
		val trimPageX = original.positionX.toInt() + 2
		val trimPageY = original.positionY.toInt() + 2
		val deltaX = if (trimPageX + 8 + 3 + 2 <= packedPage.width) 3 else -3
		val deltaY = if (trimPageY + 6 + 2 + 2 <= packedPage.height) 2 else -2
		val moved = fixture.model.withTilePlacement(0, original.copy(positionX = original.positionX + deltaX, positionY = original.positionY + deltaY))

		val derived = assertNotNull(deriveAtlasTextures(moved, fixture.store, false))

		val page = derived.atlases[original.pageIndex]
		assertContentEquals(
			pixelBytes(fixture.rasters[0], 3, 3),
			pixelBytes(page, trimPageX + deltaX + 1, trimPageY + deltaY + 1),
			"the tile's pixels sit at the new spot verbatim",
		)
		val vacatedX = if (deltaX > 0) trimPageX else trimPageX + 7
		assertEquals(255, alphaAt(packedPage, vacatedX, trimPageY), "the column was the tile's before the move")
		assertEquals(0, alphaAt(page, vacatedX, trimPageY), "the column the tile left behind is empty (beyond the extrusion band)")
	}

	@Test
	fun aRotatedFractionalPlacementDerives() {
		val fixture = packedFixture()
		val original = assertNotNull(fixture.model.atlas.tiles[0].placement)
		val turned = original.copy(positionX = original.positionX + 0.5f, rotationDegrees = 30f)
		val rotated = fixture.model.withTilePlacement(0, turned)

		val derived = assertNotNull(deriveAtlasTextures(rotated, fixture.store, false), "rotation and a fractional position resample rather than refuse")

		val page = derived.atlases[original.pageIndex]
		val trim = assertNotNull(derivedTileTrim(fixture.rasters[0]))
		val footprint = placementFootprint(turned, trim, reserve = null)
		val centerX = ((footprint.left + footprint.right) / 2f).toInt().coerceIn(0, page.width - 1)
		val centerY = ((footprint.top + footprint.bottom) / 2f).toInt().coerceIn(0, page.height - 1)
		assertTrue(alphaAt(page, centerX, centerY) > 0, "the turned tile's center is painted")
	}

	@Test
	fun aPlacedTileWithNothingOpaqueIsSkippedNotRefused() {
		val emptyTile = AtlasTile(AtlasTileId("empty"), "empty", 4, 4, AtlasPlacement(0, 0f, 0f, 1f, 1f, 0f))
		val fixture = packedFixture(extraTiles = listOf(emptyTile to DecodedImage(ByteArray(4 * 4 * 4), 4, 4)))

		val derived = assertNotNull(deriveAtlasTextures(fixture.model, fixture.store, false), "an empty placed tile draws nothing")

		for (pageIndex in fixture.packedPages.indices) {
			assertContentEquals(fixture.packedPages[pageIndex].rgba, derived.atlases[pageIndex].rgba, "page $pageIndex is untouched by the empty tile")
		}
	}

	@Test
	fun artThatWillNotDecodeIsNotDerivable() {
		val fixture = packedFixture()
		val brokenStore = SourceArtRasters { ByteArray(4) }

		assertNull(deriveAtlasTextures(fixture.model, brokenStore, false), "undecodable art cannot compose a page")
		assertNull(planAtlasDerivation(fixture.model, brokenStore), "the plan refuses before any pixel is composed")
	}

	@Test
	fun aPlacementNamingAMissingPageIsNotDerivable() {
		val fixture = packedFixture()
		val original = assertNotNull(fixture.model.atlas.tiles[0].placement)
		val stray = fixture.model.withTilePlacement(0, original.copy(pageIndex = fixture.model.atlas.pages.size))

		assertNull(planAtlasDerivation(stray, fixture.store), "a page the atlas lacks is a document fault, not a placement to resample")
	}
}