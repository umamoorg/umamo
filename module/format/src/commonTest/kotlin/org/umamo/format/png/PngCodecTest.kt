package org.umamo.format.png

import okio.Buffer
import org.umamo.format.raster.RasterImage
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises [PngCodec] with hand-built PNGs (via the codec's own chunk writer), pinning every color
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
	 * @param Int colorType     PNG color type.
	 * @param ByteArray samples The raw, row-major sample bytes (height * rowBytes).
	 * @param Int rowBytes      Bytes per scanline (excluding the filter byte).
	 * @param ByteArray? palette The PLTE bytes, or null.
	 * @param ByteArray? trns    The tRNS bytes, or null.
	 * @param Int compressionMethod The IHDR compression method byte.
	 * @param Function1 idatPieces  Splits the zlib stream into the data of the IDAT chunks to write.
	 * @return ByteArray The complete `.png` file.
	 */
	private fun buildPng(
		width: Int,
		height: Int,
		bitDepth: Int,
		colorType: Int,
		samples: ByteArray,
		rowBytes: Int,
		palette: ByteArray? = null,
		trns: ByteArray? = null,
		compressionMethod: Int = 0,
		idatPieces: (ByteArray) -> List<ByteArray> = { stream -> listOf(stream) },
	): ByteArray {
		val filtered = Buffer()
		for (rowIndex in 0 until height) {
			filtered.writeByte(0) // filter type None
			filtered.write(samples, rowIndex * rowBytes, rowBytes)
		}
		val ihdr = Buffer()
		ihdr.writeInt(width)
		ihdr.writeInt(height)
		ihdr.writeByte(bitDepth)
		ihdr.writeByte(colorType)
		ihdr.writeByte(compressionMethod)
		ihdr.writeByte(0) // filter method
		ihdr.writeByte(0) // interlace: none

		val out = Buffer()
		out.write(PNG_SIGNATURE)
		writeChunk(out, "IHDR", ihdr.readByteArray())
		if (palette != null) {
			writeChunk(out, "PLTE", palette)
		}
		if (trns != null) {
			writeChunk(out, "tRNS", trns)
		}
		for (piece in idatPieces(deflateIdat(filtered.readByteArray()))) {
			writeChunk(out, "IDAT", piece)
		}
		writeChunk(out, "IEND", ByteArray(0))
		return out.readByteArray()
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
	fun decodes16BitGrayscaleByTheSpecLinearEquation() {
		// Every 16-bit value once, 256 per row, big-endian (PNG spec §7.2).
		val samples = ByteArray(65536 * 2)
		for (value in 0 until 65536) {
			samples[value * 2] = (value ushr 8).toByte()
			samples[value * 2 + 1] = value.toByte()
		}
		val png = buildPng(width = 256, height = 256, bitDepth = 16, colorType = 0, samples = samples, rowBytes = 512)
		val decoded = PngCodec.read(png)
		for (value in 0 until 65536) {
			// PNG spec §13.12: floor(value * 255 / 65535 + 0.5), evaluated as one exact integer division.
			val expected = ((2L * value * 255 + 65535) / (2L * 65535)).toInt()
			assertEquals(expected, decoded.rgba[value * 4].toInt() and 0xFF, "16-bit sample $value")
		}
	}

	@Test
	fun decodes16BitRgbaByTheSpecLinearEquation() {
		// 0x00FF rounds up to 1 and 0xFF00 down to 254, where keeping the high byte would give 0 and 255.
		val png = buildPng(width = 1, height = 1, bitDepth = 16, colorType = 6, samples = bytes(0x00, 0xFF, 0xFF, 0x00, 0x80, 0x80, 0xFF, 0xFF), rowBytes = 8)
		assertContentEquals(bytes(1, 254, 128, 255), PngCodec.read(png).rgba)
	}

	@Test
	fun sixteenBitTransparentKeyMatchesBeforeRescaling() {
		// PNG spec §13.12: the tRNS comparison is exact, on the raw samples.  The second pixel's red
		// differs from the key by one, so both pixels rescale to the same color but only the first is
		// transparent.
		val trns = bytes(0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC)
		val samples = bytes(0x12, 0x34, 0x56, 0x78, 0x9A, 0xBC, 0x12, 0x35, 0x56, 0x78, 0x9A, 0xBC)
		val png = buildPng(width = 2, height = 1, bitDepth = 16, colorType = 2, samples = samples, rowBytes = 12, trns = trns)
		val decoded = PngCodec.read(png)
		assertContentEquals(bytes(0x12, 0x56, 0x9A, 0, 0x12, 0x56, 0x9A, 255), decoded.rgba)
	}

	@Test
	fun decodesGrayscaleAlpha8() {
		val png = buildPng(width = 2, height = 1, bitDepth = 8, colorType = 4, samples = bytes(10, 20, 200, 255), rowBytes = 4)
		assertContentEquals(bytes(10, 10, 10, 20, 200, 200, 200, 255), PngCodec.read(png).rgba)
	}

	@Test
	fun decodesTwoBitPalette() {
		// Indices 3,2,1,0 packed leftmost-first into one byte (PNG spec §7.2): 0b11_10_01_00.
		val palette = bytes(255, 0, 0, 0, 255, 0, 0, 0, 255, 9, 9, 9)
		val png = buildPng(width = 4, height = 1, bitDepth = 2, colorType = 3, samples = bytes(0xE4), rowBytes = 1, palette = palette)
		assertContentEquals(bytes(9, 9, 9, 255, 0, 0, 255, 255, 0, 255, 0, 255, 255, 0, 0, 255), PngCodec.read(png).rgba)
	}

	@Test
	fun idatSplitAcrossChunksDecodesTheSame() {
		// PNG spec §10.2: IDAT boundaries are arbitrary, including mid-scanline and empty chunks.
		val width = 7
		val height = 5
		val samples = ByteArray(width * height * 4) { (it * 37 % 251).toByte() }
		val whole = PngCodec.read(buildPng(width, height, bitDepth = 8, colorType = 6, samples = samples, rowBytes = width * 4))
		val split =
			PngCodec.read(
				buildPng(width, height, bitDepth = 8, colorType = 6, samples = samples, rowBytes = width * 4) { stream ->
					listOf(stream.copyOfRange(0, 3), ByteArray(0), stream.copyOfRange(3, 11), stream.copyOfRange(11, stream.size))
				},
			)
		assertContentEquals(samples, whole.rgba)
		assertContentEquals(whole.rgba, split.rgba)
	}

	@Test
	fun truncatedIdatStreamDecodesWhatItHolds() {
		// Pseudo-random pixels keep the stream long, so half of it still reaches well past the first row.
		val width = 256
		val height = 64
		var state = 12345
		val samples =
			ByteArray(width * height * 4) {
				state = state * 1103515245 + 12345
				(state ushr 16).toByte()
			}
		val png = buildPng(width, height, bitDepth = 8, colorType = 6, samples = samples, rowBytes = width * 4) { stream -> listOf(stream.copyOf(stream.size / 2)) }
		val decoded = PngCodec.read(png)
		val rowBytes = width * 4
		assertContentEquals(samples.copyOfRange(0, rowBytes), decoded.rgba.copyOfRange(0, rowBytes), "the first row survives")
		assertTrue(decoded.rgba.copyOfRange((height - 1) * rowBytes, height * rowBytes).all { it == 0.toByte() }, "an unreached row is transparent black")
	}

	@Test
	fun rejectsBitDepthTheColorTypeDoesNotAllow() {
		// PNG spec §11.2.2 Table 11.1: truecolor allows only 8 and 16 bits.
		val png = buildPng(width = 2, height = 1, bitDepth = 4, colorType = 2, samples = bytes(0, 0, 0), rowBytes = 3)
		assertFailsWith<IllegalArgumentException> { PngCodec.read(png) }
	}

	@Test
	fun rejectsUnknownCompressionMethod() {
		val png = buildPng(width = 1, height = 1, bitDepth = 8, colorType = 0, samples = bytes(0), rowBytes = 1, compressionMethod = 1)
		assertFailsWith<IllegalArgumentException> { PngCodec.read(png) }
	}

	@Test
	fun rejectsIndexedColorWithoutPalette() {
		val png = buildPng(width = 1, height = 1, bitDepth = 8, colorType = 3, samples = bytes(0), rowBytes = 1)
		assertFailsWith<IllegalArgumentException> { PngCodec.read(png) }
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