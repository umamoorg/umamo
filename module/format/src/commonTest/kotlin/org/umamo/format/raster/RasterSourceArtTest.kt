package org.umamo.format.raster

import org.umamo.format.art.LayerBlend
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Confirms [rasterToSourceArt] wraps a flat [RasterImage] as a single full-canvas source layer, so a
 * flat raster rides the same SourceArt ingest path as the layered readers.
 */
class RasterSourceArtTest {
	@Test
	fun wrapsRasterAsOneFullCanvasLayer() {
		val rgba = ByteArray(3 * 2 * 4) { it.toByte() }
		val image = RasterImage(width = 3, height = 2, rgba = rgba)

		val art = rasterToSourceArt(image, name = "hero.png")

		assertEquals(3, art.widthPx, "canvas width")
		assertEquals(2, art.heightPx, "canvas height")
		assertTrue(art.groups.isEmpty(), "a flat image has no folders")
		assertEquals(1, art.layers.size, "exactly one layer")

		val layer = art.layers.single()
		assertEquals("hero.png", layer.name, "layer name")
		assertEquals("hero.png", layer.id.raw, "provisional layer id is the file name")
		assertEquals(0, layer.order, "single layer is top-most")
		assertEquals("", layer.groupPath, "no enclosing folder")
		assertEquals(LayerBlend.Normal, layer.blend, "normal blend")
		assertEquals(1f, layer.opacity, "full opacity")
		assertTrue(!layer.clipped, "not a clipping layer")
		assertEquals(0, layer.bounds.left, "bounds origin x")
		assertEquals(0, layer.bounds.top, "bounds origin y")
		assertEquals(3, layer.bounds.width, "bounds width covers the canvas")
		assertEquals(2, layer.bounds.height, "bounds height covers the canvas")
		assertEquals(3, layer.raster.width, "raster width")
		assertEquals(2, layer.raster.height, "raster height")
		assertSame(rgba, layer.raster.rgba, "pixels are handed through by reference")
	}
}
