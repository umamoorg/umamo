package org.umamo.format.clip

/*
 * Synthetic CSFCHUNK container assembly for tests.  Lives in commonTest so the jvmTest synthetic
 * ClipReader test (which additionally needs a real SQLite driver) can reuse the same builder via the
 * test source-set hierarchy.
 */

/**
 * Assembles a synthetic .clip container around the given chunks.
 *
 * Layout per docs/format/CLIP.md (all integers big-endian):
 *   // CLIP: @ +0x00 "CSFCHUNK" (8) container magic
 *   // CLIP: @ +0x08 fileSize   u64 whole-file length
 *   // CLIP: @ +0x10 headOffset u64 offset of the first chunk (= 24)
 *   // CLIP: then [8-byte ASCII id][u64 size][payload] repeated
 *
 * @param List chunks     The chunk id (8 ASCII characters) to payload pairs, in file order.
 * @param Long headOffset The first-chunk offset to stamp at +0x10 (24 in every real file).
 * @return ByteArray The assembled container bytes.
 */
internal fun syntheticClipContainer(chunks: List<Pair<String, ByteArray>>, headOffset: Long = 24): ByteArray {
	val chunkBytes = chunks.sumOf { (_, payload) -> 16 + payload.size }
	val container = ByteArray(24 + chunkBytes)
	"CSFCHUNK".encodeToByteArray().copyInto(container, 0)
	writeUInt64BigEndian(container, 8, container.size.toLong())
	writeUInt64BigEndian(container, 16, headOffset)
	var offset = 24
	for ((chunkId, payload) in chunks) {
		require(chunkId.length == 8) { "chunk id must be 8 ASCII characters: '$chunkId'" }
		chunkId.encodeToByteArray().copyInto(container, offset)
		writeUInt64BigEndian(container, offset + 8, payload.size.toLong())
		payload.copyInto(container, offset + 16)
		offset += 16 + payload.size
	}
	return container
}

/**
 * Writes a big-endian unsigned 64-bit integer.
 *
 * @param ByteArray bytes The buffer to write into.
 * @param Int       at    The offset of the most-significant byte.
 * @param Long      value The value to write.
 */
private fun writeUInt64BigEndian(bytes: ByteArray, at: Int, value: Long) {
	for (byteIndex in 0 until 8) {
		bytes[at + byteIndex] = ((value ushr ((7 - byteIndex) * 8)) and 0xFF).toByte()
	}
}
