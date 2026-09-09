package org.umamo.ui.document

import org.umamo.format.art.LayerBlend
import org.umamo.format.art.LayerBounds
import org.umamo.format.art.LayerId
import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayer

/** An in-memory source layer of one opaque color at a canvas position, keyed the way a reader keys it. */
internal class InMemoryLayer(
	id: String,
	override val name: String,
	override val order: Int,
	override val bounds: LayerBounds,
	override val raster: LayerRaster,
) : SourceLayer {
	override val id: LayerId = LayerId(id)
	override val groupPath: String = ""
	override val opacity: Float = 1f
	override val clipped: Boolean = false
	override val blend: LayerBlend = LayerBlend.Normal
}

/** An in-memory source document, so a flow test needs no file. */
internal class InMemoryArt(
	override val layers: List<SourceLayer>,
	override val widthPx: Int = 64,
	override val heightPx: Int = 64,
) : SourceArt

/**
 * A raster filled with one opaque color.
 *
 * @param Int  width  The width in pixels.
 * @param Int  height The height in pixels.
 * @param Byte value  The byte every color channel takes.
 * @return LayerRaster The raster.
 */
internal fun solidRaster(width: Int, height: Int, value: Byte): LayerRaster {
	val rgba = ByteArray(width * height * 4)
	for (pixel in 0 until width * height) {
		rgba[pixel * 4] = value
		rgba[pixel * 4 + 1] = value
		rgba[pixel * 4 + 2] = value
		rgba[pixel * 4 + 3] = 0xFF.toByte()
	}
	return LayerRaster(width, height, rgba)
}