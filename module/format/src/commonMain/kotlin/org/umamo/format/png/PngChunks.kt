package org.umamo.format.png

import okio.Buffer
import okio.Source
import okio.Timeout
import org.umamo.format.binary.Crc32
import org.umamo.format.binary.deflateZlib

/*
 * PNG datastream plumbing: the 8-byte signature, the length/type/data/CRC chunk framing, the IDAT
 * stream the decoder inflates, and the zlib compression the encoder writes it with.
 *
 * No host image library, so decode is byte-identical on every target; the only platform dependency is
 * the zlib bridge in org.umamo.format.binary.  Spec citations are to the PNG specification, second
 * edition (ISO/IEC 15948:2003, W3C REC-PNG-20031110), whose section numbers differ from RFC 2083's.
 */

/** PNG spec §5.2 Datastream signature: the fixed 8-byte file magic. */
internal val PNG_SIGNATURE: ByteArray =
	byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

/**
 * One parsed PNG chunk: its 4-character type and where its (already CRC-verified) data sits in the
 * file bytes.  The data stays in place rather than being copied out, because the IDAT chunks of a
 * large atlas page hold hundreds of megabytes that the decoder only needs to stream through once.
 *
 * @param String type          The chunk type.
 * @param ByteArray fileBytes  The complete `.png` file the chunk was read from.
 * @param Int dataOffset       Offset of the chunk's first data byte in [fileBytes].
 * @param Int dataLength       The chunk's data byte count.
 */
internal class PngChunk(val type: String, val fileBytes: ByteArray, val dataOffset: Int, val dataLength: Int) {
	/**
	 * Copies the chunk's data out of the file bytes, for the small chunks (IHDR, PLTE, tRNS) the
	 * decoder reads as a whole.
	 *
	 * @return ByteArray The chunk data.
	 */
	fun copyData(): ByteArray = fileBytes.copyOfRange(dataOffset, dataOffset + dataLength)
}

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
		// Checked in Long: a hostile length near Int.MAX_VALUE would otherwise wrap past the bound.
		require(length >= 0 && dataStart.toLong() + length + 4 <= bytes.size) { "truncated PNG chunk at offset $cursor" }
		val dataEnd = dataStart + length

		val type = bytes.decodeToString(typeStart, typeStart + 4)
		val storedCrc = readU32BE(bytes, dataEnd)
		// The CRC covers the type and data fields, which sit next to each other in the file.
		val computedCrc = Crc32().also { crc -> crc.update(bytes, typeStart, 4 + length) }.value
		require(storedCrc == computedCrc) {
			"PNG chunk '$type' CRC mismatch (stored $storedCrc, computed $computedCrc)"
		}

		chunks += PngChunk(type, bytes, dataStart, length)
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
 * The data of the IDAT chunks, in file order, read as the one continuous zlib stream they form (PNG
 * spec §10.2 and §11.2.4: the concatenated IDAT data is one zlib datastream, split at arbitrary
 * boundaries).
 *
 * Reads straight out of the file bytes, a piece at a time, so the compressed stream is never
 * concatenated into a copy of its own.
 *
 * @param List<PngChunk> idatChunks The IDAT chunks in file order.
 */
internal class IdatSource(private val idatChunks: List<PngChunk>) : Source {
	private var chunkIndex = 0

	// Bytes of the current chunk's data already delivered.
	private var consumed = 0

	/**
	 * Appends up to [byteCount] of the next stream bytes to [sink], never crossing into the next
	 * chunk in one call.
	 *
	 * @param Buffer sink    The buffer to append to.
	 * @param Long byteCount The most bytes to deliver.
	 * @return Long The bytes delivered, or -1 once every chunk has been read.
	 */
	override fun read(sink: Buffer, byteCount: Long): Long {
		while (chunkIndex < idatChunks.size && consumed == idatChunks[chunkIndex].dataLength) {
			chunkIndex++
			consumed = 0
		}
		if (chunkIndex == idatChunks.size) {
			return -1L
		}
		val chunk = idatChunks[chunkIndex]
		val count = minOf(byteCount, (chunk.dataLength - consumed).toLong()).toInt()
		sink.write(chunk.fileBytes, chunk.dataOffset + consumed, count)
		consumed += count
		return count.toLong()
	}

	/**
	 * No deadline: the bytes are already in memory.
	 *
	 * @return Timeout The no-op timeout.
	 */
	override fun timeout(): Timeout = Timeout.NONE

	/**
	 * Nothing to release: the file bytes belong to the caller.
	 */
	override fun close() {
	}
}

/**
 * Compresses [data] to a zlib stream for an IDAT chunk (default deflate level).
 *
 * @param ByteArray data The raw, filtered scanline bytes.
 * @return ByteArray The zlib-wrapped deflate stream.
 */
internal fun deflateIdat(data: ByteArray): ByteArray = deflateZlib(data)