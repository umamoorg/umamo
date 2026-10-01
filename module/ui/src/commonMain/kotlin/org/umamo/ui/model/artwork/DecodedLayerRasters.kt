package org.umamo.ui.model.artwork

import org.umamo.format.art.LayerRaster
import org.umamo.format.art.SourceLayer
import org.umamo.render.DecodedImage

/**
 * The decoded wrapper of every layer raster of some read art, minted once per raster: a re-run of the
 * operation that read the art hands the raster store the same instances again, and the renderer's
 * texture cache and the viewport's freshness test key on that identity.
 *
 * LayerRaster is a plain class, so the map keys by identity - which is the point: the wrapper of one
 * raster is one object for the request's life.  Built on the first lookup rather than with the request: a
 * layer's raster can decode on first use (a CMO3's layers, the document's own tiles), and a request whose
 * plan pulls nothing should decode nothing.  The lazy value is synchronized, so the off-thread passes that
 * share it all see the one map.
 *
 * @param List<SourceLayer> layers The layers whose rasters are wrapped.
 */
internal class DecodedLayerRasters(layers: List<SourceLayer>) {
	private val decodedByRaster: Map<LayerRaster, DecodedImage> by lazy {
		layers.associate { layer -> layer.raster to DecodedImage(layer.raster.rgba, layer.raster.width, layer.raster.height) }
	}

	/**
	 * The decoded wrapper of one of the layers' rasters, or a fresh wrapper for a raster the art did
	 * not carry.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call for a known raster.
	 */
	fun decodedFor(raster: LayerRaster): DecodedImage = decodedByRaster[raster] ?: DecodedImage(raster.rgba, raster.width, raster.height)
}