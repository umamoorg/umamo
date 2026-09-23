package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the pure-Kotlin CRC-32 against the standard check vector and the java.util.zip.CRC32 shape
 * it mirrors (accumulate, then read) - the checksum PNG and ZIP both stamp, so a drift here corrupts
 * every written container.
 */
class Crc32Test {
	/** The IEEE 802.3 check value: CRC-32 of the ASCII digits "123456789" is 0xCBF43926. */
	@Test
	fun standardCheckVectorMatches() {
		val checksum = Crc32()
		checksum.update("123456789".encodeToByteArray())
		assertEquals(0xCBF43926L, checksum.value)
	}

	/** Chunked accumulation equals a one-shot update over the same bytes. */
	@Test
	fun incrementalUpdateEqualsOneShot() {
		val chunked = Crc32()
		chunked.update("1234".encodeToByteArray())
		chunked.update("56789".encodeToByteArray())
		val oneShot = Crc32()
		oneShot.update("123456789".encodeToByteArray())
		assertEquals(oneShot.value, chunked.value)
	}

	/** No input yields the empty-message CRC of zero (the all-ones precondition inverted back). */
	@Test
	fun emptyInputYieldsZero() {
		assertEquals(0L, Crc32().value)
	}

	/**
	 * Slices of every length at every alignment agree with a bit-at-a-time reference, covering the
	 * eight-byte fast path, its byte-wise tail, and a slice that starts mid-buffer.
	 */
	@Test
	fun slicedUpdateMatchesBitwiseReference() {
		val bytes = ByteArray(64) { byteIndex -> (byteIndex * 151 + 7).toByte() }
		for (offset in 0 until 8) {
			for (length in 0..(bytes.size - offset)) {
				val checksum = Crc32()
				checksum.update(bytes, offset, length)
				assertEquals(bitwiseCrc32(bytes, offset, length), checksum.value, "offset $offset, length $length")
			}
		}
	}

	/**
	 * The CRC-32 computed one bit at a time straight from the reflected polynomial, sharing no table
	 * with [Crc32].
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int offset      Offset of the first byte.
	 * @param Int length      Number of bytes.
	 * @return Long The checksum, as an unsigned 32-bit value.
	 */
	private fun bitwiseCrc32(bytes: ByteArray, offset: Int, length: Int): Long {
		var remainder = -1
		for (byteIndex in offset until offset + length) {
			remainder = remainder xor (bytes[byteIndex].toInt() and 0xFF)
			repeat(8) {
				remainder =
					if ((remainder and 1) != 0) {
						(remainder ushr 1) xor 0xEDB88320.toInt()
					} else {
						remainder ushr 1
					}
			}
		}
		return remainder.inv().toLong() and 0xFFFFFFFFL
	}
}