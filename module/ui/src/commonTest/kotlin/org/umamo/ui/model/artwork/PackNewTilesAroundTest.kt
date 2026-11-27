package org.umamo.ui.model.artwork

import org.umamo.interop.art.SourceArtImportNotice
import org.umamo.render.DecodedImage
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
import org.umamo.ui.model.repack.MAX_IMPORT_PAGE_SIZE
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pack that lands new art in an open document: the placed art holds still and refuses the pack if
 * it would be lost, while new art and art the document has not placed yet is placed when it fits and
 * left unplaced, with a note, when it does not.
 */
class PackNewTilesAroundTest {
	private val placedId = AtlasTileId("placed")
	private val newId = AtlasTileId("new")
	private val leftOutId = AtlasTileId("leftOut")
	private val backgroundId = AtlasTileId("background")
	private val placedSpot = AtlasPlacement(0, 0f, 0f, 1f, 1f, 0f)

	@Test
	fun unplacedArtThatFitsIsPlacedAndArtThatDoesNotIsANote() {
		val model = modelOf()

		val packed = assertIs<PackAroundOutcome.Packed>(packNewTilesAround(model, setOf(newId), decoder(placedDecodes = true), false, emptyList()))

		val atlas = packed.model.atlas
		assertEquals(placedSpot, atlas.tileById.getValue(placedId).placement, "the placed art held still")
		assertNotNull(atlas.tileById.getValue(newId).placement, "the new art is placed")
		assertNotNull(atlas.tileById.getValue(leftOutId).placement, "art an earlier pack left unplaced is placed now that it fits")
		assertNull(atlas.tileById.getValue(backgroundId).placement, "art larger than any page stays unplaced")
		assertTrue(SourceArtImportNotice.LayerLargerThanPage("Background") in packed.notices, "and is noted: ${packed.notices}")
	}

	@Test
	fun placedArtThePackWouldLoseStillRefusesIt() {
		val model = modelOf()

		val refused = assertIs<PackAroundOutcome.Refused>(packNewTilesAround(model, setOf(newId), decoder(placedDecodes = false), false, emptyList()))

		assertEquals(listOf("Placed"), refused.refusals.map { refusal -> refusal.tileName })
	}

	/**
	 * The document: a placed tile, a new one, one an earlier pack left unplaced, and a background wider
	 * than any page, each under a drawable.
	 *
	 * @return PuppetModel The model.
	 */
	private fun modelOf(): PuppetModel {
		val tiles =
			listOf(
				AtlasTile(placedId, "Placed", 16, 16, placement = placedSpot),
				AtlasTile(newId, "New", 16, 16),
				AtlasTile(leftOutId, "Left Out", 16, 16),
				AtlasTile(backgroundId, "Background", MAX_IMPORT_PAGE_SIZE + 8, 1),
			)
		val drawables = tiles.map { tile -> quadOver(tile) }

		return PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = drawables.map { drawable -> OrgChild.Drawable(drawable.id) },
			rootPartId = null,
			atlas = PuppetAtlas(pages = listOf(AtlasPage(64, 64)), tiles = tiles),
		)
	}

	/**
	 * A drawable whose quad covers its whole tile.
	 *
	 * @param AtlasTile tile The tile.
	 * @return Drawable The drawable.
	 */
	private fun quadOver(tile: AtlasTile): Drawable {
		val width = tile.width.toFloat()
		val height = tile.height.toFloat()

		return Drawable(
			id = DrawableId(tile.id.raw),
			name = tile.name,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh =
				DrawableMesh.withLocalEqualToCanvas(
					floatArrayOf(0f, 0f, width, 0f, width, height, 0f, height),
					floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f),
					intArrayOf(0, 1, 2, 0, 2, 3),
				),
			geometryGrid = null,
			atlasTileId = tile.id,
		)
	}

	/**
	 * Fully opaque art for every tile, the placed one's withheld when [placedDecodes] is false.
	 *
	 * @param Boolean placedDecodes Whether the placed tile's art decodes.
	 * @return Function The decoder.
	 */
	private fun decoder(placedDecodes: Boolean): (AtlasTileId) -> DecodedImage? =
		{ tileId ->
			when (tileId) {
				placedId -> if (placedDecodes) opaque(16, 16) else null
				backgroundId -> opaque(MAX_IMPORT_PAGE_SIZE + 8, 1)
				else -> opaque(16, 16)
			}
		}

	/**
	 * A fully opaque raster.
	 *
	 * @param Int width  The width.
	 * @param Int height The height.
	 * @return DecodedImage The raster.
	 */
	private fun opaque(width: Int, height: Int): DecodedImage = DecodedImage(ByteArray(width * height * 4) { 0xFF.toByte() }, width, height)
}