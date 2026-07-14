package org.umamo.format.bmp

import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import java.io.ByteArrayOutputStream
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [BmpCodec] without a committed corpus: the 32-bit BITMAPV4HEADER writer round-trips
 * straight alpha losslessly, a hand-built 24-bit BI_RGB file pins the read path (BGR order, bottom-up
 * rows, 4-byte row padding), and a BMP -> PNG cross-check confirms both codecs share the neutral
 * RGBA representation.
 */
class BmpCodecTest {
	private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

	private fun writeU16LE(out: ByteArrayOutputStream, value: Int) {
		out.write(value and 0xFF)
		out.write((value ushr 8) and 0xFF)
	}

	private fun writeU32LE(out: ByteArrayOutputStream, value: Int) {
		out.write(value and 0xFF)
		out.write((value ushr 8) and 0xFF)
		out.write((value ushr 16) and 0xFF)
		out.write((value ushr 24) and 0xFF)
	}

	/**
	 * Builds an uncompressed 24-bit BI_RGB BMP (bottom-up, 4-byte-padded rows) from top-first RGB.
	 *
	 * @param Int width            Image width.
	 * @param Int height           Image height.
	 * @param ByteArray rgbTopFirst Row-major R,G,B samples, top row first (width * height * 3).
	 * @return ByteArray The complete `.bmp` file.
	 */
	private fun buildBmp24(width: Int, height: Int, rgbTopFirst: ByteArray): ByteArray {
		val rowStride = ((24 * width + 31) / 32) * 4
		val pixelData = ByteArray(rowStride * height)
		for (imageRow in 0 until height) {
			val fileRow = height - 1 - imageRow // bottom-up storage
			var destination = fileRow * rowStride
			for (column in 0 until width) {
				val source = (imageRow * width + column) * 3
				pixelData[destination++] = rgbTopFirst[source + 2] // B
				pixelData[destination++] = rgbTopFirst[source + 1] // G
				pixelData[destination++] = rgbTopFirst[source] // R
			}
		}
		val offBits = 14 + 40
		val out = ByteArrayOutputStream()
		out.write(0x42)
		out.write(0x4D)
		writeU32LE(out, offBits + pixelData.size)
		writeU16LE(out, 0)
		writeU16LE(out, 0)
		writeU32LE(out, offBits)
		writeU32LE(out, 40) // BITMAPINFOHEADER
		writeU32LE(out, width)
		writeU32LE(out, height)
		writeU16LE(out, 1)
		writeU16LE(out, 24)
		writeU32LE(out, 0) // BI_RGB
		writeU32LE(out, pixelData.size)
		writeU32LE(out, 2835)
		writeU32LE(out, 2835)
		writeU32LE(out, 0)
		writeU32LE(out, 0)
		out.write(pixelData)
		return out.toByteArray()
	}

	@Test
	fun writeThenReadRoundTripsRgbaWithAlpha() {
		val source =
			RasterImage(
				width = 2,
				height = 2,
				rgba = bytes(10, 20, 30, 40, 50, 60, 70, 255, 80, 90, 100, 128, 200, 210, 220, 64),
			)
		val decoded = BmpCodec.read(BmpCodec.write(source))
		assertEquals(2, decoded.width)
		assertEquals(2, decoded.height)
		assertContentEquals(source.rgba, decoded.rgba, "straight alpha survives the 32-bit V4 round-trip")
	}

	@Test
	fun readsHandBuilt24BitBmpAsOpaqueRgba() {
		val rgb = bytes(255, 0, 0, 0, 255, 0, 0, 0, 255, 255, 255, 0)
		val decoded = BmpCodec.read(buildBmp24(width = 2, height = 2, rgbTopFirst = rgb))
		val expected = bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 255, 255, 0, 255)
		assertContentEquals(expected, decoded.rgba)
	}

	@Test
	fun bmpAndPngShareTheNeutralRepresentation() {
		val width = 6
		val height = 4
		val rgba = ByteArray(width * height * 4) { (it * 5 % 256).toByte() }
		val throughBmp = BmpCodec.read(BmpCodec.write(RasterImage(width, height, rgba)))
		val throughPng = PngCodec.read(PngCodec.write(throughBmp))
		assertContentEquals(rgba, throughPng.rgba, "RGBA -> BMP -> PNG -> RGBA is lossless")
	}

	@Test
	fun matchesOnlyTheBmSignature() {
		val bmp = BmpCodec.write(RasterImage(1, 1, bytes(1, 2, 3, 4)))
		assertTrue(BmpCodec.matches(bmp), "BM-prefixed bytes match")
		assertTrue(!BmpCodec.matches(bytes(0x00, 0x4D, 1, 2)), "non-BM bytes do not match")
	}
}
