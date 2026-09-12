package org.umamo.format.art

import kotlin.test.Test
import kotlin.test.assertEquals

/** The overlap measure: same, nested, partial, disjoint, and empty rectangles. */
class LayerBoundsTest {
	@Test
	fun intersectionOverUnionCoversEveryArrangement() {
		val square = LayerBounds(10, 10, 10, 10)
		assertEquals(1f, square.intersectionOverUnion(square))
		assertEquals(0.25f, square.intersectionOverUnion(LayerBounds(10, 10, 5, 5)), "a nested quarter")
		assertEquals(0.25f, LayerBounds(10, 10, 5, 5).intersectionOverUnion(square), "symmetric")
		// Half overlap: 50 shared of 150 covered.
		assertEquals(50f / 150f, square.intersectionOverUnion(LayerBounds(15, 10, 10, 10)), 1e-6f)
		assertEquals(0f, square.intersectionOverUnion(LayerBounds(30, 30, 10, 10)), "disjoint")
		assertEquals(0f, square.intersectionOverUnion(LayerBounds(20, 10, 10, 10)), "touching edges do not overlap")
		assertEquals(0f, square.intersectionOverUnion(LayerBounds(10, 10, 0, 5)), "an empty rectangle")
	}
}