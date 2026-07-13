package org.umamo.ui.graphics

import android.graphics.Bitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap

/**
 * Android actual: packs the RGBA bytes into non-premultiplied ARGB ints and wraps them in a Bitmap.
 * setPixels takes non-premultiplied colors and premultiplies internally — visually equivalent for
 * previews/thumbnails, which is this seam's use.
 *
 * @param ByteArray rgba The pixel bytes, 4 per texel, top row first.
 * @param Int width The image width in texels.
 * @param Int height The image height in texels.
 * @param RgbaAlphaType alphaType How the alpha channel is interpreted.
 * @return ImageBitmap The wrapped Compose bitmap.
 */
actual fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int, alphaType: RgbaAlphaType): ImageBitmap {
	val argb = IntArray(width * height)
	for (pixelIndex in argb.indices) {
		val base = pixelIndex * 4
		val red = rgba[base].toInt() and 0xFF
		val green = rgba[base + 1].toInt() and 0xFF
		val blue = rgba[base + 2].toInt() and 0xFF
		val alpha =
			when (alphaType) {
				RgbaAlphaType.Opaque -> 0xFF
				RgbaAlphaType.Straight -> rgba[base + 3].toInt() and 0xFF
			}
		argb[pixelIndex] = (alpha shl 24) or (red shl 16) or (green shl 8) or blue
	}
	val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
	bitmap.setPixels(argb, 0, width, 0, 0, width, height)
	return bitmap.asImageBitmap()
}
