package org.umamo.ui.graphics

import androidx.compose.ui.graphics.ImageBitmap

/** How the RGBA bytes' alpha channel is to be interpreted when wrapped as an [ImageBitmap]. */
enum class RgbaAlphaType {
	/** Every texel is fully opaque; the alpha bytes are ignored. */
	Opaque,

	/** Straight (non-premultiplied) alpha: RGB is not scaled by A. */
	Straight,
}

/**
 * Wraps raw top-first RGBA bytes as a Compose [ImageBitmap].
 *
 * Platform seam: desktop wraps via Skiko's Image.makeRaster, Android via android.graphics.Bitmap —
 * both preserve straight alpha so a transparent texel's leftover matte RGB does not ghost through.
 *
 * @param ByteArray rgba The pixel bytes, 4 per texel, top row first.
 * @param Int width The image width in texels.
 * @param Int height The image height in texels.
 * @param RgbaAlphaType alphaType How the alpha channel is interpreted.
 * @return ImageBitmap The wrapped Compose bitmap.
 */
expect fun rgbaToImageBitmap(rgba: ByteArray, width: Int, height: Int, alphaType: RgbaAlphaType): ImageBitmap
