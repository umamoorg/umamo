package org.umamo.format.binary

/**
 * The CRC-32 lookup table for the reflected IEEE 802.3 polynomial (0xEDB88320), built once.
 *
 * Each entry is the CRC of a single byte value, so the update loop is one table lookup per byte.
 */
private val CRC32_TABLE: IntArray =
	IntArray(256) { tableIndex ->
		var remainder = tableIndex
		repeat(8) {
			remainder =
				if ((remainder and 1) != 0) {
					(remainder ushr 1) xor 0xEDB88320.toInt()
				} else {
					remainder ushr 1
				}
		}
		remainder
	}

/**
 * Eight chained CRC-32 tables for slicing-by-8, 256 entries each, laid end to end: table 0 is
 * [CRC32_TABLE], and entry i of table k is the CRC of byte i followed by k zero bytes.  Looking up
 * each of eight input bytes in its own table and XOR-ing the results advances the checksum by eight
 * bytes at once, with the eight lookups independent of each other instead of each waiting on the last.
 */
private val CRC32_SLICE_TABLES: IntArray =
	IntArray(8 * 256).also { tables ->
		CRC32_TABLE.copyInto(tables)
		for (tableIndex in 1 until 8) {
			for (byteValue in 0 until 256) {
				val shorter = tables[(tableIndex - 1) * 256 + byteValue]
				tables[tableIndex * 256 + byteValue] = (shorter ushr 8) xor CRC32_TABLE[shorter and 0xFF]
			}
		}
	}

/**
 * CRC-32 (IEEE 802.3), the checksum PNG stamps on every chunk (PNG spec §5.3) and ZIP on every entry
 * (APPNOTE.TXT 4.3.9) — hence a shared utility here rather than something a single codec owns.
 *
 * A pure-Kotlin equivalent of `java.util.zip.CRC32`, mirroring its shape (accumulate with [update],
 * read with [value]) so the codecs that use it stay in commonMain and compile for every target.
 */
internal class Crc32 {
	// The running remainder, pre-conditioned to all-ones per the standard.
	private var remainder = -1

	/**
	 * Folds every byte of [bytes] into the running checksum.
	 *
	 * @param ByteArray bytes The bytes to accumulate.
	 */
	fun update(bytes: ByteArray) {
		update(bytes, 0, bytes.size)
	}

	/**
	 * Folds [length] bytes of [bytes] starting at [offset] into the running checksum, so a caller can
	 * checksum a slice of a larger buffer without copying it out.
	 *
	 * @param ByteArray bytes The buffer holding the bytes.
	 * @param Int offset      Offset of the first byte to accumulate.
	 * @param Int length      Number of bytes to accumulate.
	 */
	fun update(bytes: ByteArray, offset: Int, length: Int) {
		val tables = CRC32_SLICE_TABLES
		val end = offset + length
		var running = remainder
		var byteIndex = offset
		while (end - byteIndex >= 8) {
			// The reflected CRC consumes bytes least-significant first, so the first four fold into the
			// running remainder as a little-endian word; the oldest byte takes the longest table.
			val firstWord =
				(bytes[byteIndex].toInt() and 0xFF) or
					((bytes[byteIndex + 1].toInt() and 0xFF) shl 8) or
					((bytes[byteIndex + 2].toInt() and 0xFF) shl 16) or
					((bytes[byteIndex + 3].toInt() and 0xFF) shl 24)
			val first = running xor firstWord
			running =
				tables[7 * 256 + (first and 0xFF)] xor
				tables[6 * 256 + ((first ushr 8) and 0xFF)] xor
				tables[5 * 256 + ((first ushr 16) and 0xFF)] xor
				tables[4 * 256 + (first ushr 24)] xor
				tables[3 * 256 + (bytes[byteIndex + 4].toInt() and 0xFF)] xor
				tables[2 * 256 + (bytes[byteIndex + 5].toInt() and 0xFF)] xor
				tables[256 + (bytes[byteIndex + 6].toInt() and 0xFF)] xor
				tables[bytes[byteIndex + 7].toInt() and 0xFF]
			byteIndex += 8
		}
		while (byteIndex < end) {
			running = CRC32_TABLE[(running xor bytes[byteIndex].toInt()) and 0xFF] xor (running ushr 8)
			byteIndex++
		}
		remainder = running
	}

	/** The checksum of everything accumulated so far, as an unsigned 32-bit value. */
	val value: Long
		get() = remainder.inv().toLong() and 0xFFFFFFFFL
}