package org.umamo.render

import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/**
 * Desktop actual: decodes PNG bytes via javax.imageio into a top-first RGBA [DecodedImage].
 *
 * @param ByteArray? png The PNG bytes.
 * @return DecodedImage? The decoded RGBA image.
 */
actual fun decodePngToRgba(png: ByteArray?): DecodedImage? {
	if (png == null) {
		return null
	}
	val image = ImageIO.read(ByteArrayInputStream(png)) ?: return null
	val width = image.width
	val height = image.height
	val argb = IntArray(width * height)
	image.getRGB(0, 0, width, height, argb, 0, width) // top-first, packed INT_ARGB
	val rgba = ByteArray(width * height * 4)
	for (pixelIndex in argb.indices) {
		val packed = argb[pixelIndex]
		val base = pixelIndex * 4
		rgba[base] = (packed ushr 16).toByte() // R
		rgba[base + 1] = (packed ushr 8).toByte() // G
		rgba[base + 2] = packed.toByte() // B
		rgba[base + 3] = (packed ushr 24).toByte() // A
	}
	return DecodedImage(rgba, width, height)
}
