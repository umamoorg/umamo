package org.umamo.render.puppet

import org.umamo.render.ContentBounds
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [contentBoundsOf] and the fallback beside it: the extent spans only the shown drawables, each span
 * is floored at 1, nothing shown measures as null, and an empty view frames the canvas.  Reachable without
 * a GPU, unlike the framing path in the renderer.
 */
class ContentBoundsEvalTest {
	private fun geometryOf(vararg drawables: Pair<String, FloatArray>): DeformedGeometry =
		DeformedGeometry(
			worldPositions = drawables.associate { (id, positions) -> DrawableId(id) to positions },
			drawOrder = emptyMap(),
			opacity = emptyMap(),
		)

	@Test
	fun contentBoundsOfSpansEveryShownVertex() {
		val geometry = geometryOf("a" to floatArrayOf(-10f, -20f, 30f, 5f), "b" to floatArrayOf(0f, 40f))
		val bounds = assertNotNull(contentBoundsOf(geometry, setOf(DrawableId("a"), DrawableId("b"))))
		assertEquals(-10f, bounds.minX, "left edge is the smallest x across both meshes")
		assertEquals(-20f, bounds.minY, "bottom edge is the smallest y across both meshes")
		assertEquals(40f, bounds.width, "width spans -10..30")
		assertEquals(60f, bounds.height, "height spans -20..40")
	}

	@Test
	fun contentBoundsOfIgnoresHiddenDrawables() {
		// The hidden mesh is far larger; if the filter regressed, the bounds would swell to it.
		val geometry = geometryOf("shown" to floatArrayOf(0f, 0f, 10f, 10f), "hidden" to floatArrayOf(-500f, -500f, 500f, 500f))
		val bounds = assertNotNull(contentBoundsOf(geometry, setOf(DrawableId("shown"))))
		assertEquals(0f, bounds.minX)
		assertEquals(0f, bounds.minY)
		assertEquals(10f, bounds.width, "the hidden full-canvas mesh must not stretch the framing")
		assertEquals(10f, bounds.height)
	}

	@Test
	fun contentBoundsOfFloorsDegenerateSpansAtOne() {
		// A single vertex has zero extent; the floor is what keeps a fit from dividing by zero.
		val bounds = assertNotNull(contentBoundsOf(geometryOf("dot" to floatArrayOf(7f, 9f)), setOf(DrawableId("dot"))))
		assertEquals(1f, bounds.width, "a zero-width extent floors at 1")
		assertEquals(1f, bounds.height, "a zero-height extent floors at 1")
	}

	/**
	 * Nothing shown is nothing to measure: null, never a rectangle left at the float extremes - a camera
	 * fitted to that sits where the grid has no precision to draw with, and the viewport goes blank.
	 */
	@Test
	fun contentBoundsOfIsNullWhenNothingIsShown() {
		assertNull(contentBoundsOf(geometryOf("a" to floatArrayOf(0f, 0f)), emptySet()), "every drawable hidden")
		assertNull(contentBoundsOf(geometryOf(), emptySet()), "an empty model")
		assertNull(contentBoundsOf(geometryOf("empty" to FloatArray(0)), setOf(DrawableId("empty"))), "a shown drawable with no vertices")
	}

	/** An empty view frames the canvas, which world space holds at x in [0, width] and y in [-height, 0]. */
	@Test
	fun anEmptyViewFramesTheCanvas() {
		val bounds = emptyContentBoundsOf(emptyModel(canvasWidth = 1200f, canvasHeight = 800f))

		assertEquals(ContentBounds(0f, -800f, 1200f, 800f), bounds)
	}

	/** With no canvas either, it frames a square around the world origin, so the origin's axes are on screen. */
	@Test
	fun anEmptyViewWithNoCanvasFramesTheWorldOrigin() {
		val bounds = emptyContentBoundsOf(emptyModel(canvasWidth = 0f, canvasHeight = 0f, worldOriginX = 40f, worldOriginZ = -60f))

		assertEquals(40f, bounds.minX + bounds.width / 2f, "centered on the origin's x")
		assertEquals(-60f, bounds.minY + bounds.height / 2f, "centered on the origin's y")
		assertTrue(bounds.width > 1f && bounds.height > 1f, "with room to work in, not a degenerate point")
	}

	/**
	 * A model with nothing in it.
	 *
	 * @param Float canvasWidth  The canvas width, or 0 for none.
	 * @param Float canvasHeight The canvas height, or 0 for none.
	 * @param Float worldOriginX The world origin's x.
	 * @param Float worldOriginZ The world origin's z (up).
	 * @return PuppetModel The model.
	 */
	private fun emptyModel(canvasWidth: Float, canvasHeight: Float, worldOriginX: Float = 0f, worldOriginZ: Float = 0f): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
			canvasWidth = canvasWidth,
			canvasHeight = canvasHeight,
			worldOriginX = worldOriginX,
			worldOriginZ = worldOriginZ,
		)
}