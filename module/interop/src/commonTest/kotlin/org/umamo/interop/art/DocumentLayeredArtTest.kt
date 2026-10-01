package org.umamo.interop.art

import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ArtSource
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetAtlas
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.SourceLayerRef
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the art a document holds for a listed file (docs/plan/uma-format.md D40): one layer per present row in
 * the file's order, a bound row reading as its first bound tile's pixels at the row's position, an unbound or
 * erased row reading as a layer with no pixels, a lost row left out, the key strength its binding carries or
 * its shape suggests, and no decode until a layer's pixels are asked for.
 */
class DocumentLayeredArtTest {
	private val sourceId = ArtSourceId("art-0")

	/**
	 * An inventory row of a 3 x 2 layer at [left], 7.
	 *
	 * @param String  key     The row's key.
	 * @param Int     left    Its canvas x.
	 * @param Boolean present Whether the file still lists it.
	 * @param Boolean empty   Whether the file erased it.
	 * @return ArtSourceLayer The row.
	 */
	private fun row(key: String, left: Int, present: Boolean = true, empty: Boolean = false): ArtSourceLayer = ArtSourceLayer(key, "Layer $key", "Head", left, 7, 3, 2, true, present = present, empty = empty)

	/**
	 * A 4 x 5 tile bound to [key].
	 *
	 * @param String  id     The tile's id.
	 * @param String  key    The row it binds.
	 * @param Boolean stable Whether the binding's key is format-minted.
	 * @return AtlasTile The tile.
	 */
	private fun tile(id: String, key: String, stable: Boolean = true): AtlasTile = AtlasTile(AtlasTileId(id), id, 4, 5, source = SourceLayerRef(sourceId, key, stable))

	/** A model of [rows] and [tiles] on a 200 x 100 canvas. */
	private fun model(rows: List<ArtSourceLayer>, tiles: List<AtlasTile>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = 200f,
			canvasHeight = 100f,
			atlas = PuppetAtlas(pages = emptyList(), tiles = tiles),
			sources = listOf(ArtSource(sourceId, "hero.psd", null, "psd", rows)),
		)

	@Test
	fun rowsReadAsTheirTilesOrAsNothing() {
		val rows = listOf(row("lyid:1", 10), row("name:Loose", 20), row("lyid:3", 30, present = false), row("lyid:4", 40, empty = true))
		val pixels = LayerRaster(4, 5, ByteArray(4 * 5 * 4) { index -> index.toByte() })
		val asked = ArrayList<AtlasTileId>()
		val art =
			documentSourceArtOf(model(rows, listOf(tile("first", "lyid:1"), tile("second", "lyid:1"), tile("lost", "lyid:3"), tile("erased", "lyid:4"))), sourceId) { tileId ->
				asked.add(tileId)
				pixels
			}!!

		assertEquals(listOf("lyid:1", "name:Loose", "lyid:4"), art.layers.map { layer -> layer.id.raw }, "present rows, in the file's order")
		assertEquals(listOf(SourceLayerKind.Raster, SourceLayerKind.Unknown, SourceLayerKind.Unknown), art.layers.map { layer -> layer.kind })
		assertEquals(LayerBounds(10, 7, 4, 5), art.layers[0].bounds, "at the row's position, with the tile's size")
		assertEquals(LayerBounds(20, 7, 3, 2), art.layers[1].bounds, "an unbound row keeps the size it was recorded at")
		assertEquals(listOf(true, false, true), art.layers.map { layer -> layer.idIsStable }, "the binding's strength, else the key's shape")
		assertEquals("Head", art.layers[0].groupPath)
		assertEquals(200 to 100, art.widthPx to art.heightPx, "on the document canvas")
		assertTrue(asked.isEmpty(), "nothing decodes until a layer's pixels are asked for")

		assertContentEquals(pixels.rgba, art.layers[0].raster.rgba)
		assertEquals(listOf(AtlasTileId("first")), asked, "the first tile in atlas order stands for a doubly bound row")
		assertEquals(0, art.layers[1].raster.rgba.count { byte -> byte != 0.toByte() }, "a row with no tile has nothing to give")
	}

	@Test
	fun aFileTheModelDoesNotListOrWithNoPresentRowReadsNull() {
		assertNull(documentSourceArtOf(model(listOf(row("lyid:1", 0)), emptyList()), ArtSourceId("art-9")) { null })
		assertNull(documentSourceArtOf(model(listOf(row("lyid:1", 0, present = false)), emptyList()), sourceId) { null })
	}
}