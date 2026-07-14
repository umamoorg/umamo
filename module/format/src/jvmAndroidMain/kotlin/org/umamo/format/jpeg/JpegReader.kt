package org.umamo.format.jpeg

import org.umamo.format.FileKind
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.ReadOnlyRasterCodec

/**
 * JPEG reader - registered placeholder.
 *
 * Detects JPEG by magic so [org.umamo.format.FormatRegistry] advertises the format and routes a
 * `.jpg` to it, but the baseline decode (Huffman + IDCT + YCbCr->RGB, a TwelveMonkeys-style port)
 * is a follow-up session; [read] throws until it lands.
 */
public object JpegReader : ReadOnlyRasterCodec {
	override val kind: FileKind = FileKind.Jpeg

	/**
	 * True if [candidateBytes] opens with the JPEG SOI + marker prefix.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the JPEG magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean =
		// JPEG (JFIF/EXIF): SOI marker 0xFFD8 followed by a further 0xFF marker byte.
		candidateBytes.size >= 3 &&
			candidateBytes[0] == 0xFF.toByte() &&
			candidateBytes[1] == 0xD8.toByte() &&
			candidateBytes[2] == 0xFF.toByte()

	/**
	 * Not yet implemented - see the class note.
	 *
	 * @param ByteArray bytes The complete `.jpg` file.
	 * @return RasterImage Never returns.
	 */
	override fun read(bytes: ByteArray): RasterImage = throw NotImplementedError("JPEG decode is not yet implemented")
}
