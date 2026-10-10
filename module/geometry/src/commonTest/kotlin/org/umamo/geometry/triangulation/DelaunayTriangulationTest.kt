package org.umamo.geometry.triangulation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The Delaunay triangulation on ordinary input: the smallest cases, random point sets checked by
 * brute force, the internal structure validated after every single insertion, independence from the
 * caller's point order, determinism, and input validation.
 */
class DelaunayTriangulationTest {
	@Test
	fun emptyInputHasNoTriangles() {
		val result = triangulate(DoubleArray(0))
		assertEquals(0, result.triangleCount)
		assertEquals(0, result.canonicalIndex.size)
	}

	@Test
	fun oneAndTwoPointsHaveNoTriangles() {
		assertEquals(0, triangulate(doubleArrayOf(1.0, 2.0)).triangleCount)
		assertEquals(0, triangulate(doubleArrayOf(1.0, 2.0, 3.0, 4.0)).triangleCount)
	}

	@Test
	fun threePointsMakeOnePositiveTriangle() {
		// Listed clockwise with y up; the result must still be positively oriented.
		val points = doubleArrayOf(0.0, 0.0, 0.0, 1.0, 1.0, 0.0)
		val result = triangulate(points)
		assertEquals(1, result.triangleCount)
		assertDelaunayTriangulation(points, result)
	}

	@Test
	fun aSquareSplitsIntoTwoTriangles() {
		val points = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
		val result = triangulate(points)
		assertEquals(2, result.triangleCount)
		assertDelaunayTriangulation(points, result)
	}

	@Test
	fun randomPointSetsAreDelaunay() {
		for (seed in 1..8) {
			val random = Random(seed)
			val pointCount = random.nextInt(3, 250)
			val points = DoubleArray(2 * pointCount) { random.nextDouble(-500.0, 500.0) }
			assertDelaunayTriangulation(points, triangulate(points))
		}
	}

	@Test
	fun theStructureStaysValidAfterEveryInsertion() {
		val random = Random(61)
		val points = DoubleArray(2 * 400) { random.nextDouble(0.0, 1000.0) }
		var insertions = 0
		val triangulation =
			buildDelaunay(points, planInsertion(points)) { store ->
				store.validate()
				insertions++
			}
		// The first triangle takes three points at once.
		assertEquals(398, insertions)
		assertEquals(2 * 400 - 2, triangulation!!.triangleCount, "a sphere over n points plus the ghost has 2n - 2 triangles")
	}

	@Test
	fun theTriangleSetDoesNotDependOnInputOrder() {
		// Random doubles are in general position, where the Delaunay triangulation is unique.
		val random = Random(67)
		val pointCount = 150
		val points = DoubleArray(2 * pointCount) { random.nextDouble(-100.0, 100.0) }
		val reference = normalizedTriangleSet(triangulate(points).triangles)

		val permutation = (0 until pointCount).shuffled(Random(71))
		val permuted = DoubleArray(2 * pointCount)
		for ((newIndex, oldIndex) in permutation.withIndex()) {
			permuted[2 * newIndex] = points[2 * oldIndex]
			permuted[2 * newIndex + 1] = points[2 * oldIndex + 1]
		}
		val permutedTriangles = triangulate(permuted).triangles
		val mappedBack = IntArray(permutedTriangles.size) { permutation[permutedTriangles[it]] }
		assertEquals(reference, normalizedTriangleSet(mappedBack))
	}

	@Test
	fun theOutputIsDeterministic() {
		val random = Random(73)
		// Integer coordinates are full of collinear and cocircular ties, where determinism is earned.
		val points = DoubleArray(2 * 300) { random.nextInt(0, 20).toDouble() }
		val first = triangulate(points)
		val second = triangulate(points)
		assertContentEquals(first.triangles, second.triangles)
		assertContentEquals(first.canonicalIndex, second.canonicalIndex)
	}

	@Test
	fun malformedInputIsRejected() {
		assertFailsWith<IllegalArgumentException> { triangulate(doubleArrayOf(1.0, 2.0, 3.0)) }
		assertFailsWith<IllegalArgumentException> { triangulate(doubleArrayOf(0.0, 0.0, 1.0, Double.NaN, 2.0, 0.0)) }
		assertFailsWith<IllegalArgumentException> { triangulate(doubleArrayOf(0.0, 0.0, Double.POSITIVE_INFINITY, 1.0, 2.0, 0.0)) }
	}
}