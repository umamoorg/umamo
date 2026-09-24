package org.umamo.format.raster

/*
 * Alpha conversions over the neutral raster, for pixels read back from a renderer: a GPU framebuffer
 * holds premultiplied color, while [RasterImage] and every file format that carries it (PNG first)
 * hold straight alpha.
 */

/**
 * Converts this raster's premultiplied RGBA to straight alpha in place.
 *
 * In place because the raster is a renderer's read-back, owned by whoever asked for it: a capture can be
 * a gigabyte, and a converted copy beside it would double what it holds at its peak.
 *
 * Each color channel divides back out by the pixel's alpha, rounded to nearest and clamped at 255: a
 * premultiplied pixel whose color exceeds its alpha (an additive contribution over nothing) has no
 * straight form, and the clamp keeps its hue as close as the byte range allows.  A pixel with zero
 * alpha has no color at all, so it becomes transparent black.
 */
public fun RasterImage.unpremultiplyInPlace() {
	var offset = 0
	while (offset < rgba.size) {
		val alpha = rgba[offset + 3].toInt() and 0xFF
		if (alpha == 0) {
			rgba[offset] = 0
			rgba[offset + 1] = 0
			rgba[offset + 2] = 0
		} else if (alpha != 0xFF) {
			rgba[offset] = unpremultiplied(rgba[offset].toInt() and 0xFF, alpha).toByte()
			rgba[offset + 1] = unpremultiplied(rgba[offset + 1].toInt() and 0xFF, alpha).toByte()
			rgba[offset + 2] = unpremultiplied(rgba[offset + 2].toInt() and 0xFF, alpha).toByte()
		}
		offset += 4
	}
}

/**
 * Sets every pixel's alpha to 255 in place, leaving its color as it is.
 *
 * For pixels rendered over an opaque background, where premultiplied and straight color coincide and
 * any alpha short of 255 is only what a blend left in the channel, not coverage.  In place for the same
 * reason as [unpremultiplyInPlace].
 */
public fun RasterImage.setOpaqueAlphaInPlace() {
	var alphaOffset = 3
	while (alphaOffset < rgba.size) {
		rgba[alphaOffset] = 0xFF.toByte()
		alphaOffset += 4
	}
}

/**
 * One premultiplied channel divided back out by its alpha, rounded to nearest and clamped to a byte.
 *
 * @param Int component The premultiplied channel, 0..255.
 * @param Int alpha     The pixel's alpha, 1..255.
 * @return Int The straight channel, 0..255.
 */
private fun unpremultiplied(component: Int, alpha: Int): Int = minOf(255, (component * 255 + alpha / 2) / alpha)