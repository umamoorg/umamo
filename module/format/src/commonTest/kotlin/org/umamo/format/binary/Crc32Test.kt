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
}