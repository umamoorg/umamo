package org.umamo.render

import org.umamo.format.raster.RasterImage
import org.umamo.format.raster.setOpaqueAlphaInPlace
import org.umamo.format.raster.unpremultiplyInPlace

/**
 * What a puppet frame is drawn over.
 *
 * The viewport always draws over its grid; an image captured from the renderer chooses, because a
 * transparent or flat background is what makes the capture usable outside the editor.
 */
sealed interface FrameBackdrop {
	/**
	 * The themed grid backdrop the viewport shows, with the world-origin axes when the renderer has them
	 * turned on.
	 */
	data object Grid : FrameBackdrop

	/**
	 * A flat fill, as premultiplied 0..1 RGBA.  Transparent is all four at zero; an opaque color has alpha
	 * 1, where premultiplied and straight color are the same.  No grid and no axes are drawn.
	 *
	 * @property Float red   The fill's red.
	 * @property Float green The fill's green.
	 * @property Float blue  The fill's blue.
	 * @property Float alpha The fill's alpha.
	 */
	data class Clear(
		val red: Float,
		val green: Float,
		val blue: Float,
		val alpha: Float,
	) : FrameBackdrop

	companion object {
		/** Nothing at all: every pixel the puppet does not cover stays fully transparent. */
		val Transparent: FrameBackdrop = Clear(0f, 0f, 0f, 0f)
	}
}

/**
 * Turns a premultiplied capture drawn over [backdrop] into the straight-alpha image a file stores, in place.
 *
 * Over an opaque backdrop every pixel is opaque, so the color is already straight and only the alpha
 * channel is set to 255.  Over anything with coverage to spare (transparency, or a translucent fill) the
 * color divides back out by its alpha.
 *
 * Consumes the capture: its own buffer is converted and the same image returned, because a capture can be
 * a gigabyte and a converted copy would double that.  The caller must own the pixels.
 *
 * @param FrameBackdrop backdrop What the capture was drawn over.
 * @return RasterImage This image, now straight alpha, top row first.
 */
fun RasterImage.capturedOver(backdrop: FrameBackdrop): RasterImage {
	val opaque =
		when (backdrop) {
			FrameBackdrop.Grid -> true
			is FrameBackdrop.Clear -> backdrop.alpha >= 1f
		}
	if (opaque) {
		setOpaqueAlphaInPlace()
	} else {
		unpremultiplyInPlace()
	}
	return this
}