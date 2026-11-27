package org.umamo.ui.model.artwork

import org.umamo.format.art.LayerRaster
import kotlin.test.Test
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/** Pins the memo's contract: one wrapper per raster for the request's life, over the raster's own pixels. */
class DecodedLayerRastersTest {
	@Test
	fun oneRasterGetsOneWrapperAndAnotherRasterItsOwn() {
		val rasters = DecodedLayerRasters()
		val first = LayerRaster(2, 1, ByteArray(8))
		val second = LayerRaster(2, 1, ByteArray(8))
		val wrapped = rasters.decodedFor(first)
		assertSame(wrapped, rasters.decodedFor(first), "the same instance on every call")
		assertSame(first.rgba, wrapped.rgba, "over the raster's own pixels, not a copy")
		assertNotSame(wrapped, rasters.decodedFor(second), "two rasters with equal pixels are two rasters")
	}
}