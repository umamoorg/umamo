package org.umamo.render

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.nio.ByteBuffer

/**
 * Android actual: decodes PNG bytes via BitmapFactory into a top-first RGBA [DecodedImage].
 *
 * Decodes non-premultiplied so the byte stream matches the desktop ImageIO decode (straight
 * alpha). ARGB_8888's in-memory layout is RGBA byte order, so copyPixelsToBuffer yields the
 * exact stream GL upload expects. getPixels is unusable here: it throws on bitmaps decoded
 * with inPremultiplied = false.
 *
 * @param ByteArray? png The PNG bytes.
 * @return DecodedImage? The decoded RGBA image.
 */
actual fun decodePngToRgba(png: ByteArray?): DecodedImage? {
	if (png == null) {
		return null
	}
	val options =
		BitmapFactory.Options().apply {
			inPreferredConfig = Bitmap.Config.ARGB_8888
			inPremultiplied = false
		}
	val bitmap = BitmapFactory.decodeByteArray(png, 0, png.size, options) ?: return null
	val width = bitmap.width
	val height = bitmap.height
	val rowStride = bitmap.rowBytes
	val buffer = ByteBuffer.allocate(rowStride * height)
	bitmap.copyPixelsToBuffer(buffer)
	bitmap.recycle()
	val copied = buffer.array()
	if (rowStride == width * 4) {
		return DecodedImage(copied, width, height)
	}
	// Rare row padding (rowBytes > width*4): repack to the tight RGBA stream GL upload expects.
	val rgba = ByteArray(width * height * 4)
	for (rowIndex in 0 until height) {
		copied.copyInto(
			destination = rgba,
			destinationOffset = rowIndex * width * 4,
			startIndex = rowIndex * rowStride,
			endIndex = rowIndex * rowStride + width * 4,
		)
	}
	return DecodedImage(rgba, width, height)
}
