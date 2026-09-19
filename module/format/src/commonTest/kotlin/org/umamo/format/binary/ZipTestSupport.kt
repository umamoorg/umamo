package org.umamo.format.binary

/*
 * Byte-level helpers the ZIP tests use to read and corrupt archives in place, so each malformation is
 * built from a valid archive rather than hand-assembled.
 */

/** A fixed stamp for test archives: 2026-09-16 12:34:56 local. */
internal val TEST_DOS_DATE_TIME: Int = ZipRecords.dosDateTimeOf(2026, 9, 16, 12, 34, 56)

/**
 * Reads a little-endian unsigned 16-bit value.
 *
 * @param ByteArray bytes The buffer.
 * @param Int       at    Offset of the low byte.
 * @return Int The value.
 */
internal fun readU16Le(bytes: ByteArray, at: Int): Int = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

/**
 * Reads a little-endian unsigned 32-bit value.
 *
 * @param ByteArray bytes The buffer.
 * @param Int       at    Offset of the low byte.
 * @return Long The value.
 */
internal fun readU32Le(bytes: ByteArray, at: Int): Long = readU16Le(bytes, at).toLong() or (readU16Le(bytes, at + 2).toLong() shl 16)

/**
 * Overwrites a little-endian 16-bit value.
 *
 * @param ByteArray bytes The buffer.
 * @param Int       at    Offset of the low byte.
 * @param Int       value The value.
 */
internal fun writeU16Le(bytes: ByteArray, at: Int, value: Int) {
	bytes[at] = value.toByte()
	bytes[at + 1] = (value ushr 8).toByte()
}

/**
 * Overwrites a little-endian 32-bit value.
 *
 * @param ByteArray bytes The buffer.
 * @param Int       at    Offset of the low byte.
 * @param Long      value The value.
 */
internal fun writeU32Le(bytes: ByteArray, at: Int, value: Long) {
	writeU16Le(bytes, at, (value and 0xFFFF).toInt())
	writeU16Le(bytes, at + 2, ((value ushr 16) and 0xFFFF).toInt())
}

/**
 * The offset of the end of central directory record of an archive with no comment.
 *
 * @param ByteArray archive The archive.
 * @return Int The record's offset.
 */
internal fun endRecordOffset(archive: ByteArray): Int = archive.size - ZipRecords.END_SIZE

/**
 * The offset of the central directory, read from an archive with no comment.
 *
 * @param ByteArray archive The archive.
 * @return Int The directory's offset.
 */
internal fun centralDirectoryOffset(archive: ByteArray): Int = readU32Le(archive, endRecordOffset(archive) + 16).toInt()

/**
 * The offset of the central header of the entry at [entryIndex].
 *
 * @param ByteArray archive    The archive, with no comment.
 * @param Int       entryIndex The entry's position in the directory.
 * @return Int The header's offset.
 */
internal fun centralHeaderOffset(archive: ByteArray, entryIndex: Int): Int {
	var position = centralDirectoryOffset(archive)
	repeat(entryIndex) {
		val nameLength = readU16Le(archive, position + 28)
		val extraLength = readU16Le(archive, position + 30)
		val commentLength = readU16Le(archive, position + 32)
		position += ZipRecords.CENTRAL_HEADER_SIZE + nameLength + extraLength + commentLength
	}
	return position
}

/**
 * The offset of the local header of the entry at [entryIndex].
 *
 * @param ByteArray archive    The archive, with no comment.
 * @param Int       entryIndex The entry's position in the directory.
 * @return Int The header's offset.
 */
internal fun localHeaderOffset(archive: ByteArray, entryIndex: Int): Int = readU32Le(archive, centralHeaderOffset(archive, entryIndex) + 42).toInt()

/**
 * An archive of the given entries, each stored or deflated as its flag says, stamped [TEST_DOS_DATE_TIME].
 *
 * @param List entries Entry name, contents, and whether to deflate.
 * @return ByteArray The archive.
 */
internal fun archiveOf(vararg entries: Triple<String, ByteArray, Boolean>): ByteArray {
	val writer = ZipWriter(TEST_DOS_DATE_TIME)
	for ((name, contents, deflated) in entries) {
		if (deflated) {
			writer.addDeflated(name, contents)
		} else {
			writer.addStored(name, contents)
		}
	}
	return writer.finish()
}