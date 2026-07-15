package org.umamo.format.png

import org.umamo.format.raster.ByteBuilder
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [PngCodec] with hand-built PNGs (via the codec's own chunk writer), pinning every colour
 * type, bit depth, tRNS transparency, and sub-byte packing to a known-exact expected RGBA.
 *
 * Lives in commonTest so it runs on every target: the codec is pure Kotlin apart from the zlib bridge,
 * and these cases are what prove a new platform decodes identically.  The complementary strategy —
 * decoding PNGs written by javax.imageio, an independent reference encoder — necessarily needs the
 * JVM, and lives in PngImageIoParityTest.
 */
class PngCodecTest {
	/** Builds a ByteArray from int literals (so values > 127 read cleanly). */
	private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

	/**
	 * Assembles a minimal, non-interlaced PNG (filter type 0 on every row) from raw sample bytes,
	 * reusing the codec's own internal chunk framing so the test controls the exact bytes.
	 *
	 * @param Int width         Image width.
	 * @param Int height        Image height.
	 * @param Int bitDepth      Bits per sample.
	 * @param Int colorType     PNG colour type.
	 * @param ByteArray samples The raw, row-major sample bytes (height * rowBytes).
	 * @param Int rowBytes      Bytes per scanline (excluding the filter byte).
	 * @param ByteArray? palette The PLTE bytes, or null.
	 * @param ByteArray? trns    The tRNS bytes, or null.
	 * @return ByteArray The complete `.png` file.
	 */
	private fun buildPng(width: Int, height: Int, bitDepth: Int, colorType: Int, samples: ByteArray, rowBytes: Int, palette: ByteArray? = null, trns: ByteArray? = null): ByteArray {
		val filtered = ByteBuilder()
		for (rowIndex in 0 until height) {
			filtered.writeByte(0) // filter type None
			filtered.writeBytes(samples, rowIndex * rowBytes, rowBytes)
		}
		val ihdr = ByteBuilder()
		writeU32BE(ihdr, width)
		writeU32BE(ihdr, height)
		ihdr.writeByte(bitDepth)
		ihdr.writeByte(colorType)
		ihdr.writeByte(0) // compression
		ihdr.writeByte(0) // filter method
		ihdr.writeByte(0) // interlace: none

		val out = ByteBuilder()
		out.writeBytes(PNG_SIGNATURE)
		writeChunk(out, "IHDR", ihdr.toByteArray())
		if (palette != null) {
			writeChunk(out, "PLTE", palette)
		}
		if (trns != null) {
			writeChunk(out, "tRNS", trns)
		}
		writeChunk(out, "IDAT", deflateIdat(filtered.toByteArray()))
		writeChunk(out, "IEND", ByteArray(0))
		return out.toByteArray()
	}

	@Test
	fun writeThenReadRoundTripsRgba() {
		val source =
			RasterImage(
				width = 2,
				height = 2,
				rgba = bytes(10, 20, 30, 40, 50, 60, 70, 255, 80, 90, 100, 128, 200, 210, 220, 64),
			)
		val decoded = PngCodec.read(PngCodec.write(source))
		assertEquals(2, decoded.width)
		assertEquals(2, decoded.height)
		assertContentEquals(source.rgba, decoded.rgba, "RGBA survives write -> read")
	}

	@Test
	fun writeThenReadRoundTripsOddWidth() {
		// A width that is not a power of two exercises the scanline byte math on both ends.
		val width = 5
		val height = 3
		val rgba = ByteArray(width * height * 4) { (it * 7 % 256).toByte() }
		val decoded = PngCodec.read(PngCodec.write(RasterImage(width, height, rgba)))
		assertContentEquals(rgba, decoded.rgba, "odd-width RGBA survives write -> read")
	}

	@Test
	fun decodesGrayscale8WithTransparentKey() {
		// tRNS gray key = 255, so the single 255-valued pixel decodes transparent; the rest opaque.
		val png = buildPng(width = 3, height = 2, bitDepth = 8, colorType = 0, samples = bytes(10, 20, 200, 30, 255, 40), rowBytes = 3, trns = bytes(0, 255))
		val decoded = PngCodec.read(png)
		val expected = bytes(10, 10, 10, 255, 20, 20, 20, 255, 200, 200, 200, 255, 30, 30, 30, 255, 255, 255, 255, 0, 40, 40, 40, 255)
		assertContentEquals(expected, decoded.rgba)
	}

	@Test
	fun decodesPalette8WithTrns() {
		val palette = bytes(255, 0, 0, 0, 255, 0, 0, 0, 255, 128, 128, 128)
		val trns = bytes(255, 128) // idx0 opaque, idx1 half; idx2/idx3 default opaque
		val png = buildPng(width = 2, height = 2, bitDepth = 8, colorType = 3, samples = bytes(0, 1, 2, 3), rowBytes = 2, palette = palette, trns = trns)
		val decoded = PngCodec.read(png)
		val expected = bytes(255, 0, 0, 255, 0, 255, 0, 128, 0, 0, 255, 255, 128, 128, 128, 255)
		assertContentEquals(expected, decoded.rgba)
	}

	@Test
	fun decodesRgb8WithTransparentKey() {
		val trns = bytes(0, 200, 0, 100, 0, 50) // key (200,100,50) as three 16-bit big-endian samples
		val png = buildPng(width = 2, height = 1, bitDepth = 8, colorType = 2, samples = bytes(10, 20, 30, 200, 100, 50), rowBytes = 6, trns = trns)
		val decoded = PngCodec.read(png)
		assertContentEquals(bytes(10, 20, 30, 255, 200, 100, 50, 0), decoded.rgba)
	}

	@Test
	fun decodes16BitGrayscaleKeepingHighByte() {
		val png = buildPng(width = 2, height = 1, bitDepth = 16, colorType = 0, samples = bytes(0x12, 0x34, 0xAB, 0xCD), rowBytes = 4)
		val decoded = PngCodec.read(png)
		assertContentEquals(bytes(0x12, 0x12, 0x12, 255, 0xAB, 0xAB, 0xAB, 255), decoded.rgba)
	}

	@Test
	fun decodesOneBitGrayscaleScaledToFullRange() {
		// 0b10110001 MSB-first: 1,0,1,1,0,0,0,1 -> 255,0,255,255,0,0,0,255.
		val png = buildPng(width = 8, height = 1, bitDepth = 1, colorType = 0, samples = bytes(0xB1), rowBytes = 1)
		val decoded = PngCodec.read(png)
		val expected =
			bytes(
				255,
				255,
				255,
				255,
				0,
				0,
				0,
				255,
				255,
				255,
				255,
				255,
				255,
				255,
				255,
				255,
				0,
				0,
				0,
				255,
				0,
				0,
				0,
				255,
				0,
				0,
				0,
				255,
				255,
				255,
				255,
				255,
			)
		assertContentEquals(expected, decoded.rgba)
	}

	@Test
	fun decodedSignatureIsRejectedWhenCorrupt() {
		val png = PngCodec.write(RasterImage(1, 1, bytes(1, 2, 3, 4)))
		assertTrue(PngCodec.matches(png), "valid PNG matches")
		png[1] = 0
		assertTrue(!PngCodec.matches(png), "corrupt signature does not match")
	}
}
