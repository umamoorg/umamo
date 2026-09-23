package org.umamo.format.raster

/*
 * Alpha conversions over the neutral raster, for pixels read back from a renderer: a GPU framebuffer
 * holds premultiplied color, while [RasterImage] and every file format that carries it (PNG first)
 * hold straight alpha.
 */

/**
 * Converts premultiplied RGBA to straight alpha, returning a new raster.
 *
 * Each color channel divides back out by the pixel's alpha, rounded to nearest and clamped at 255: a
 * premultiplied pixel whose color exceeds its alpha (an additive contribution over nothing) has no
 * straight form, and the clamp keeps its hue as close as the byte range allows.  A pixel with zero
 * alpha has no color at all, so it becomes transparent black.
 *
 * @return RasterImage The same pixels, straight alpha, top row first.
 */
public fun RasterImage.premultipliedToStraight(): RasterImage {
	val straight = ByteArray(rgba.size)
	var offset = 0
	while (offset < rgba.size) {
		val alpha = rgba[offset + 3].toInt() and 0xFF
		if (alpha != 0) {
			straight[offset] = unpremultiplied(rgba[offset].toInt() and 0xFF, alpha).toByte()
			straight[offset + 1] = unpremultiplied(rgba[offset + 1].toInt() and 0xFF, alpha).toByte()
			straight[offset + 2] = unpremultiplied(rgba[offset + 2].toInt() and 0xFF, alpha).toByte()
			straight[offset + 3] = alpha.toByte()
		}
		offset += 4
	}
	return RasterImage(width, height, straight)
}

/**
 * Returns a copy of this raster with every pixel's alpha set to 255 and its color left as it is.
 *
 * For pixels rendered over an opaque background, where premultiplied and straight color coincide and
 * any alpha short of 255 is only what a blend left in the channel, not coverage.
 *
 * @return RasterImage The same color, fully opaque.
 */
public fun RasterImage.withOpaqueAlpha(): RasterImage {
	val opaque = rgba.copyOf()
	var alphaOffset = 3
	while (alphaOffset < opaque.size) {
		opaque[alphaOffset] = 0xFF.toByte()
		alphaOffset += 4
	}
	return RasterImage(width, height, opaque)
}

/**
 * One premultiplied channel divided back out by its alpha, rounded to nearest and clamped to a byte.
 *
 * @param Int component The premultiplied channel, 0..255.
 * @param Int alpha     The pixel's alpha, 1..255.
 * @return Int The straight channel, 0..255.
 */
private fun unpremultiplied(component: Int, alpha: Int): Int = minOf(255, (component * 255 + alpha / 2) / alpha)