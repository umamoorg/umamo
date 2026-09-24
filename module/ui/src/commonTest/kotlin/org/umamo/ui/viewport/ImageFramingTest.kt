package org.umamo.ui.viewport

import org.umamo.render.ContentBounds
import org.umamo.render.FrameBackdrop
import org.umamo.render.ViewportCamera
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * Export Image's framing rules: what each region captures, how the scale sizes it, and the refusals when a
 * region has nothing to frame or would be too large.
 */
class ImageFramingTest {
	private val areaView = ImageFrame(ViewportCamera(40f, -25f, 0.5f), 800, 600)

	// A 2000 x 3000 canvas, placed the way world space holds it: x in [0, 2000], z in [-3000, 0].
	private val canvas = ContentBounds(0f, -3000f, 2000f, 3000f)
	private val content = ContentBounds(100f, -900f, 300.5f, 600f)

	/**
	 * Frames with the fixture rectangles, unwrapping a successful frame.
	 *
	 * @param ImageRegion region The region.
	 * @param Float       scale  The size multiplier.
	 * @return ImageFrame The frame.
	 */
	private fun framed(region: ImageRegion, scale: Float): ImageFrame = assertIs<ImageFrameResult.Framed>(resolveImageFrame(region, scale, areaView, canvas, content)).frame

	/** View at 100% is the area's own frame, pixel for pixel. */
	@Test
	fun viewAtOneIsTheAreaPixelForPixel() {
		assertEquals(areaView, framed(ImageRegion.View, 1f))
	}

	/** View's scale grows the zoom and the size together and keeps the area's center. */
	@Test
	fun viewScalesItsZoomAndSizeTogetherAroundTheSameCenter() {
		assertEquals(ImageFrame(ViewportCamera(40f, -25f, 1f), 1600, 1200), framed(ImageRegion.View, 2f))
	}

	/** Canvas at 100% draws one pixel per canvas pixel, centered on the canvas. */
	@Test
	fun canvasAtOneIsOnePixelPerCanvasPixelCenteredOnTheCanvas() {
		assertEquals(ImageFrame(ViewportCamera(1000f, -1500f, 1f), 2000, 3000), framed(ImageRegion.Canvas, 1f))
	}

	/** Content rounds a fractional size up to whole pixels, so nothing at its edge is cut. */
	@Test
	fun contentRoundsUpToWholePixelsSoItsEdgesAreNotCut() {
		val frame = framed(ImageRegion.Content, 0.5f)
		// 300.5 x 0.5 = 150.25 rounds up to 151; 600 x 0.5 is exactly 300.
		assertEquals(151 to 300, frame.width to frame.height)
		assertEquals(ViewportCamera(250.25f, -600f, 0.5f), frame.camera)
	}

	/** Each region names what it lacks when it has nothing to frame. */
	@Test
	fun eachRegionRefusesWhenItHasNothingToFrame() {
		assertEquals(ImageFrameResult.NoViewport, resolveImageFrame(ImageRegion.View, 1f, null, canvas, content))
		assertEquals(ImageFrameResult.NoCanvas, resolveImageFrame(ImageRegion.Canvas, 1f, areaView, null, content))
		assertEquals(ImageFrameResult.NothingVisible, resolveImageFrame(ImageRegion.Content, 1f, areaView, canvas, null))
	}

	/** An image past the edge limit is refused, carrying the size it would have had. */
	@Test
	fun anImagePastTheEdgeLimitIsRefusedWithItsSize() {
		assertEquals(ImageFrameResult.TooLarge(12000, 18000), resolveImageFrame(ImageRegion.Canvas, 6f, areaView, canvas, content))
	}

	/** The thumbnail square is filled by the longer side, with the shorter one centered. */
	@Test
	fun aSquareFitFillsTheLongerSideAndCentersTheShorter() {
		val frame = fitSquare(content, 256)
		assertEquals(256 to 256, frame.width to frame.height)
		// The 600-unit height is the longer side, so it spans the 256 pixels.
		assertEquals(ViewportCamera(250.25f, -600f, 256f / 600f), frame.camera)
	}

	/** Each background choice maps onto the renderer backdrop it draws over. */
	@Test
	fun backgroundsMapToTheRendererBackdrop() {
		val options = ImageExportOptions.Default
		assertEquals(FrameBackdrop.Transparent, options.copy(background = ImageBackground.Transparent).frameBackdrop())
		assertEquals(FrameBackdrop.Grid, options.copy(background = ImageBackground.Grid).frameBackdrop())
		assertEquals(FrameBackdrop.Clear(1f, 0f, 0f, 1f), options.copy(background = ImageBackground.Solid, solidColorHex = "#FFFF0000").frameBackdrop())
	}

	/** A translucent solid color reaches the renderer premultiplied, as its framebuffer is. */
	@Test
	fun aTranslucentSolidColorIsPremultiplied() {
		val backdrop = assertIs<FrameBackdrop.Clear>(ImageExportOptions.Default.copy(background = ImageBackground.Solid, solidColorHex = "#80FFFFFF").frameBackdrop())
		assertEquals(backdrop.alpha, backdrop.red, 1e-6f)
		assertEquals(128f / 255f, backdrop.alpha, 1e-6f)
	}
}