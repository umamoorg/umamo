package org.umamo.render.puppet

import org.umamo.render.ViewportCamera
import org.umamo.render.device.ScissorRect
import org.umamo.render.device.WorldToNdc
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Pins [scissorRectOf] and [intersectScissor]: where a composite layer's work is confined.
 *
 * An undersized rectangle clips rendered pixels and an oversized one only costs fill, so every case here
 * is about the rectangle never coming out smaller than the bounds it was built from.
 */
class ScissorRectsTest {
	/**
	 * The affine the renderer projects through for a camera centered on the world origin.
	 *
	 * @param Float zoom         Framebuffer pixels per world unit.
	 * @param Int   viewportSize The square viewport's edge in pixels.
	 * @return WorldToNdc The affine.
	 */
	private fun affineOf(zoom: Float, viewportSize: Int): WorldToNdc {
		val transform = ViewportCamera(0f, 0f, zoom).worldToNdc(viewportSize, viewportSize)

		return WorldToNdc(transform[0], transform[1], transform[2], transform[3])
	}

	/** Bounds inside the viewport map to their pixel rectangle, rows counted from the top, plus the pad. */
	@Test
	fun boundsMapToTheirPaddedPixelRectangle() {
		// World x in [-16, 16] is columns 16 to 48; world Z in [8, 30] is up, so rows 2 to 24 from the top.
		val rect = scissorRectOf(PosedAabb(-16f, 8f, 16f, 30f), affineOf(zoom = 1f, viewportSize = 64), 64, 64, pixelScale = 1f)

		assertEquals(ScissorRect(x = 14, y = 0, width = 36, height = 26), rect)
	}

	/** The pad is a constant on-screen size, so a supersampled render pads by as many framebuffer pixels more. */
	@Test
	fun thePadScalesWithThePixelScale() {
		// The same view at 2x: every pixel edge doubles, and so does the pad.
		val rect = scissorRectOf(PosedAabb(-16f, -30f, 16f, -8f), affineOf(zoom = 2f, viewportSize = 128), 128, 128, pixelScale = 2f)

		assertEquals(ScissorRect(x = 28, y = 76, width = 72, height = 52), rect)
	}

	/** Bounds that land entirely off the viewport have no rectangle. */
	@Test
	fun boundsOffTheViewportHaveNoRectangle() {
		val affine = affineOf(zoom = 1f, viewportSize = 64)

		assertNull(scissorRectOf(PosedAabb(100f, -8f, 200f, 8f), affine, 64, 64, pixelScale = 1f), "off the right edge")
		assertNull(scissorRectOf(PosedAabb(-8f, 100f, 8f, 200f), affine, 64, 64, pixelScale = 1f), "off the top edge")
	}

	/** Bounds partly off the viewport are clamped to it. */
	@Test
	fun boundsPartlyOffTheViewportAreClamped() {
		val rect = scissorRectOf(PosedAabb(16f, -8f, 200f, 8f), affineOf(zoom = 1f, viewportSize = 64), 64, 64, pixelScale = 1f)

		assertEquals(ScissorRect(x = 46, y = 22, width = 18, height = 20), rect)
	}

	/**
	 * Bounds whose pixel edges fall outside Int range cover the viewport.  Converting to Int before
	 * clamping would saturate and then wrap when the pad was applied, leaving no rectangle at all.
	 */
	@Test
	fun boundsPastIntRangeCoverTheViewport() {
		val affine = affineOf(zoom = 1f, viewportSize = 64)
		val whole = ScissorRect(x = 0, y = 0, width = 64, height = 64)

		assertEquals(whole, scissorRectOf(PosedAabb(-1e30f, -1e30f, 1e30f, 1e30f), affine, 64, 64, pixelScale = 1f), "edges past Int range")
		assertEquals(
			whole,
			scissorRectOf(
				PosedAabb(Float.NEGATIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.POSITIVE_INFINITY, Float.POSITIVE_INFINITY),
				affine,
				64,
				64,
				pixelScale = 1f,
			),
			"infinite edges",
		)
	}

	/** A missing rectangle stands for the whole viewport, so intersecting with it leaves the other one. */
	@Test
	fun intersectingWithNoRectangleKeepsTheOther() {
		val rect = ScissorRect(x = 4, y = 6, width = 10, height = 12)

		assertNull(intersectScissor(null, null))
		assertEquals(rect, intersectScissor(rect, null))
		assertEquals(rect, intersectScissor(null, rect))
	}

	/** Two overlapping rectangles intersect to the area they share. */
	@Test
	fun overlappingRectanglesIntersect() {
		val inner = ScissorRect(x = 10, y = 10, width = 30, height = 30)
		val outer = ScissorRect(x = 20, y = 0, width = 40, height = 25)

		assertEquals(ScissorRect(x = 20, y = 10, width = 20, height = 15), intersectScissor(inner, outer))
	}

	/** Rectangles that share no area fall back to the first, never to the whole viewport. */
	@Test
	fun disjointRectanglesFallBackToTheFirst() {
		val inner = ScissorRect(x = 0, y = 0, width = 10, height = 10)
		val outer = ScissorRect(x = 40, y = 40, width = 10, height = 10)

		assertEquals(inner, intersectScissor(inner, outer))
	}
}