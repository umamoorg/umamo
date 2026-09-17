package org.umamo.format.png

import okio.Buffer
import org.umamo.format.binary.Crc32
import org.umamo.format.binary.deflateZlib
import org.umamo.format.binary.inflateZlib

/*
 * PNG datastream plumbing: the 8-byte signature, the length/type/data/CRC chunk framing, and the
 * zlib (de)compression the IDAT stream uses.
 *
 * No host image library, so decode is byte-identical on every target; the only platform dependency is
 * the zlib bridge in org.umamo.format.raster.  Spec citations are to the W3C PNG Specification
 * (equivalently RFC 2083).
 */

/** PNG spec §5.2 Datastream signature: the fixed 8-byte file magic. */
internal val PNG_SIGNATURE: ByteArray =
	byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/** One parsed PNG chunk: its 4-character type and its (already CRC-verified) data bytes. */
internal class PngChunk(val type: String, val data: ByteArray)

/**
 * True if [bytes] opens with the PNG signature.
 *
 * @param ByteArray bytes Candidate file contents.
 * @return Boolean Whether the leading 8 bytes are the PNG magic.
 */
internal fun matchesPngSignature(bytes: ByteArray): Boolean {
	if (bytes.size < PNG_SIGNATURE.size) {
		return false
	}
	for (signatureIndex in PNG_SIGNATURE.indices) {
		if (bytes[signatureIndex] != PNG_SIGNATURE[signatureIndex]) {
			return false
		}
	}
	return true
}

/**
 * The width and height a PNG's header declares, read from the fixed offsets of its IHDR chunk without
 * decoding anything else, or null when [bytes] do not open with the signature and an IHDR.
 *
 * PNG spec §11.2.2: IHDR is always the first chunk, 13 bytes of data, width then height as unsigned
 * big-endian 32-bit integers.  A dimension past what an Int holds reads as null too: no image this
 * codec decodes can be that large.
 *
 * @param ByteArray bytes The complete `.png` file, or at least its first 24 bytes.
 * @return Pair<Int, Int>? The width and height, or null.
 */
internal fun pngDimensionsOf(bytes: ByteArray): Pair<Int, Int>? {
	val headerEnd = PNG_SIGNATURE.size + 16
	if (bytes.size < headerEnd || !matchesPngSignature(bytes)) {
		return null
	}
	val chunkStart = PNG_SIGNATURE.size
	if (readU32BE(bytes, chunkStart) != 13L || bytes.copyOfRange(chunkStart + 4, chunkStart + 8).decodeToString() != "IHDR") {
		return null
	}
	val width = readU32BE(bytes, chunkStart + 8)
	val height = readU32BE(bytes, chunkStart + 12)
	if (width > Int.MAX_VALUE || height > Int.MAX_VALUE) {
		return null
	}
	return width.toInt() to height.toInt()
}

/**
 * Reads an unsigned big-endian 32-bit integer as a Long (PNG stores lengths and CRCs this way).
 *
 * @param ByteArray bytes The buffer.
 * @param Int at          Offset of the most-significant byte.
 * @return Long The value in 0..4294967295.
 */
internal fun readU32BE(bytes: ByteArray, at: Int): Long =
	((bytes[at].toLong() and 0xFF) shl 24) or
		((bytes[at + 1].toLong() and 0xFF) shl 16) or
		((bytes[at + 2].toLong() and 0xFF) shl 8) or
		(bytes[at + 3].toLong() and 0xFF)

/**
 * Parses the chunk sequence after the signature, verifying each chunk's CRC-32, up to and including
 * IEND.  A truncated chunk or a CRC mismatch is a hard error - a valid PNG never has either.
 *
 * @param ByteArray bytes The complete `.png` file.
 * @return List<PngChunk> The chunks in file order.
 */
internal fun readChunks(bytes: ByteArray): List<PngChunk> {
	require(matchesPngSignature(bytes)) { "not a PNG (bad signature)" }
	val chunks = ArrayList<PngChunk>()
	var cursor = PNG_SIGNATURE.size
	// PNG spec §5.3 Chunk layout: length(4) | type(4) | data(length) | crc(4).
	while (cursor + 8 <= bytes.size) {
		val length = readU32BE(bytes, cursor).toInt()
		val typeStart = cursor + 4
		val dataStart = cursor + 8
		val dataEnd = dataStart + length
		require(length >= 0 && dataEnd + 4 <= bytes.size) { "truncated PNG chunk at offset $cursor" }

		val typeBytes = bytes.copyOfRange(typeStart, typeStart + 4)
		val data = bytes.copyOfRange(dataStart, dataEnd)
		val storedCrc = readU32BE(bytes, dataEnd)
		val computedCrc = crc32Of(typeBytes, data)
		require(storedCrc == computedCrc) {
			"PNG chunk '${typeBytes.decodeToString()}' CRC mismatch (stored $storedCrc, computed $computedCrc)"
		}

		val type = typeBytes.decodeToString()
		chunks += PngChunk(type, data)
		cursor = dataEnd + 4
		if (type == "IEND") {
			break
		}
	}
	return chunks
}

/**
 * CRC-32 over a chunk's type bytes followed by its data bytes, per PNG spec §5.3.
 *
 * @param ByteArray type The 4 type bytes.
 * @param ByteArray data The chunk data.
 * @return Long The CRC-32 value.
 */
internal fun crc32Of(type: ByteArray, data: ByteArray): Long {
	val crc = Crc32()
	crc.update(type)
	crc.update(data)
	return crc.value
}

/**
 * Appends a complete chunk (length, type, data, CRC) to [out].
 *
 * PNG's multi-byte fields are all big-endian, which is Buffer.writeInt's native order — hence no
 * byte-order helper here.
 *
 * @param Buffer out     The destination buffer.
 * @param String type    The 4-character chunk type (ASCII).
 * @param ByteArray data The chunk data (may be empty, e.g. IEND).
 */
internal fun writeChunk(out: Buffer, type: String, data: ByteArray) {
	val typeBytes = type.encodeToByteArray()
	out.writeInt(data.size)
	out.write(typeBytes)
	out.write(data)
	out.writeInt(crc32Of(typeBytes, data).toInt())
}

/**
 * Inflates the concatenated IDAT bytes at [offset], stopping at [expectedSize].
 *
 * PNG IDAT is a standard zlib datastream (header + Adler-32).  The stream does not state its own
 * decompressed size, but IHDR fully determines it (see PngCodec.expectedRawSize), so the caller
 * passes it here as a hard bound: a corrupt or hostile IDAT that would otherwise inflate without
 * limit stops at the size the header promised.  A truncated stream still degrades to a short result
 * rather than throwing (best-effort, mirroring the PSD/CLIP readers).
 *
 * @param ByteArray bytes The buffer holding the zlib stream.
 * @param Int offset      Offset of the first byte.
 * @param Int length      Compressed byte count.
 * @param Int expectedSize The decompressed byte count IHDR implies; the inflate stops there.
 * @return ByteArray The inflated bytes.
 */
internal fun inflateIdat(bytes: ByteArray, offset: Int, length: Int, expectedSize: Int): ByteArray = inflateZlib(bytes, offset, length, expectedSize)

/**
 * Compresses [data] to a zlib stream for an IDAT chunk (default deflate level).
 *
 * @param ByteArray data The raw, filtered scanline bytes.
 * @return ByteArray The zlib-wrapped deflate stream.
 */
internal fun deflateIdat(data: ByteArray): ByteArray = deflateZlib(data)