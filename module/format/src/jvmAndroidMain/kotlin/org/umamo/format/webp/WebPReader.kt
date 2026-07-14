package org.umamo.format.webp

import org.umamo.format.FileKind
import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.ReadOnlyRasterCodec

/**
 * WebP reader - registered placeholder.
 *
 * Detects WebP by its RIFF container magic so [org.umamo.format.FormatRegistry] advertises the
 * format, but decode (lossless VP8L first, lossy VP8 later - a substantial pure-Kotlin effort) is
 * a follow-up session; [read] throws until it lands.
 */
public object WebPReader : ReadOnlyRasterCodec {
	override val kind: FileKind = FileKind.WebP

	/**
	 * True if [candidateBytes] is a RIFF container tagged "WEBP".
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the WebP magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean {
		// WebP: 'RIFF' @ +0x00, 4-byte file size, then the 'WEBP' form type @ +0x08.
		if (candidateBytes.size < 12) {
			return false
		}
		val riff =
			candidateBytes[0] == 0x52.toByte() && candidateBytes[1] == 0x49.toByte() && candidateBytes[2] == 0x46.toByte() && candidateBytes[3] == 0x46.toByte()
		val webp =
			candidateBytes[8] == 0x57.toByte() && candidateBytes[9] == 0x45.toByte() && candidateBytes[10] == 0x42.toByte() && candidateBytes[11] == 0x50.toByte()
		return riff && webp
	}

	/**
	 * Not yet implemented - see the class note.
	 *
	 * @param ByteArray bytes The complete `.webp` file.
	 * @return RasterImage Never returns.
	 */
	override fun read(bytes: ByteArray): RasterImage = throw NotImplementedError("WebP decode is not yet implemented")
}
