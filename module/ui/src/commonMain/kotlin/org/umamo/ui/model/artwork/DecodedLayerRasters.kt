package org.umamo.ui.model.artwork

import kotlinx.atomicfu.locks.SynchronizedObject
import kotlinx.atomicfu.locks.synchronized
import org.umamo.format.art.LayerRaster
import org.umamo.render.DecodedImage

/**
 * The decoded wrapper of each layer raster an operation pulls, minted once per raster: a re-run of the
 * operation that read the art hands the raster store the same instances again, and the renderer's
 * texture cache and the viewport's freshness test key on that identity.
 *
 * LayerRaster is a plain class, so the map keys by identity - which is the point: the wrapper of one
 * raster is one object for the request's life.  Filled one raster at a time, as each is asked for: a
 * layer's raster can decode on first use (a CMO3's layers, the document's own tiles), so a request whose
 * plan pulls one layer decodes one layer, and one that pulls nothing decodes nothing.  The lock is for the
 * off-thread passes that share a request: they run on the default dispatcher, and two adjustments of the
 * strip can overlap.
 */
internal class DecodedLayerRasters {
	private val lock = SynchronizedObject()
	private val decodedByRaster = HashMap<LayerRaster, DecodedImage>()

	/**
	 * The decoded wrapper of a layer's raster.
	 *
	 * @param LayerRaster raster The layer's pixels.
	 * @return DecodedImage The wrapper, the same instance on every call for one raster.
	 */
	fun decodedFor(raster: LayerRaster): DecodedImage =
		synchronized(lock) {
			decodedByRaster.getOrPut(raster) { DecodedImage(raster.rgba, raster.width, raster.height) }
		}
}