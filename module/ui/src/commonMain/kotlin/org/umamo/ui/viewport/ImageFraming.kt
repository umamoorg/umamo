package org.umamo.ui.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.FrameBackdrop
import org.umamo.render.ViewportCamera
import org.umamo.ui.graphics.parseHexColor
import kotlin.math.ceil
import kotlin.math.roundToInt

/*
 * What an image captured from the puppet renderer shows and at what size: Export Image's region, scale,
 * and background, resolved into the camera and pixel size the renderer draws.  Pure, so the framing
 * rules test without a GPU; the render itself is PuppetViewportService.renderImage.
 */

/**
 * The longest edge an image capture may have, in pixels.  The GPU renders any size in tiles, so this is
 * a CPU-memory bound: the stitched image is width x height x 4 bytes, a gigabyte at this edge squared.
 */
const val MAX_IMAGE_EDGE = 16384

/**
 * The part of the world an image capture frames.
 */
enum class ImageRegion {
	/** Exactly what a 2D viewport area shows, at its own framing. */
	View,

	/** The document's canvas rectangle. */
	Canvas,

	/** The shown drawables' extent at the current pose, cropped tight. */
	Content,
}

/**
 * What an image capture is drawn over.
 */
enum class ImageBackground {
	/** Nothing: every pixel the puppet does not cover is fully transparent. */
	Transparent,

	/** A flat color, which may itself be translucent. */
	Solid,

	/** The themed grid and world axes, as the viewport shows them. */
	Grid,
}

/**
 * The Export Image dialog's choices.
 *
 * @property ImageRegion     region        The part of the world to frame.
 * @property Float           scalePercent  The size relative to the region's own: the area's pixels for View,
 *   one pixel per world unit for Canvas and Content.  100 is 1:1.
 * @property ImageBackground background    What the puppet is drawn over.
 * @property String          solidColorHex The Solid background's color as canonical "#AARRGGBB", kept while
 *   another background is chosen so switching back finds it again.
 */
data class ImageExportOptions(
	val region: ImageRegion,
	val scalePercent: Float,
	val background: ImageBackground,
	val solidColorHex: String,
) {
	companion object {
		/** A transparent, tightly cropped capture of the posed puppet at 1:1: the image most often wanted. */
		val Default: ImageExportOptions = ImageExportOptions(ImageRegion.Content, 100f, ImageBackground.Transparent, "#FFFFFFFF")
	}
}

/**
 * The renderer backdrop these options draw over.  A Solid color is premultiplied here, since the
 * renderer's framebuffer is; an unparseable color falls back to opaque white.
 *
 * @return FrameBackdrop The backdrop.
 */
fun ImageExportOptions.frameBackdrop(): FrameBackdrop =
	when (background) {
		ImageBackground.Transparent -> FrameBackdrop.Transparent
		ImageBackground.Grid -> FrameBackdrop.Grid
		ImageBackground.Solid -> {
			val color = parseHexColor(solidColorHex)
			if (color == null) {
				FrameBackdrop.Clear(1f, 1f, 1f, 1f)
			} else {
				FrameBackdrop.Clear(color.red * color.alpha, color.green * color.alpha, color.blue * color.alpha, color.alpha)
			}
		}
	}

/**
 * A resolved capture: the camera the renderer draws through and the image's size.
 *
 * @property ViewportCamera camera The world point at the image's center, and output pixels per world unit.
 * @property Int            width  The image width in pixels.
 * @property Int            height The image height in pixels.
 */
data class ImageFrame(
	val camera: ViewportCamera,
	val width: Int,
	val height: Int,
)

/**
 * The outcome of framing a capture: the frame, or why there is none.
 */
sealed interface ImageFrameResult {
	/**
	 * The capture can be drawn.
	 *
	 * @property ImageFrame frame The frame to draw.
	 */
	data class Framed(val frame: ImageFrame) : ImageFrameResult

	/** View was asked for, but no 2D viewport has been touched. */
	data object NoViewport : ImageFrameResult

	/** Canvas was asked for, but the document has no canvas. */
	data object NoCanvas : ImageFrameResult

	/** Content was asked for, but nothing shown has any geometry. */
	data object NothingVisible : ImageFrameResult

	/**
	 * The image would pass [MAX_IMAGE_EDGE] on an edge.
	 *
	 * @property Int width  The width it would have.
	 * @property Int height The height it would have.
	 */
	data class TooLarge(val width: Int, val height: Int) : ImageFrameResult
}

/**
 * Frames a capture of [region] at [scale].
 *
 * View keeps the area's own center and scales its zoom and size together, so at 1 it is the area pixel for
 * pixel.  Canvas and Content center on their rectangle and draw [scale] pixels per world unit, rounding the
 * size up to whole pixels so nothing at the rectangle's edge is cut.
 *
 * @param ImageRegion    region        The part of the world to frame.
 * @param Float          scale         The size multiplier, above zero (1 is 1:1).
 * @param ImageFrame?    areaView      The 2D viewport area's own frame, or null when none has been touched.
 * @param ContentBounds? canvasBounds  The canvas rectangle in world space, or null when there is none.
 * @param ContentBounds? contentBounds The shown content's extent at the current pose, or null when none.
 * @return ImageFrameResult The frame, or why there is none.
 */
fun resolveImageFrame(
	region: ImageRegion,
	scale: Float,
	areaView: ImageFrame?,
	canvasBounds: ContentBounds?,
	contentBounds: ContentBounds?,
): ImageFrameResult {
	require(scale > 0f) { "an image scale must be positive, got $scale" }
	val frame =
		when (region) {
			ImageRegion.View -> {
				val view = areaView ?: return ImageFrameResult.NoViewport
				ImageFrame(
					view.camera.copy(zoom = view.camera.zoom * scale),
					(view.width * scale).roundToInt().coerceAtLeast(1),
					(view.height * scale).roundToInt().coerceAtLeast(1),
				)
			}

			ImageRegion.Canvas -> framedBounds(canvasBounds ?: return ImageFrameResult.NoCanvas, scale)
			ImageRegion.Content -> framedBounds(contentBounds ?: return ImageFrameResult.NothingVisible, scale)
		}
	if (frame.width > MAX_IMAGE_EDGE || frame.height > MAX_IMAGE_EDGE) {
		return ImageFrameResult.TooLarge(frame.width, frame.height)
	}
	return ImageFrameResult.Framed(frame)
}

/**
 * Frames [bounds] into an [edge] x [edge] square: its longer side fills the square and the shorter one is
 * centered, the rest left to the backdrop.  The shape UMA's saved thumbnail takes (docs/format/UMA.md § 5.6).
 *
 * @param ContentBounds bounds The world rectangle to fit.
 * @param Int           edge   The square's edge in pixels, at least 1.
 * @return ImageFrame The square frame.
 */
fun fitSquare(bounds: ContentBounds, edge: Int): ImageFrame {
	require(edge > 0) { "a square frame needs a positive edge, got $edge" }
	val zoom = edge / maxOf(bounds.width, bounds.height)
	return ImageFrame(ViewportCamera(bounds.minX + bounds.width / 2f, bounds.minY + bounds.height / 2f, zoom), edge, edge)
}

/**
 * A world rectangle framed at [zoom] pixels per unit, centered, sized up to whole pixels.
 *
 * @param ContentBounds bounds The rectangle.
 * @param Float         zoom   Output pixels per world unit.
 * @return ImageFrame The frame.
 */
private fun framedBounds(bounds: ContentBounds, zoom: Float): ImageFrame =
	ImageFrame(
		ViewportCamera(bounds.minX + bounds.width / 2f, bounds.minY + bounds.height / 2f, zoom),
		wholePixels(bounds.width * zoom),
		wholePixels(bounds.height * zoom),
	)

/**
 * A pixel extent rounded up to whole pixels, forgiving the float noise that would otherwise turn an exact
 * 2000 into 2001, and never below one pixel.
 *
 * @param Float extent The extent in pixels.
 * @return Int The whole-pixel extent.
 */
private fun wholePixels(extent: Float): Int = ceil(extent - PIXEL_ROUNDING_SLACK).toInt().coerceAtLeast(1)

/** How far past a whole pixel an extent may be and still count as that whole pixel. */
private const val PIXEL_ROUNDING_SLACK = 1e-3f