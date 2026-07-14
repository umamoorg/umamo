package org.umamo.format.png

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * PNG datastream plumbing: the 8-byte signature, the length/type/data/CRC chunk framing, and the
 * zlib (de)compression the IDAT stream uses.
 *
 * Pure java.util.zip (present on desktop JVM and Android) - no host image library, so decode is
 * byte-identical on both targets.  Spec citations are to the W3C PNG Specification (equivalently
 * RFC 2083).
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
	val crc = CRC32()
	crc.update(type)
	crc.update(data)
	return crc.value
}

/**
 * Appends a complete chunk (length, type, data, CRC) to [out].
 *
 * @param ByteArrayOutputStream out The destination stream.
 * @param String type               The 4-character chunk type (ASCII).
 * @param ByteArray data            The chunk data (may be empty, e.g. IEND).
 */
internal fun writeChunk(out: ByteArrayOutputStream, type: String, data: ByteArray) {
	val typeBytes = type.encodeToByteArray()
	writeU32BE(out, data.size)
	out.write(typeBytes)
	out.write(data)
	writeU32BE(out, crc32Of(typeBytes, data).toInt())
}

/**
 * Appends a big-endian 32-bit integer to [out].
 *
 * @param ByteArrayOutputStream out The destination stream.
 * @param Int value                 The value to write.
 */
internal fun writeU32BE(out: ByteArrayOutputStream, value: Int) {
	out.write((value ushr 24) and 0xFF)
	out.write((value ushr 16) and 0xFF)
	out.write((value ushr 8) and 0xFF)
	out.write(value and 0xFF)
}

/**
 * Inflates the zlib stream at [offset] (the concatenated IDAT bytes) to its full length.
 *
 * PNG IDAT is a standard zlib datastream (header + Adler-32), so a default [Inflater] decodes it.
 * Grows the output buffer as needed rather than pre-sizing, so a truncated stream degrades to a
 * short result instead of throwing (best-effort, mirroring the PSD/CLIP readers).
 *
 * @param ByteArray bytes The buffer holding the zlib stream.
 * @param Int offset      Offset of the first byte.
 * @param Int length      Compressed byte count.
 * @return ByteArray The inflated bytes.
 */
internal fun inflateZlib(bytes: ByteArray, offset: Int, length: Int): ByteArray {
	if (length <= 0) {
		return ByteArray(0)
	}
	val inflater = Inflater()
	inflater.setInput(bytes, offset, minOf(length, bytes.size - offset))
	val out = ByteArrayOutputStream(length * 3)
	val chunk = ByteArray(64 * 1024)
	try {
		while (!inflater.finished()) {
			val produced = inflater.inflate(chunk)
			if (produced == 0) {
				if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
					break
				}
			} else {
				out.write(chunk, 0, produced)
			}
		}
	} catch (_: DataFormatException) {
		// Best-effort: return what inflated so far rather than failing the whole decode.
	} finally {
		inflater.end()
	}
	return out.toByteArray()
}

/**
 * Compresses [data] to a zlib stream for an IDAT chunk (default deflate level).
 *
 * @param ByteArray data The raw, filtered scanline bytes.
 * @return ByteArray The zlib-wrapped deflate stream.
 */
internal fun deflateZlib(data: ByteArray): ByteArray {
	val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
	deflater.setInput(data)
	deflater.finish()
	val out = ByteArrayOutputStream(data.size / 2 + 64)
	val chunk = ByteArray(64 * 1024)
	while (!deflater.finished()) {
		val produced = deflater.deflate(chunk)
		out.write(chunk, 0, produced)
	}
	deflater.end()
	return out.toByteArray()
}
