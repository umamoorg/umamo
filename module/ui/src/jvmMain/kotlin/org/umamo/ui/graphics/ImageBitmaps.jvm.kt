package org.umamo.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import org.jetbrains.skia.ColorAlphaType
import org.jetbrains.skia.ColorType
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageInfo

/**
 * Desktop actual: wraps the RGBA bytes as a Skia raster image and bridges it to Compose.
 *
 * @param ByteArray rgba The pixel bytes, 4 per texel, top row first.
 * @param Int width The image width in texels.
 * @param Int height The image height in texels.
 * @param RgbaAlphaType alphaType How the alpha channel is interpreted.
 * @return ImageBitmap The wrapped Compose bitmap.
 */
actual fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int, alphaType: RgbaAlphaType): ImageBitmap {
	val skiaAlphaType =
		when (alphaType) {
			RgbaAlphaType.Opaque -> ColorAlphaType.OPAQUE
			RgbaAlphaType.Straight -> ColorAlphaType.UNPREMUL
		}
	val info = ImageInfo(width, height, ColorType.RGBA_8888, skiaAlphaType)
	return Image.makeRaster(info, rgba, width * 4).toComposeImageBitmap()
}
