package org.umamo.interop.art

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer
import org.umamo.format.art.SourceLayerKind
import org.umamo.runtime.model.ArtSourceId
import org.umamo.runtime.model.ArtSourceLayer
import org.umamo.runtime.model.AtlasTile
import org.umamo.runtime.model.AtlasTileId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.layerKeyLooksStable
import kotlin.math.roundToInt

/*
 * The art a document holds for one of its listed files, as source art: what Relink and Match read when the
 * file is not on disk and the document has no CMO3 decomposition to fall back to (docs/plan/uma-format.md
 * D40) - a reopened CMO3-origin `.uma`, or any document whose artwork file has gone.  Each tile keeps the
 * pixels its layer had when it was last read, so a layer some tile binds reads as that tile's art; a layer
 * no tile binds has no pixels anywhere in the document and reads as a layer with none, which the planners
 * treat as nothing to pull.  Like a CMO3's decomposition it is the art as last read, never the file as it is
 * now, so a reload never reads it.
 */

/**
 * One inventory row as a source layer.  Its pixels are its bound tile's, decoded on first use - a relink needs
 * one layer's pixels, not the whole file's.
 *
 * @property ArtSourceLayer row         The inventory row.
 * @property Int            order       The row's place in the file's layer order.
 * @property AtlasTile?     boundTile   The tile whose pixels the row reads as, or null when it has none to give.
 * @property Boolean        idIsStable  Whether the row's key survives a rename in the art program.
 * @property Function       rasterOf    The document's pixels for a tile.
 */
private class DocumentSourceLayer(
	private val row: ArtSourceLayer,
	override val order: Int,
	private val boundTile: AtlasTile?,
	override val idIsStable: Boolean,
	private val rasterOf: (AtlasTileId) -> LayerRaster?,
) : SourceLayer {
	override val id: LayerId = LayerId(row.key)
	override val name: String = row.name
	override val groupPath: String = row.groupPath
	override val kind: SourceLayerKind = if (boundTile != null) SourceLayerKind.Raster else SourceLayerKind.Unknown
	override val visible: Boolean = row.visible

	// The bound tile's size is its raster's, so the bounds are known without decoding it; a row with no tile
	// keeps the size the inventory recorded.
	override val bounds: LayerBounds = LayerBounds(row.left, row.top, boundTile?.width ?: row.width, boundTile?.height ?: row.height)
	override val opacity: Float = 1f
	override val clipped: Boolean = false
	override val blend: LayerBlend = LayerBlend.Normal
	override val raster: LayerRaster by lazy {
		// A tile the store cannot decode reads as fully transparent at the row's bounds, which the planners
		// treat as a layer with no art to give.
		boundTile?.let { tile -> rasterOf(tile.id) } ?: LayerRaster(bounds.width, bounds.height, ByteArray(bounds.width * bounds.height * 4))
	}
}

/**
 * The held art as a source document, on the document canvas.
 *
 * @property List layers   The rows, in the file's layer order.
 * @property Int  widthPx  The canvas width.
 * @property Int  heightPx The canvas height.
 */
private class DocumentLayeredArt(
	override val layers: List<SourceLayer>,
	override val widthPx: Int,
	override val heightPx: Int,
) : SourceArt

/**
 * The file [sourceId] names as the document holds it: one source layer per present inventory row, in the
 * file's layer order, each reading as the pixels of the first tile in atlas order bound to it; a row no tile
 * binds, and a row the file erased to nothing, has no pixels to give.  The rows already sit on the document
 * canvas - an inventory row records the layer where the file's offset put it - so the art is never placed
 * again.  A row the file lost is left out: read back as present it would clear its own review.
 *
 * @param PuppetModel model    The model, with its current tiles (a reloaded tile's pixels included).
 * @param ArtSourceId sourceId The file to read.
 * @param Function    rasterOf The document's pixels for a tile, or null when it holds none.
 * @return SourceArt? The held art, or null when the model lists no such file or the file has no present row.
 */
fun documentSourceArtOf(model: PuppetModel, sourceId: ArtSourceId, rasterOf: (AtlasTileId) -> LayerRaster?): SourceArt? {
	val source = model.sources.firstOrNull { candidate -> candidate.id == sourceId } ?: return null
	val presentRows = source.layers.filter { row -> row.present }
	if (presentRows.isEmpty()) {
		return null
	}
	val boundTileByKey = LinkedHashMap<String, AtlasTile>()
	for (tile in model.atlas.tiles) {
		val ref = tile.source?.takeIf { binding -> binding.sourceId == sourceId } ?: continue
		if (ref.layerKey !in boundTileByKey) {
			boundTileByKey[ref.layerKey] = tile
		}
	}
	val layers =
		presentRows.mapIndexed { order, row ->
			val boundTile = boundTileByKey[row.key]?.takeUnless { row.empty }
			val stable = boundTile?.source?.stableKey ?: layerKeyLooksStable(row.key)
			DocumentSourceLayer(row, order, boundTile, stable, rasterOf)
		}
	return DocumentLayeredArt(layers, model.canvasWidth.roundToInt(), model.canvasHeight.roundToInt())
}