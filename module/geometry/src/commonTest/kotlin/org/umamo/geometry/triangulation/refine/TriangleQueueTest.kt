package org.umamo.geometry.triangulation.refine

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The refinement queue's order, and the packed triangle identity it orders by.
 */
class TriangleQueueTest {
	@Test
	fun theQueuePopsWorstFirstAndTiesByTriple() {
		val queue = TriangleQueue()
		val entries = listOf(0.5 to packTriangle(4, 5, 6), 0.1 to packTriangle(9, 7, 8), 0.5 to packTriangle(1, 2, 3), 0.3 to packTriangle(3, 1, 2), 0.1 to packTriangle(2, 0, 1))

		for ((value, triangle) in entries) {
			queue.push(value, triangle)
		}

		val popped = ArrayList<Long>()

		while (queue.isNotEmpty()) {
			popped.add(queue.pop())
		}

		val expected = entries.sortedWith(compareBy<Pair<Double, Long>> { it.first }.thenBy { it.second }).map { it.second }
		assertEquals(expected, popped)
	}

	@Test
	fun aPackedTriangleStartsAtItsLowestCornerAndKeepsItsOrientation() {
		for ((first, second, third) in listOf(Triple(3, 1, 2), Triple(1, 2, 3), Triple(2, 3, 1))) {
			val packed = packTriangle(first, second, third)

			assertEquals(1, triangleFirst(packed))
			assertEquals(2, triangleSecond(packed))
			assertEquals(3, triangleThird(packed))
		}

		val large = packTriangle(MAXIMUM_PACKED_VERTICES - 1, 5, MAXIMUM_PACKED_VERTICES - 2)
		assertEquals(5, triangleFirst(large))
		assertEquals(MAXIMUM_PACKED_VERTICES - 2, triangleSecond(large))
		assertEquals(MAXIMUM_PACKED_VERTICES - 1, triangleThird(large))
	}
}