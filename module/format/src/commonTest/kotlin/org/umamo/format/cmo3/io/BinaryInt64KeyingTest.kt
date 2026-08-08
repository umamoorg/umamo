package org.umamo.format.cmo3.io

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the 64-bit obfuscation mask to the editor's semantics: ((long) key << 32) | key with a
 * SIGN-EXTENDING OR operand, so a negative key floods the high dword with ones.
 *
 * Corpus proof: every negative-key official file stores 0xFFFFFFFF as the raw high dword of its
 * sub-4GB entry offsets (miku.cmo3, modelB.cmo3, ...), which only the sign-extended mask
 * produces.  A zero-extended mask round-trips through Umamo's own symmetric reader while the
 * official reader decodes garbage offsets and reports the file as "Premature end of file" - the
 * failure is invisible to every decompressed-content gate, so this byte-level pin is the guard.
 */
class BinaryInt64KeyingTest {
	/**
	 * Encodes [value] with [key] through BinaryWriter and returns the raw big-endian bytes.
	 *
	 * @param Long value The value to encode.
	 * @param Int  key   The obfuscation key.
	 * @return ByteArray The eight raw bytes.
	 */
	private fun rawBytes(value: Long, key: Int): ByteArray {
		val writer = BinaryWriter()
		writer.writeInt64(value, key)
		return writer.toByteArray()
	}

	@Test
	fun negativeKeyFloodsTheHighDwordLikeTheEditor() {
		// miku.cmo3's key; its entry table stores ffffffff high dwords for every offset.
		val key = -1141606607
		val raw = rawBytes(0x12345678L, key)
		val highDword =
			(0 until 4).fold(0L) { accumulated, byteIndex -> (accumulated shl 8) or (raw[byteIndex].toLong() and 0xFF) }
		// value high dword is 0, so the raw high dword IS the mask's high dword: all ones.
		assertEquals(0xFFFFFFFFL, highDword, "negative key must sign-extend into the high dword")
	}

	@Test
	fun positiveKeyKeepsTheKeyInTheHighDword() {
		val key = 0x49C74776
		val raw = rawBytes(0x12345678L, key)
		val highDword =
			(0 until 4).fold(0L) { accumulated, byteIndex -> (accumulated shl 8) or (raw[byteIndex].toLong() and 0xFF) }
		assertEquals(key.toLong(), highDword, "positive key masks the high dword with the key itself")
	}

	@Test
	fun readerAndWriterStaySymmetricForBothSigns() {
		for (key in intArrayOf(0, 0x49C74776, -1141606607, -1, Int.MIN_VALUE)) {
			for (value in longArrayOf(0L, 455L, 23485317L, 0x1_0000_0000L)) {
				val reader = BinaryReader(rawBytes(value, key))
				assertEquals(value, reader.readInt64(key), "round trip for key=$key value=$value")
			}
		}
	}
}