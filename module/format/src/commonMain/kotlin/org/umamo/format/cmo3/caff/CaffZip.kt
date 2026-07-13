package org.umamo.format.cmo3.caff

/**
 * Platform DEFLATE bridge for FAST/SMALL entries.
 *
 * EN: CAFF compressed blobs are a Java `ZipOutputStream` stream holding a single entry named
 *     "contents". The JVM/Android actual uses java.util.zip directly, so framing matches the
 *     editor exactly (the reader uses ZipInputStream; the writer uses ZipOutputStream).
 * JA: 圧縮ブロブは単一エントリ "contents" の Java Zip ストリーム。JVM/Android では java.util.zip を使用。
 *
 * @see <a href="https://docs.umamo.org/format/CMO3.md">CMO3.md §1 Compression</a>
 */
internal expect object CaffZip {
	/**
	 * Inflates a single-entry CAFF zip stream back to the raw payload.
	 *
	 * @param ByteArray zipStream The full zip stream bytes (already de-obfuscated).
	 * @return ByteArray The decompressed "contents" payload.
	 */
	fun unzipSingle(zipStream: ByteArray): ByteArray

	/**
	 * Wraps [contents] in a single-entry ("contents") zip stream at the given level.
	 *
	 * @param ByteArray contents The raw payload to compress.
	 * @param Int       level    DEFLATE level (CompressOption.zipLevel).
	 * @return ByteArray The zip stream bytes (before any obfuscation).
	 */
	fun zipSingle(contents: ByteArray, level: Int): ByteArray
}
