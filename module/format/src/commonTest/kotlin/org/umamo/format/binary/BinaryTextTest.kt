package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals

/**
 * Pins the common-code text conversions against the JVM charset behavior they replicate: US-ASCII
 * decode with U+FFFD for high bytes (NOT Latin-1), and BOM-less UTF-16BE encode.
 */
class BinaryTextTest {
	/** Bytes above 0x7F decode to the replacement character, matching the JVM's US_ASCII decoder. */
	@Test
	fun decodeAsciiMapsHighBytesToReplacementCharacter() {
		val bytes = byteArrayOf(0x41, 0xC3.toByte(), 0x42)
		assertEquals("A�B", decodeAscii(bytes, 0, 3))
	}

	/** The offset/length window decodes exactly the requested slice. */
	@Test
	fun decodeAsciiHonorsTheOffsetWindow() {
		val bytes = "XhelloY".encodeToByteArray()
		assertEquals("hello", decodeAscii(bytes, 1, 5))
	}

	/** Each code unit becomes two big-endian bytes with no byte-order mark. */
	@Test
	fun encodeUtf16BeEmitsTwoBigEndianBytesPerCodeUnit() {
		assertContentEquals(byteArrayOf(0x00, 0x41, 0x00, 0x42), encodeUtf16Be("AB"))
		assertContentEquals(byteArrayOf(0x30, 0x42), encodeUtf16Be("あ"), "a non-ASCII code unit keeps its high byte first")
	}

	/** An astral-plane character passes through as its two surrogate code units (four bytes). */
	@Test
	fun encodeUtf16BePassesSurrogatePairsThrough() {
		assertContentEquals(
			byteArrayOf(0xD8.toByte(), 0x3D, 0xDE.toByte(), 0x00),
			encodeUtf16Be("😀"),
		)
	}
}
