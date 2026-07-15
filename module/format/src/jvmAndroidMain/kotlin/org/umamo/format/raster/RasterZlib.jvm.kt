package org.umamo.format.raster

import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * The JVM/Android actual of the raster zlib bridge: java.util.zip, which both targets ship, so PNG and
 * TIFF Deflate decode byte-identically on desktop and Android.  See RasterZlib.kt for the contract.
 */

/**
 * Inflates the zlib stream at [sourceOffset] using java.util.zip.
 *
 * @param ByteArray source  The buffer holding the zlib stream.
 * @param Int sourceOffset  Offset of the stream's first byte.
 * @param Int sourceLength  Compressed byte count.
 * @param Int maximumSize   An upper bound on the output.
 * @return ByteArray The inflated bytes.
 */
internal actual fun inflateZlib(source: ByteArray, sourceOffset: Int, sourceLength: Int, maximumSize: Int): ByteArray {
	if (sourceLength <= 0 || sourceOffset >= source.size || maximumSize <= 0) {
		return ByteArray(0)
	}
	val available = minOf(sourceLength, source.size - sourceOffset)
	val inflater = Inflater()
	inflater.setInput(source, sourceOffset, available)
	val out = ByteBuilder(minOf(available * 3, maximumSize).coerceAtLeast(64))
	val chunk = ByteArray(64 * 1024)
	try {
		while (!inflater.finished() && out.size < maximumSize) {
			val wanted = minOf(chunk.size, maximumSize - out.size)
			val produced = inflater.inflate(chunk, 0, wanted)
			if (produced == 0) {
				if (inflater.finished() || inflater.needsInput() || inflater.needsDictionary()) {
					break
				}
			} else {
				out.writeBytes(chunk, 0, produced)
			}
		}
	} catch (_: DataFormatException) {
		// Best-effort: return what inflated so far rather than failing the whole decode.
	} finally {
		inflater.end()
	}
	return out.toByteArray()
}

/**
 * Compresses [source] to a zlib stream using java.util.zip.
 *
 * @param ByteArray source The raw bytes to compress.
 * @return ByteArray The zlib-wrapped deflate stream.
 */
internal actual fun deflateZlib(source: ByteArray): ByteArray {
	val deflater = Deflater(Deflater.DEFAULT_COMPRESSION)
	deflater.setInput(source)
	deflater.finish()
	val out = ByteBuilder(source.size / 2 + 64)
	val chunk = ByteArray(64 * 1024)
	while (!deflater.finished()) {
		val produced = deflater.deflate(chunk)
		out.writeBytes(chunk, 0, produced)
	}
	deflater.end()
	return out.toByteArray()
}
