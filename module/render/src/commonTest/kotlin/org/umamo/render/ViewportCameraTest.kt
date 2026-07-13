package org.umamo.render

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises the pure viewport-camera maths (no GL): fit framing, the world-to-NDC affine, cursor-pinned
 * zoom, grab pan, actual-size, and the zoom clamps. These are the invariants the interactive navigation
 * relies on, isolated so they verify without a render context.
 */
class ViewportCameraTest {
	private val tolerance = 1e-3f

	/** Maps a world point to a screen pixel (top-left origin) through a camera, for round-trip assertions. */
	private fun worldToScreen(camera: ViewportCamera, worldX: Float, worldY: Float, width: Int, height: Int): Pair<Float, Float> {
		val ndc = camera.worldToNdc(width, height)
		val ndcX = worldX * ndc[0] + ndc[2]
		val ndcY = worldY * ndc[1] + ndc[3]
		return Pair((ndcX + 1f) / 2f * width, (1f - ndcY) / 2f * height)
	}

	@Test
	fun fitCentersAndScalesContent() {
		val content = ContentBounds(minX = -100f, minY = -50f, width = 200f, height = 100f)
		val camera = ViewportCamera.fit(content, viewportWidth = 400, viewportHeight = 400)
		assertEquals(0f, camera.centerX, tolerance, "centre X = content centre")
		assertEquals(0f, camera.centerY, tolerance, "centre Y = content centre")
		// min(400/200, 400/100) = 2, times the 0.9 margin.
		assertEquals(0.9f * 2f, camera.zoom, tolerance, "fit zoom uses the tighter axis and the margin")
	}

	@Test
	fun worldToNdcMapsCentreToOriginAndOneUnitToZoomPixels() {
		val camera = ViewportCamera(centerX = 5f, centerY = -7f, zoom = 3f)
		val width = 200
		val height = 100
		val ndc = camera.worldToNdc(width, height)
		val centreX = camera.centerX * ndc[0] + ndc[2]
		val centreY = camera.centerY * ndc[1] + ndc[3]
		assertEquals(0f, centreX, tolerance, "camera centre maps to NDC origin")
		assertEquals(0f, centreY, tolerance, "camera centre maps to NDC origin")
		// One world unit right of centre lands zoom pixels right of the viewport centre.
		val (screenX, screenY) = worldToScreen(camera, camera.centerX + 1f, camera.centerY, width, height)
		assertEquals(width / 2f + camera.zoom, screenX, tolerance, "one world unit = zoom pixels")
		assertEquals(height / 2f, screenY, tolerance, "no vertical shift along a horizontal step")
	}

	@Test
	fun zoomAtCursorPinsTheWorldPointUnderTheCursor() {
		// Start on the grid (150%) so the +25% step lands cleanly at 175%, isolating the pin assertion.
		val camera = ViewportCamera(centerX = 10f, centerY = 20f, zoom = 1.5f)
		val width = 320
		val height = 240
		val cursorX = 250f
		val cursorY = 60f
		// The world point currently under the cursor (the camera's own inverse).
		val worldX = camera.centerX + (cursorX - width / 2f) / camera.zoom
		val worldY = camera.centerY + (height / 2f - cursorY) / camera.zoom
		val zoomed = camera.zoomAtCursorByPercent(deltaPercent = 25f, stepPercent = 1f, cursorXpx = cursorX, cursorYpx = cursorY, viewportWidth = width, viewportHeight = height)
		assertEquals(1.75f, zoomed.zoom, tolerance, "150% + 25 points = 175%")
		val (screenX, screenY) = worldToScreen(zoomed, worldX, worldY, width, height)
		assertEquals(cursorX, screenX, tolerance, "the pinned world point stays under the cursor X")
		assertEquals(cursorY, screenY, tolerance, "the pinned world point stays under the cursor Y")
	}

	@Test
	fun zoomSnapsOffGridFitToWholePercents() {
		// A fit ratio is arbitrary (41.3%); +1% steps must snap onto the integer-percent grid and reach 100.
		var camera = ViewportCamera(centerX = 0f, centerY = 0f, zoom = 0.413f)
		camera = camera.zoomedByPercent(deltaPercent = 1f, stepPercent = 1f)
		assertEquals(42f, camera.zoom * 100f, 0.5f, "first +1% step snaps onto the integer grid")
		// 58 more +1% steps reach exactly 100%.
		repeat(58) { camera = camera.zoomedByPercent(deltaPercent = 1f, stepPercent = 1f) }
		assertEquals(1f, camera.zoom, tolerance, "stepping reaches exactly 100%")
		// A +5% step from 100% snaps onto the 5-grid at 105%.
		val coarse = camera.zoomedByPercent(deltaPercent = 5f, stepPercent = 5f)
		assertEquals(1.05f, coarse.zoom, tolerance, "5% steps snap onto the 5-percent grid")
	}

	@Test
	fun panByScreenMovesCentreByDeltaOverZoom() {
		val camera = ViewportCamera(centerX = 0f, centerY = 0f, zoom = 2f)
		val panned = camera.panByScreen(deltaXpx = 10f, deltaYpx = 6f)
		assertEquals(-5f, panned.centerX, tolerance, "centre X shifts opposite the drag, scaled by 1/zoom")
		assertEquals(3f, panned.centerY, tolerance, "centre Y carries the screen-down vs world-up flip")
	}

	@Test
	fun actualSizeSetsUnitZoomKeepingCentre() {
		val camera = ViewportCamera(centerX = 42f, centerY = -9f, zoom = 7f)
		val actual = camera.withActualSize()
		assertEquals(1f, actual.zoom, tolerance, "actual size is true 1:1")
		assertEquals(camera.centerX, actual.centerX, tolerance, "centre is unchanged")
		assertEquals(camera.centerY, actual.centerY, tolerance, "centre is unchanged")
	}

	@Test
	fun framingScreenRectCentresAndFillsTheBox() {
		val camera = ViewportCamera(centerX = 10f, centerY = 20f, zoom = 2f)
		val width = 400
		val height = 300
		// A 100x60 box; corners passed reversed to prove the two corners may be given in any order.
		val left = 50f
		val top = 40f
		val right = 150f
		val bottom = 100f
		val framed = camera.framingScreenRect(right, bottom, left, top, viewportWidth = width, viewportHeight = height)
		// min(400/100, 300/60) = min(4, 5) = 4, so the looser (vertical) axis letterboxes.
		assertEquals(camera.zoom * 4f, framed.zoom, tolerance, "the box fills the tighter axis")
		// The world point under the box centre (via the OLD camera inverse) maps to the viewport centre
		// under the NEW camera.
		val worldX = camera.centerX + ((left + right) / 2f - width / 2f) / camera.zoom
		val worldY = camera.centerY + (height / 2f - (top + bottom) / 2f) / camera.zoom
		val (screenX, screenY) = worldToScreen(framed, worldX, worldY, width, height)
		assertEquals(width / 2f, screenX, tolerance, "box centre X frames to viewport centre")
		assertEquals(height / 2f, screenY, tolerance, "box centre Y frames to viewport centre")
	}

	@Test
	fun framingTinyRectClampsToMaxZoom() {
		val camera = ViewportCamera(centerX = 0f, centerY = 0f, zoom = 1f)
		val framed = camera.framingScreenRect(100f, 100f, 100.5f, 100.5f, viewportWidth = 800, viewportHeight = 800)
		assertEquals(ViewportCamera.MAX_ZOOM, framed.zoom, tolerance, "a sub-pixel box cannot exceed the ceiling")
	}

	@Test
	fun framingDegenerateRectIsGuarded() {
		val camera = ViewportCamera(centerX = 3f, centerY = -4f, zoom = 2f)
		// Zero-area (a click, not a drag): the 1px floor keeps the zoom finite and clamped.
		val framed = camera.framingScreenRect(200f, 150f, 200f, 150f, viewportWidth = 400, viewportHeight = 300)
		assertTrue(framed.zoom in ViewportCamera.MIN_ZOOM..ViewportCamera.MAX_ZOOM, "zoom stays within the clamps")
	}

	@Test
	fun zoomClampsAtTheLimits() {
		val far = ViewportCamera(0f, 0f, ViewportCamera.MAX_ZOOM).zoomedByPercent(deltaPercent = 100_000f, stepPercent = 5f)
		assertEquals(ViewportCamera.MAX_ZOOM, far.zoom, tolerance, "cannot exceed the ceiling")
		val near = ViewportCamera(0f, 0f, ViewportCamera.MIN_ZOOM).zoomedByPercent(deltaPercent = -100_000f, stepPercent = 5f)
		assertEquals(ViewportCamera.MIN_ZOOM, near.zoom, tolerance, "cannot drop below the floor")
		assertTrue(ViewportCamera.MIN_ZOOM < ViewportCamera.MAX_ZOOM, "floor below ceiling")
	}
}
