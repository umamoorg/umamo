package org.umamo.ui.model

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.toPixelMap
import org.umamo.render.DecodedImage
import org.umamo.render.SourceArtRasters
import org.umamo.runtime.model.AtlasTileId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * The Sources space's tile previews: fitted into their square, cached per tile, and refitted when the
 * store's pixels for a tile change under the same id.
 */
class SourceTileThumbnailsTest {
	private val tile = AtlasTileId("tile")

	/**
	 * A raster of one opaque color.
	 *
	 * @param Int   width  The raster width.
	 * @param Int   height The raster height.
	 * @param Color color  The fill.
	 * @return DecodedImage The raster.
	 */
	private fun solid(width: Int, height: Int, color: Color): DecodedImage {
		val rgba = ByteArray(width * height * 4)
		val argb = color.toArgb()
		for (pixelIndex in 0 until width * height) {
			rgba[pixelIndex * 4] = (argb shr 16).toByte()
			rgba[pixelIndex * 4 + 1] = (argb shr 8).toByte()
			rgba[pixelIndex * 4 + 2] = argb.toByte()
			rgba[pixelIndex * 4 + 3] = (argb ushr 24).toByte()
		}
		return DecodedImage(rgba, width, height)
	}

	/** A tile the store cannot decode previews nothing. */
	@Test
	fun aTileWithNoPixelsHasNoPreview() {
		val thumbnails = SourceTileThumbnails(SourceArtRasters { null })
		assertNull(thumbnails.thumbnailFor(tile))
	}

	/** A wide layer fills the square's width, centered, with transparent bands above and below. */
	@Test
	fun aPreviewIsFittedIntoItsSquare() {
		val thumbnails = SourceTileThumbnails(SourceArtRasters { solid(300, 150, Color.Red) })
		val preview = assertNotNull(thumbnails.thumbnailFor(tile))
		assertEquals(128, preview.width)
		assertEquals(128, preview.height)
		val pixels = preview.toPixelMap()
		assertEquals(Color.Red.toArgb(), pixels[64, 64].toArgb(), "the art sits in the middle")
		assertEquals(0f, pixels[64, 0].alpha, "a wide layer leaves the top band transparent")
	}

	/** Asking again for an unchanged tile hands back the same bitmap rather than fitting it twice. */
	@Test
	fun aRepeatRequestReusesThePreview() {
		val thumbnails = SourceTileThumbnails(SourceArtRasters { solid(8, 8, Color.Red) })
		assertSame(thumbnails.thumbnailFor(tile), thumbnails.thumbnailFor(tile))
	}

	/**
	 * A reload, its undo, and a second reload mint one tile id twice with different pixels, and the store
	 * hands out the second.  The preview has to follow it rather than keep the first reload's art.
	 */
	@Test
	fun aReplacedRasterIsRefitted() {
		val rasters = SourceArtRasters { solid(8, 8, Color.Red) }
		val thumbnails = SourceTileThumbnails(rasters)
		val first = assertNotNull(thumbnails.thumbnailFor(tile))
		rasters.addDecoded(mapOf(tile to solid(8, 8, Color.Blue)))
		val second = assertNotNull(thumbnails.thumbnailFor(tile))
		assertNotSame(first, second)
		assertEquals(Color.Blue.toArgb(), second.toPixelMap()[64, 64].toArgb())
		assertSame(second, thumbnails.thumbnailFor(tile), "the refitted preview is cached in turn")
	}
}