package org.umamo.format.raster

/*
 * Platform zlib bridge for the raster codecs.
 *
 * PNG's IDAT stream and TIFF's Deflate compression are both plain zlib datastreams (RFC 1950:
 * header + deflate + Adler-32).  Everything else in the raster codecs is pure Kotlin, so this is
 * the single seam that keeps them off commonMain; isolating it here means the codecs themselves
 * compile for every target and a new platform only has to supply these two functions.
 * The JVM/Android actual uses java.util.zip.  This is deliberately NOT CaffZip: that bridge is
 * shaped around a ZIP container holding an entry named "contents", whereas these are raw zlib
 * streams.
 */

/**
 * Inflates the zlib stream at [sourceOffset] into its decompressed bytes.
 *
 * Best-effort by design: a truncated or corrupt stream yields the bytes recovered so far rather than
 * throwing, matching the other readers and letting a partly damaged image still decode.
 *
 * @param ByteArray source  The buffer holding the zlib stream.
 * @param Int sourceOffset  Offset of the stream's first byte.
 * @param Int sourceLength  Compressed byte count.
 * @param Int maximumSize   An upper bound on the output, so a malformed stream cannot inflate without
 *                          limit; pass Int.MAX_VALUE when the size is genuinely unknown.
 * @return ByteArray The inflated bytes.
 */
internal expect fun inflateZlib(source: ByteArray, sourceOffset: Int, sourceLength: Int, maximumSize: Int): ByteArray

/**
 * Compresses [source] to a zlib stream at the default compression level.
 *
 * @param ByteArray source The raw bytes to compress.
 * @return ByteArray The zlib-wrapped deflate stream.
 */
internal expect fun deflateZlib(source: ByteArray): ByteArray
