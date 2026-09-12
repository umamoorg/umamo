package org.umamo.ui.model

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.render.DecodedImage

/**
 * The decoded wrapper of every layer raster of some read art, minted once per raster: a re-run of the
 * operation that read the art hands the raster store the same instances again, and the renderer's
 * texture cache and the viewport's freshness test key on that identity.
 *
 * LayerRaster is a plain class, so the map keys by identity - which is the point: the wrapper of one
 * raster is one object for the request's life.  Built eagerly so the off-thread passes only read.
 *
 * @param List<SourceLayer> layers The layers whose rasters are wrapped.
 */
internal class DecodedLayerRasters(layers: List<SourceLayer>) {
	private val decodedByRaster: Map<LayerRaster, DecodedImage> =
		layers.associate { layer -> layer.raster to DecodedImage(layer.raster.rgba, layer.raster.width, layer.raster.height) }

	/**
	 * The decoded wrapper of one of the layers' rasters, or a fresh wrapper for a raster the art did
	 * not carry.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call for a known raster.
	 */
	fun decodedFor(raster: LayerRaster): DecodedImage = decodedByRaster[raster] ?: DecodedImage(raster.rgba, raster.width, raster.height)
}