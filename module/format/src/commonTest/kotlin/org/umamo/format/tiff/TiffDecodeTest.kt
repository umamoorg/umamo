package org.umamo.format.tiff

import org.umamo.format.raster.ByteBuilder
import org.umamo.format.raster.deflateZlib
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Exercises [TiffReader] with synthetic, hand-built single-strip TIFFs whose pixels are known exactly
 * (mirroring PsdSyntheticTest), covering each compression (uncompressed, PackBits, LZW, Deflate),
 * photometric (RGB, gray with WhiteIsZero, palette), the horizontal predictor, RGBA ExtraSamples
 * alpha, 16-bit down-conversion, and both byte orders.  In-test PackBits/LZW/Deflate encoders drive
 * the compressed paths so no corpus is needed.
 */
class TiffDecodeTest {
	private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

	// --- Compression codes (mirrors TiffConstants). ---
	private val none = 1
	private val lzw = 5
	private val zlib = 8
	private val packBits = 32773

	// --- Field types. ---
	private val typeShort = 3
	private val typeLong = 4

	private class Field(val tag: Int, val type: Int, val values: IntArray)

	private fun typeLength(type: Int): Int = if (type == typeShort) 2 else 4

	/**
	 * Encodes a field's values little-endian.
	 */
	private fun encodeValues(field: Field): ByteArray {
		val out = ByteBuilder()
		for (value in field.values) {
			if (field.type == typeShort) {
				out.writeByte(value and 0xFF)
				out.writeByte((value ushr 8) and 0xFF)
			} else {
				out.writeByte(value and 0xFF)
				out.writeByte((value ushr 8) and 0xFF)
				out.writeByte((value ushr 16) and 0xFF)
				out.writeByte((value ushr 24) and 0xFF)
			}
		}
		return out.toByteArray()
	}

	/**
	 * Builds a little-endian single-strip TIFF from a fixed set of fields plus the strip data.
	 */
	private fun buildTiff(
		width: Int,
		height: Int,
		bitsPerSample: IntArray,
		samplesPerPixel: Int,
		photometric: Int,
		compression: Int,
		stripData: ByteArray,
		predictor: Int? = null,
		colorMap: IntArray? = null,
		extraSamples: IntArray? = null,
		bigEndian: Boolean = false,
	): ByteArray {
		val fields = ArrayList<Field>()
		fields += Field(256, typeShort, intArrayOf(width))
		fields += Field(257, typeShort, intArrayOf(height))
		fields += Field(258, typeShort, bitsPerSample)
		fields += Field(259, typeShort, intArrayOf(compression))
		fields += Field(262, typeShort, intArrayOf(photometric))
		val stripOffsetsField = Field(273, typeLong, intArrayOf(0))
		fields += stripOffsetsField
		fields += Field(277, typeShort, intArrayOf(samplesPerPixel))
		fields += Field(278, typeLong, intArrayOf(height))
		fields += Field(279, typeLong, intArrayOf(stripData.size))
		if (predictor != null) {
			fields += Field(317, typeShort, intArrayOf(predictor))
		}
		if (colorMap != null) {
			fields += Field(320, typeShort, colorMap)
		}
		if (extraSamples != null) {
			fields += Field(338, typeShort, extraSamples)
		}
		fields.sortBy { it.tag }

		val ifdOffset = 8
		val ifdSize = 2 + 12 * fields.size + 4
		var externalCursor = ifdOffset + ifdSize
		val externalBlocks = ArrayList<Pair<Int, ByteArray>>()
		val valueField = HashMap<Field, Int>()
		for (field in fields) {
			val encoded = encodeValues(field)
			if (encoded.size <= 4) {
				valueField[field] = 0 // inline; filled below from encoded bytes
			} else {
				valueField[field] = externalCursor
				externalBlocks += externalCursor to encoded
				externalCursor += encoded.size
			}
		}
		val stripOffset = externalCursor

		val totalSize = stripOffset + stripData.size
		val out = ByteArray(totalSize)
		// Header.
		if (bigEndian) {
			out[0] = 0x4D
			out[1] = 0x4D
			out[2] = 0x00
			out[3] = 0x2A
		} else {
			out[0] = 0x49
			out[1] = 0x49
			out[2] = 0x2A
			out[3] = 0x00
		}
		putU32(out, 4, ifdOffset, bigEndian)

		// IFD.
		putU16(out, ifdOffset, fields.size, bigEndian)
		var entryOffset = ifdOffset + 2
		for (field in fields) {
			putU16(out, entryOffset, field.tag, bigEndian)
			putU16(out, entryOffset + 2, field.type, bigEndian)
			putU32(out, entryOffset + 4, field.values.size, bigEndian)
			val totalBytes = field.values.size * typeLength(field.type)
			if (field === stripOffsetsField) {
				putU32(out, entryOffset + 8, stripOffset, bigEndian)
			} else if (totalBytes <= 4) {
				// Inline: place the value(s) left-justified in the 4-byte field, in file byte order.
				putInlineField(out, entryOffset + 8, field, bigEndian)
			} else {
				putU32(out, entryOffset + 8, valueField[field]!!, bigEndian)
			}
			entryOffset += 12
		}
		putU32(out, entryOffset, 0, bigEndian) // next IFD = none

		// External data blocks (all SHORT arrays here). encodeValues wrote them little-endian; byte-swap
		// each 16-bit value for a big-endian file.
		for ((offset, block) in externalBlocks) {
			val reordered = if (bigEndian) swapShorts(block) else block
			reordered.copyInto(out, offset)
		}
		// Strip data.
		stripData.copyInto(out, stripOffset)
		return out
	}

	/** Writes a field's short/long values inline into the 4-byte value slot, in file byte order. */
	private fun putInlineField(out: ByteArray, at: Int, field: Field, bigEndian: Boolean) {
		var cursor = at
		for (value in field.values) {
			if (field.type == typeShort) {
				putU16(out, cursor, value, bigEndian)
				cursor += 2
			} else {
				putU32(out, cursor, value, bigEndian)
				cursor += 4
			}
		}
	}

	/** Byte-swaps each 16-bit value in [littleEndianBlock] (external SHORT arrays are always 16-bit). */
	private fun swapShorts(littleEndianBlock: ByteArray): ByteArray {
		val out = ByteArray(littleEndianBlock.size)
		var index = 0
		while (index + 1 < littleEndianBlock.size) {
			out[index] = littleEndianBlock[index + 1]
			out[index + 1] = littleEndianBlock[index]
			index += 2
		}
		return out
	}

	private fun putU16(out: ByteArray, at: Int, value: Int, bigEndian: Boolean) {
		if (bigEndian) {
			out[at] = ((value ushr 8) and 0xFF).toByte()
			out[at + 1] = (value and 0xFF).toByte()
		} else {
			out[at] = (value and 0xFF).toByte()
			out[at + 1] = ((value ushr 8) and 0xFF).toByte()
		}
	}

	private fun putU32(out: ByteArray, at: Int, value: Int, bigEndian: Boolean) {
		if (bigEndian) {
			out[at] = ((value ushr 24) and 0xFF).toByte()
			out[at + 1] = ((value ushr 16) and 0xFF).toByte()
			out[at + 2] = ((value ushr 8) and 0xFF).toByte()
			out[at + 3] = (value and 0xFF).toByte()
		} else {
			out[at] = (value and 0xFF).toByte()
			out[at + 1] = ((value ushr 8) and 0xFF).toByte()
			out[at + 2] = ((value ushr 16) and 0xFF).toByte()
			out[at + 3] = ((value ushr 24) and 0xFF).toByte()
		}
	}

	// --- Strip-data encoders. ---

	/** PackBits-encodes [data] as one literal run per 128-byte chunk (valid; my decoder reads literals). */
	private fun packBitsEncode(data: ByteArray): ByteArray {
		val out = ByteBuilder()
		var index = 0
		while (index < data.size) {
			val runLength = minOf(128, data.size - index)
			out.writeByte(runLength - 1) // control: copy next runLength literal bytes
			out.writeBytes(data, index, runLength)
			index += runLength
		}
		return out.toByteArray()
	}

	/**
	 * zlib-compresses [data] for a TIFF Deflate/ZIP strip.
	 *
	 * Uses the shared bridge rather than a platform Deflater so this test runs on every target.  That
	 * does mean the Deflate case round-trips through our own compressor, unlike the LZW and PackBits
	 * cases which have independent encoders below — acceptable because zlib framing is not ours to get
	 * wrong (the bridge delegates to the platform), and the assertions still pin the predictor,
	 * photometric, and assembly logic that this test actually exists to cover.
	 *
	 * @param ByteArray data The raw strip bytes.
	 * @return ByteArray The zlib stream.
	 */
	private fun deflate(data: ByteArray): ByteArray = deflateZlib(data)

	/**
	 * TIFF-spec LZW encode (MSB-first, 9-12 bit codes, early change) matching [decodeLzw].
	 */
	private fun lzwEncode(data: ByteArray): ByteArray {
		val out = ByteBuilder()
		var bitBuffer = 0
		var bitCount = 0
		var codeSize = 9

		fun emit(code: Int) {
			bitBuffer = (bitBuffer shl codeSize) or code
			bitCount += codeSize
			while (bitCount >= 8) {
				out.writeByte((bitBuffer ushr (bitCount - 8)) and 0xFF)
				bitCount -= 8
			}
		}

		val dictionary = HashMap<String, Int>()

		fun reset() {
			dictionary.clear()
			for (value in 0..255) {
				dictionary[value.toString()] = value
			}
		}

		val clearCode = 256
		val eoiCode = 257
		reset()
		var next = 258
		codeSize = 9
		emit(clearCode)
		var current = ""
		for (rawByte in data) {
			val symbol = (rawByte.toInt() and 0xFF).toString()
			val combined = if (current.isEmpty()) symbol else "$current,$symbol"
			if (dictionary.containsKey(combined)) {
				current = combined
			} else {
				emit(dictionary[current]!!)
				dictionary[combined] = next++
				if (next == (1 shl codeSize) - 1 && codeSize < 12) {
					codeSize++
				}
				current = symbol
			}
		}
		if (current.isNotEmpty()) {
			emit(dictionary[current]!!)
		}
		emit(eoiCode)
		// Flush remaining bits.
		if (bitCount > 0) {
			out.writeByte((bitBuffer shl (8 - bitCount)) and 0xFF)
		}
		return out.toByteArray()
	}

	/** Applies the horizontal-differencing predictor forward (encoder side) to 8-bit sample rows. */
	private fun predictHorizontal(data: ByteArray, width: Int, height: Int, samplesPerPixel: Int): ByteArray {
		val out = data.copyOf()
		val rowBytes = width * samplesPerPixel
		for (row in 0 until height) {
			val base = row * rowBytes
			for (column in width - 1 downTo 1) {
				for (sample in 0 until samplesPerPixel) {
					val offset = base + column * samplesPerPixel + sample
					out[offset] = (data[offset] - data[offset - samplesPerPixel]).toByte()
				}
			}
		}
		return out
	}

	private fun compress(raw: ByteArray, compression: Int): ByteArray =
		when (compression) {
			packBits -> packBitsEncode(raw)
			lzw -> lzwEncode(raw)
			zlib -> deflate(raw)
			else -> raw
		}

	// --- Fixtures. ---

	private val rgb2x2 = bytes(10, 20, 30, 40, 50, 60, 70, 80, 90, 100, 110, 120)
	private val rgb2x2Expected =
		bytes(10, 20, 30, 255, 40, 50, 60, 255, 70, 80, 90, 255, 100, 110, 120, 255)

	private fun assertRgbDecodes(compression: Int) {
		val tiff = buildTiff(2, 2, intArrayOf(8, 8, 8), 3, 2, compression, compress(rgb2x2, compression))
		val decoded = TiffReader.read(tiff)
		assertEquals(2, decoded.width)
		assertEquals(2, decoded.height)
		assertContentEquals(rgb2x2Expected, decoded.rgba, "RGB decode (compression $compression)")
	}

	@Test fun decodesUncompressedRgb() = assertRgbDecodes(none)

	@Test fun decodesPackBitsRgb() = assertRgbDecodes(packBits)

	@Test fun decodesLzwRgb() = assertRgbDecodes(lzw)

	@Test fun decodesDeflateRgb() = assertRgbDecodes(zlib)

	@Test
	fun decodesBigEndianRgb() {
		val tiff = buildTiff(2, 2, intArrayOf(8, 8, 8), 3, 2, none, rgb2x2, bigEndian = true)
		assertContentEquals(rgb2x2Expected, TiffReader.read(tiff).rgba, "big-endian RGB")
	}

	@Test
	fun decodesGrayBlackIsZero() {
		val gray = bytes(0, 64, 128, 255)
		val tiff = buildTiff(2, 2, intArrayOf(8), 1, 1, none, gray)
		val expected = bytes(0, 0, 0, 255, 64, 64, 64, 255, 128, 128, 128, 255, 255, 255, 255, 255)
		assertContentEquals(expected, TiffReader.read(tiff).rgba)
	}

	@Test
	fun decodesGrayWhiteIsZeroInverts() {
		val gray = bytes(0, 64, 200, 255)
		val tiff = buildTiff(2, 2, intArrayOf(8), 1, 0, none, gray) // photometric 0 = WhiteIsZero
		val expected = bytes(255, 255, 255, 255, 191, 191, 191, 255, 55, 55, 55, 255, 0, 0, 0, 255)
		assertContentEquals(expected, TiffReader.read(tiff).rgba)
	}

	@Test
	fun decodesPalette8Bit() {
		// 8-bit palette needs 256 entries * 3 planes of 16-bit ColorMap; only the used indices matter.
		val paletteSize = 256
		val colorMap = IntArray(paletteSize * 3)

		// Index 0 -> red, 1 -> green, 2 -> blue, 3 -> gray128 (ColorMap stores 16-bit; use value*257 to fill).
		fun set(index: Int, red: Int, green: Int, blue: Int) {
			colorMap[index] = red * 257
			colorMap[paletteSize + index] = green * 257
			colorMap[2 * paletteSize + index] = blue * 257
		}
		set(0, 255, 0, 0)
		set(1, 0, 255, 0)
		set(2, 0, 0, 255)
		set(3, 128, 128, 128)
		val indices = bytes(0, 1, 2, 3)
		val tiff = buildTiff(2, 2, intArrayOf(8), 1, 3, none, indices, colorMap = colorMap)
		val expected = bytes(255, 0, 0, 255, 0, 255, 0, 255, 0, 0, 255, 255, 128, 128, 128, 255)
		assertContentEquals(expected, TiffReader.read(tiff).rgba)
	}

	@Test
	fun decodesRgbaWithUnassociatedAlpha() {
		val rgba = bytes(10, 20, 30, 255, 40, 50, 60, 128, 70, 80, 90, 64, 100, 110, 120, 0)
		val tiff = buildTiff(1, 4, intArrayOf(8, 8, 8, 8), 4, 2, none, rgba, extraSamples = intArrayOf(2))
		assertContentEquals(rgba, TiffReader.read(tiff).rgba, "straight alpha preserved")
	}

	@Test
	fun decodesPredictorHorizontalRgb() {
		val predicted = predictHorizontal(rgb2x2, 2, 2, 3)
		val tiff = buildTiff(2, 2, intArrayOf(8, 8, 8), 3, 2, none, predicted, predictor = 2)
		assertContentEquals(rgb2x2Expected, TiffReader.read(tiff).rgba, "predictor 2 un-differenced")
	}

	@Test
	fun decodes16BitGrayKeepsHighByte() {
		// Two pixels, 16-bit big-endian samples 0x1234 and 0xABCD -> high bytes 0x12, 0xAB.
		val gray16 = bytes(0x12, 0x34, 0xAB, 0xCD)
		val tiff = buildTiff(2, 1, intArrayOf(16), 1, 1, none, gray16, bigEndian = true)
		val expected = bytes(0x12, 0x12, 0x12, 255, 0xAB, 0xAB, 0xAB, 255)
		assertContentEquals(expected, TiffReader.read(tiff).rgba)
	}
}
