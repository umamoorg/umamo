package org.umamo.render.puppet

import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.DrawableId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [contentBoundsOf]: the extent spans only the shown drawables, each span is floored at 1, and the
 * nothing-shown sentinel is preserved.  Reachable without a GPU, unlike the framing path in the renderer.
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
		val bounds = contentBoundsOf(geometry, setOf(DrawableId("a"), DrawableId("b")))
		assertEquals(-10f, bounds.minX, "left edge is the smallest x across both meshes")
		assertEquals(-20f, bounds.minY, "bottom edge is the smallest y across both meshes")
		assertEquals(40f, bounds.width, "width spans -10..30")
		assertEquals(60f, bounds.height, "height spans -20..40")
	}

	@Test
	fun contentBoundsOfIgnoresHiddenDrawables() {
		// The hidden mesh is far larger; if the filter regressed, the bounds would swell to it.
		val geometry = geometryOf("shown" to floatArrayOf(0f, 0f, 10f, 10f), "hidden" to floatArrayOf(-500f, -500f, 500f, 500f))
		val bounds = contentBoundsOf(geometry, setOf(DrawableId("shown")))
		assertEquals(0f, bounds.minX)
		assertEquals(0f, bounds.minY)
		assertEquals(10f, bounds.width, "the hidden full-canvas mesh must not stretch the framing")
		assertEquals(10f, bounds.height)
	}

	@Test
	fun contentBoundsOfFloorsDegenerateSpansAtOne() {
		// A single vertex has zero extent; the floor is what keeps a fit from dividing by zero.
		val bounds = contentBoundsOf(geometryOf("dot" to floatArrayOf(7f, 9f)), setOf(DrawableId("dot")))
		assertEquals(1f, bounds.width, "a zero-width extent floors at 1")
		assertEquals(1f, bounds.height, "a zero-height extent floors at 1")
	}

	@Test
	fun contentBoundsOfIsSentinelWhenNothingShown() {
		// Documented long-standing behavior, pinned so the extraction cannot silently "fix" it: with no
		// shown drawable the min/max sweep never runs and the sentinel falls out of the initial values.
		val bounds = contentBoundsOf(geometryOf("a" to floatArrayOf(0f, 0f)), emptySet())
		assertEquals(Float.MAX_VALUE, bounds.minX)
		assertEquals(Float.MAX_VALUE, bounds.minY)
		assertEquals(1f, bounds.width)
		assertEquals(1f, bounds.height)
	}
}
