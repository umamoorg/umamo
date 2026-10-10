package org.umamo.geometry.triangulation

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Even-odd classification by boundary rings, checked by area: a ring keeps its inside, a ring inside
 * it cuts a hole either way round, an island inside the hole is kept, rings touching at a corner (the
 * alpha tracer's saddle) or sharing an edge classify correctly, interior segments change nothing, an
 * open ring is reported, and random star polygons with holes conserve area.
 */
class RegionClassificationTest {
	@Test
	fun aRingKeepsItsInside() {
		val points = OUTER_SQUARE + doubleArrayOf(5.0, 5.0, 20.0, 5.0)
		val result = triangulate(points, boundaries = listOf(intArrayOf(0, 1, 2, 3)))
		assertEquals(100.0, totalArea(points, result))
		assertTrue(result.classificationConsistent)
		assertTrue(result.triangles.none { it == 5 }, "the point outside the ring is outside every triangle")
		assertConstrainedDelaunayTriangulation(points, result, coversHull = false)
	}

	@Test
	fun aRingInsideARingCutsAHoleEitherWayRound() {
		val points = OUTER_SQUARE + HOLE_SQUARE

		for (hole in listOf(intArrayOf(4, 5, 6, 7), intArrayOf(7, 6, 5, 4))) {
			val result = triangulate(points, boundaries = listOf(intArrayOf(0, 1, 2, 3), hole))
			assertEquals(100.0 - 16.0, totalArea(points, result))
			assertTrue(result.classificationConsistent)
			assertConstrainedDelaunayTriangulation(points, result, coversHull = false)
		}
	}

	@Test
	fun anIslandInsideAHoleIsKept() {
		val points = OUTER_SQUARE + HOLE_SQUARE + doubleArrayOf(4.0, 4.0, 6.0, 4.0, 6.0, 6.0, 4.0, 6.0)
		val result = triangulate(points, boundaries = listOf(intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6, 7), intArrayOf(8, 9, 10, 11)))
		assertEquals(100.0 - 16.0 + 4.0, totalArea(points, result))
		assertTrue(result.classificationConsistent)
	}

	@Test
	fun ringsTouchingAtACornerClassifyEachSide() {
		// Two 2 x 2 squares meeting only at (2, 2) - the alpha tracer's saddle corner - written as one
		// ring through the corner twice by the same index, by a duplicate point, and as two rings.
		val points = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 2.0, 2.0, 4.0, 2.0, 4.0, 4.0, 2.0, 4.0, 2.0, 2.0, 0.0, 2.0, 4.0, 0.0, 0.0, 4.0)
		val sameIndex = intArrayOf(0, 1, 2, 3, 4, 5, 2, 7)
		val duplicatePoint = intArrayOf(0, 1, 2, 3, 4, 5, 6, 7)
		val twoRings = listOf(intArrayOf(0, 1, 2, 7), intArrayOf(2, 3, 4, 5))

		for (boundaries in listOf(listOf(sameIndex), listOf(duplicatePoint), twoRings)) {
			val result = triangulate(points, boundaries = boundaries)
			assertEquals(8.0, totalArea(points, result))
			assertTrue(result.classificationConsistent)
			assertTrue(result.rejectedConstraints.isEmpty())
		}
	}

	@Test
	fun ringsSharingAnEdgeFormTheirUnion() {
		val points = doubleArrayOf(0.0, 0.0, 2.0, 0.0, 4.0, 0.0, 4.0, 2.0, 2.0, 2.0, 0.0, 2.0)
		val result = triangulate(points, boundaries = listOf(intArrayOf(0, 1, 4, 5), intArrayOf(1, 2, 3, 4)))
		assertEquals(8.0, totalArea(points, result))
		assertTrue(result.classificationConsistent)
	}

	@Test
	fun interiorSegmentsChangeNothingInsideOrOutside() {
		val points = OUTER_SQUARE + HOLE_SQUARE
		val inner = intArrayOf(4, 5, 6, 7, 4)
		val result = triangulate(points, boundaries = listOf(intArrayOf(0, 1, 2, 3)), segments = listOf(inner))
		assertEquals(100.0, totalArea(points, result))
		assertTrue(result.classificationConsistent)

		for ((low, high) in listOf(4 to 5, 5 to 6, 6 to 7, 4 to 7)) {
			assertTrue((0 until result.constrainedEdges.size / 2).any { result.constrainedEdges[2 * it] == low && result.constrainedEdges[2 * it + 1] == high }, "inner edge ($low, $high) is constrained")
		}

		assertConstrainedDelaunayTriangulation(points, result, coversHull = false)
	}

	@Test
	fun anOpenRingIsReportedInconsistent() {
		// A triangle whose two long edges cross the square's right side: they are rejected, the short
		// edge inside is accepted, and the ring is left open.
		val points = OUTER_SQUARE + doubleArrayOf(5.0, 5.0, 15.0, 5.0, 5.0, 8.0)
		val result = triangulate(points, boundaries = listOf(intArrayOf(0, 1, 2, 3), intArrayOf(4, 5, 6)))
		assertEquals(2, result.rejectedConstraints.size)
		assertFalse(result.classificationConsistent)
	}

	@Test
	fun noBoundariesKeepsTheWholeHull() {
		val points = OUTER_SQUARE + HOLE_SQUARE
		val result = triangulate(points, segments = listOf(intArrayOf(4, 5, 6, 7, 4)))
		assertEquals(100.0, totalArea(points, result))
		assertConstrainedDelaunayTriangulation(points, result, coversHull = true)
	}

	@Test
	fun randomStarPolygonsWithHolesConserveArea() {
		for (seed in 1..10) {
			val random = Random(5000 + seed)
			val outer = starPolygon(random, random.nextInt(16, 40), 6.0, 10.0)
			val hole = starPolygon(random, random.nextInt(12, 30), 1.0, 3.0)
			val scattered = DoubleArray(2 * random.nextInt(0, 60)) { random.nextDouble(-12.0, 12.0) }
			val points = outer + hole + scattered
			val outerCount = outer.size / 2
			val holeCount = hole.size / 2
			val boundaries = listOf(IntArray(outerCount) { it }, IntArray(holeCount) { outerCount + it })
			val result = triangulate(points, boundaries = boundaries)

			assertTrue(result.rejectedConstraints.isEmpty())
			assertTrue(result.classificationConsistent)
			val expected = abs(ringArea(outer)) - abs(ringArea(hole))
			assertEquals(expected, totalArea(points, result), 1e-9 * expected)
			assertConstrainedDelaunayTriangulation(points, result, coversHull = false)
		}
	}

	/**
	 * A random star-shaped polygon around the origin: angles in order with jitter, radii in a band.
	 *
	 * @param Random random        The source.
	 * @param Int    vertexCount   The number of vertices (16 or more keeps every edge outside the inner band's reach).
	 * @param Double minimumRadius The smallest vertex radius.
	 * @param Double maximumRadius The largest vertex radius.
	 * @return DoubleArray The polygon's vertices.
	 */
	private fun starPolygon(random: Random, vertexCount: Int, minimumRadius: Double, maximumRadius: Double): DoubleArray {
		val polygon = DoubleArray(2 * vertexCount)

		for (vertex in 0 until vertexCount) {
			val angle = (vertex + random.nextDouble(0.0, 0.5)) * 2.0 * PI / vertexCount
			val radius = random.nextDouble(minimumRadius, maximumRadius)
			polygon[2 * vertex] = radius * cos(angle)
			polygon[2 * vertex + 1] = radius * sin(angle)
		}

		return polygon
	}

	/**
	 * A ring's signed area by the shoelace formula.
	 *
	 * @param DoubleArray ring The ring's vertices.
	 * @return Double The signed area.
	 */
	private fun ringArea(ring: DoubleArray): Double {
		val vertexCount = ring.size / 2
		var doubledArea = 0.0

		for (vertex in 0 until vertexCount) {
			val next = (vertex + 1) % vertexCount
			doubledArea += ring[2 * vertex] * ring[2 * next + 1] - ring[2 * next] * ring[2 * vertex + 1]
		}

		return doubledArea / 2.0
	}

	private companion object {
		/** A 10 x 10 square at the origin, points 0..3. */
		val OUTER_SQUARE = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

		/** A 4 x 4 square inside it, points 4..7 when appended. */
		val HOLE_SQUARE = doubleArrayOf(3.0, 3.0, 7.0, 3.0, 7.0, 7.0, 3.0, 7.0)
	}
}