package org.umamo.format.binary

import kotlin.test.Test
import kotlin.test.assertEquals

/** The content hash is SHA-256 as lowercase hex. */
class ContentHashTest {
	@Test
	fun theDigestIsSha256Hex() {
		// The published vectors for "abc" and the empty input.
		assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", contentHashOf("abc".encodeToByteArray()))
		assertEquals("e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855", contentHashOf(ByteArray(0)))
	}
}