package org.umamo.interop.cmo3

import org.umamo.format.cmo3.model.custom.CWritableImage
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.fittedInto
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertSame

/**
 * The icon builders: a drawable's patch is its uv box on the art, and an icon is a CImageIcon over
 * a square PNG entry holding the fit.
 */
class Cmo3IconsTest {
	private fun gradient(width: Int, height: Int): RasterImage = RasterImage(width, height, ByteArray(width * height * 4) { index -> (index * 3 + 1).toByte() })

	@Test
	fun thePatchIsTheUvBoxClampedToTheArt() {
		val art = gradient(8, 8)
		// u in [0.25, 0.75), v in [0.5, 1.0) -> columns 2..5, rows 4..7.
		val patch = Cmo3Icons.patchOf(art, floatArrayOf(0.25f, 0.5f, 0.75f, 0.5f, 0.75f, 1f, 0.25f, 1f))
		assertEquals(4 to 4, patch.width to patch.height)
		for (row in 0 until 4) {
			val expectedStart = ((4 + row) * 8 + 2) * 4
			assertContentEquals(art.rgba.copyOfRange(expectedStart, expectedStart + 16), patch.rgba.copyOfRange(row * 16, row * 16 + 16), "row $row")
		}
		assertSame(art, Cmo3Icons.patchOf(art, null), "no uvs show the whole art")
		val sliver = Cmo3Icons.patchOf(art, floatArrayOf(0.3f, 0.3f, 0.3f, 0.3f))
		assertEquals(1 to 1, sliver.width to sliver.height, "a degenerate box is at least one pixel")
		val outside = Cmo3Icons.patchOf(art, floatArrayOf(-1f, -1f, 2f, 2f))
		assertEquals(8 to 8, outside.width to outside.height, "a box past the art clamps to it")
	}

	@Test
	fun anIconIsASquarePngEntryHoldingTheFit() {
		val art = gradient(6, 3)
		val entries = ArrayList<Cmo3FreshFile.PngEntry>()
		val icon = Cmo3Icons.iconOf(art, 16, "image_9.png", entries)
		val image = icon.image as CWritableImage
		assertEquals(16 to 16, image.width to image.height)
		assertEquals("INT_ARGB", image.type)
		assertEquals("image_9.png", Cmo3Icons.archivePathOf(icon))
		val entry = entries.single()
		assertEquals("image_9.png", entry.path)
		val decoded = PngCodec.read(entry.pngBytes)
		assertEquals(16 to 16, decoded.width to decoded.height)
		assertContentEquals(art.fittedInto(16).rgba, decoded.rgba, "the entry holds the fit")
		assertContentEquals(entry.pngBytes, Cmo3Icons.iconPngOf(art, 16), "the in-place form encodes the same bytes")
	}
}