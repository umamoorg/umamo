package org.umamo.ui.model

import androidx.compose.ui.graphics.ImageBitmap
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.fittedInto
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.AtlasTileId
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap

/**
 * The square edge, in pixels, a tile's preview is fitted into: the size [DrawableThumbnailer] composites a
 * part to, so a Sources layer and an Outliner part read at the same sharpness in the same 120dp slot.
 */
private const val TILE_PREVIEW_EDGE = 128

/**
 * Small previews of a document's source art, one per atlas tile: the art a layer was read as, before any
 * packing, for the Sources space's hover.
 *
 * The pixels come from the document's [SourceArtRasters], not from the atlas pages: a page crop would show
 * the art packed, possibly quarter-turned, and cut to one drawable's mesh, where a layer's preview should
 * show the layer.  Each is fitted into a transparent square by the premultiplied area average every CMO3
 * layer icon is drawn with, so an edge stays clean at any reduction.
 *
 * A preview is valid only while the store hands back the same raster instance for its tile.  A reload,
 * its undo, and a second reload mint the same tile id twice with different pixels, and the store's
 * addDecoded replaces the first, so a cache keyed on the id alone would show the first reload's art.
 *
 * CALLER-CONFINED like [SourceArtRasters.rasterFor], which it calls: every call must come from the UI
 * thread.  Sharing that store's decode means a later UV editor view of the same layer decodes nothing.
 *
 * @property SourceArtRasters rasters The document's source-art pixels.
 */
class SourceTileThumbnails(
	private val rasters: SourceArtRasters,
) {
	/** A fitted preview and the raster it was fitted from, which decides whether it is still current. */
	private class Entry(val raster: DecodedImage, val bitmap: ImageBitmap)

	private val cache = HashMap<AtlasTileId, Entry>()

	/**
	 * The preview of a tile's source art, fitted into a [TILE_PREVIEW_EDGE] square, or null when the tile
	 * has no pixels the store can decode.
	 *
	 * @param AtlasTileId tileId The tile to preview.
	 * @return ImageBitmap? The preview, or null.
	 */
	fun thumbnailFor(tileId: AtlasTileId): ImageBitmap? {
		val raster = rasters.rasterFor(tileId) ?: return null
		cache[tileId]?.let { entry ->
			if (entry.raster === raster) {
				return entry.bitmap
			}
		}
		val fitted = RasterImage(raster.width, raster.height, raster.rgba).fittedInto(TILE_PREVIEW_EDGE)
		val bitmap = rgbaToImageBitmap(fitted.rgba, fitted.width, fitted.height, RgbaAlphaType.Straight)
		cache[tileId] = Entry(raster, bitmap)
		return bitmap
	}
}