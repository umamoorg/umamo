package org.umamo.format.binary

/*
 * Text encodings the binary readers need, in common code.
 *
 * `kotlin.text.Charsets` is JVM-only despite being a default import, so `String(bytes, US_ASCII)`
 * and `toByteArray(UTF_16BE)` cannot appear in commonMain.  These are the two conversions the
 * format readers actually use, implemented directly.  Kotlin's stdlib offers no common Charset
 * API, and `decodeToString()` is UTF-8 only — which is NOT a drop-in for either of these.
 */

/** Byte values above this are not representable in ASCII. */
private const val ASCII_MAX = 0x7F

/** What a malformed byte decodes to, matching the JVM's US_ASCII decoder. */
private const val REPLACEMENT_CHARACTER = '�'

/**
 * Decodes [length] bytes of [bytes] at [offset] as US-ASCII.
 *
 * Matches the JVM's `String(bytes, offset, length, Charsets.US_ASCII)` exactly, including mapping any
 * byte outside 0x00..0x7F to U+FFFD.  That fidelity matters: the naive alternative (`Byte.toChar()`)
 * would silently decode high bytes as Latin-1, so a corrupt signature could compare equal to
 * something it is not.  Note `decodeToString()` is not a substitute — it decodes UTF-8.
 *
 * @param ByteArray bytes The buffer to read from.
 * @param Int offset      Offset of the first byte.
 * @param Int length      Number of bytes to decode.
 * @return String The decoded text.
 */
internal fun decodeAscii(bytes: ByteArray, offset: Int, length: Int): String {
	val characters = CharArray(length)
	for (characterIndex in 0 until length) {
		val byteValue = bytes[offset + characterIndex].toInt() and 0xFF
		characters[characterIndex] = if (byteValue <= ASCII_MAX) byteValue.toChar() else REPLACEMENT_CHARACTER
	}
	return characters.concatToString()
}

/**
 * Encodes [text] as UTF-16 big-endian.
 *
 * Matches the JVM's `toByteArray(Charsets.UTF_16BE)`: a Kotlin `Char` is already a UTF-16 code unit,
 * so writing each one big-endian is UTF-16BE by construction (surrogate pairs included, since they are
 * simply two code units).  No byte-order mark is emitted, which is what UTF_16BE means.
 *
 * @param String text The text to encode.
 * @return ByteArray The UTF-16BE bytes, two per code unit.
 */
internal fun encodeUtf16Be(text: String): ByteArray {
	val encoded = ByteArray(text.length * 2)
	for (characterIndex in text.indices) {
		val codeUnit = text[characterIndex].code
		encoded[characterIndex * 2] = ((codeUnit ushr 8) and 0xFF).toByte()
		encoded[characterIndex * 2 + 1] = (codeUnit and 0xFF).toByte()
	}
	return encoded
}
