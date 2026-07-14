package org.umamo.format.tiff

import org.umamo.format.FileKind
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.ReadOnlyRasterCodec

/**
 * TIFF reader - registered placeholder.
 *
 * Detects both byte orders of the TIFF header so [org.umamo.format.FormatRegistry] advertises the
 * format, but decode (a TwelveMonkeys-style port scoped to uncompressed / LZW / DEFLATE / PackBits
 * strips) is a follow-up session; [read] throws until it lands.
 */
public object TiffReader : ReadOnlyRasterCodec {
	override val kind: FileKind = FileKind.Tiff

	/**
	 * True if [candidateBytes] opens with a little- or big-endian TIFF header.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the TIFF magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean {
		if (candidateBytes.size < 4) {
			return false
		}
		// TIFF: 'II' 0x2A00 (little-endian) or 'MM' 0x002A (big-endian) header @ +0x00.
		val littleEndian =
			candidateBytes[0] == 0x49.toByte() && candidateBytes[1] == 0x49.toByte() && candidateBytes[2] == 0x2A.toByte() && candidateBytes[3] == 0x00.toByte()
		val bigEndian =
			candidateBytes[0] == 0x4D.toByte() && candidateBytes[1] == 0x4D.toByte() && candidateBytes[2] == 0x00.toByte() && candidateBytes[3] == 0x2A.toByte()
		return littleEndian || bigEndian
	}

	/**
	 * Not yet implemented - see the class note.
	 *
	 * @param ByteArray bytes The complete `.tiff` file.
	 * @return RasterImage Never returns.
	 */
	override fun read(bytes: ByteArray): RasterImage = throw NotImplementedError("TIFF decode is not yet implemented")
}
