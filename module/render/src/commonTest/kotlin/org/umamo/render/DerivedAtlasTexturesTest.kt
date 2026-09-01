package org.umamo.render

import org.umamo.format.atlas.AtlasPackItem
import org.umamo.format.atlas.AtlasPackOptions
import org.umamo.format.atlas.packAtlas
import org.umamo.runtime.model.AtlasPage
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the generated-page derivation against the packer itself: packing a tile set and deriving the
 * pages back from the lowered placements must produce byte-identical pixels and the same page
 * numbering.  This is the gate on the trim/extrude coupling - the derivation re-runs the packer's
 * trim analysis under the same policy, and any drift between the two shows up here as a byte diff.
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

	private fun packedFixture(): Triple<PuppetModel, SourceArtRasters, List<DecodedImage>> {
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
			}
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
		val pngByTile =
			tileIds.mapIndexed { tileIndex, tileId -> tileId to encodeAtlasPng(rasters[tileIndex]) }.toMap()
		val store = SourceArtRasters { tileId -> pngByTile[tileId] }
		return Triple(model, store, result.pages.map { page -> DecodedImage(page.rgba, page.width, page.height) })
	}

	@Test
	fun derivedPagesAreByteIdenticalToThePackersAndShareItsNumbering() {
		val (model, store, packedPages) = packedFixture()

		val derived = assertNotNull(deriveAtlasTextures(model, store, premultipliedAlpha = true))

		assertEquals(packedPages.size, derived.atlases.size, "one derived page per packed page")
		for (pageIndex in packedPages.indices) {
			assertEquals(packedPages[pageIndex].width, derived.atlases[pageIndex].width)
			assertEquals(packedPages[pageIndex].height, derived.atlases[pageIndex].height)
			assertContentEquals(
				packedPages[pageIndex].rgba,
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
	fun aPlacementThePackerCouldNotHaveAuthoredIsNotDerivable() {
		val (model, store, _) = packedFixture()
		val scaledTiles =
			model.atlas.tiles.mapIndexed { tileIndex, tile ->
				if (tileIndex == 0) {
					tile.copy(placement = tile.placement?.copy(scaleX = 0.5f))
				} else {
					tile
				}
			}

		val derived = deriveAtlasTextures(model.copy(atlas = model.atlas.copy(tiles = scaledTiles)), store, false)

		assertNull(derived, "a scaled placement has no reconstructible pack")
	}

	@Test
	fun artThatWillNotDecodeIsNotDerivable() {
		val (model, _, _) = packedFixture()
		val brokenStore = SourceArtRasters { ByteArray(4) }

		assertNull(deriveAtlasTextures(model, brokenStore, false), "undecodable art cannot compose a page")
	}
}