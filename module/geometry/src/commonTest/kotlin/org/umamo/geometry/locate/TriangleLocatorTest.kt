package org.umamo.geometry.locate

import org.umamo.geometry.predicate.orient2d
import org.umamo.geometry.triangulation.triangulate
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Point location over arbitrary meshes: weights that rebuild the query, the lowest index winning on
 * shared edges and overlaps, either winding, the nearest triangle off the mesh, degenerate triangles
 * skipped, and agreement with a brute-force scan on an irregular mesh.
 */
class TriangleLocatorTest {
	@Test
	fun insideWeightsRebuildTheQuery() {
		val locator = TriangleLocator(SQUARE_POSITIONS, SQUARE_TRIANGLES)
		val random = Random(97)

		repeat(1000) {
			val x = random.nextDouble(0.0, 10.0)
			val y = random.nextDouble(0.0, 10.0)
			val location = assertNotNull(locator.locate(x, y))
			assertTrue(location.isInside)
			assertEquals(0.0, location.distance)
			assertEquals(1.0, location.weightA + location.weightB + location.weightC, 1e-12)
			val rebuilt = rebuild(SQUARE_POSITIONS, SQUARE_TRIANGLES, location)
			assertEquals(x, rebuilt[0], 1e-9)
			assertEquals(y, rebuilt[1], 1e-9)
		}
	}

	@Test
	fun aPointOnASharedEdgeResolvesToTheLowerTriangle() {
		val locator = TriangleLocator(SQUARE_POSITIONS, SQUARE_TRIANGLES)
		// (5, 5) lies on the diagonal both triangles share.
		assertEquals(0, assertNotNull(locator.locate(5.0, 5.0)).triangleIndex)
	}

	@Test
	fun overlappingTrianglesResolveToTheLowerIndex() {
		// Two copies of the same triangle, the second wound the other way: a folded UV layout.
		val positions = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f)
		val forward = TriangleLocator(positions, intArrayOf(0, 1, 2, 0, 2, 1))
		val backward = TriangleLocator(positions, intArrayOf(0, 2, 1, 0, 1, 2))
		assertEquals(0, assertNotNull(forward.locate(2.0, 2.0)).triangleIndex)
		assertEquals(0, assertNotNull(backward.locate(2.0, 2.0)).triangleIndex)
		assertTrue(assertNotNull(backward.locate(2.0, 2.0)).isInside)
	}

	@Test
	fun aPointOffTheMeshSnapsToTheNearestTriangle() {
		val locator = TriangleLocator(SQUARE_POSITIONS, SQUARE_TRIANGLES)
		val location = assertNotNull(locator.locate(13.0, 4.0))
		assertFalse(location.isInside)
		assertEquals(3.0, location.distance, 1e-12)
		val rebuilt = rebuild(SQUARE_POSITIONS, SQUARE_TRIANGLES, location)
		assertEquals(10.0, rebuilt[0], 1e-12)
		assertEquals(4.0, rebuilt[1], 1e-12)
		assertTrue(minOf(location.weightA, location.weightB, location.weightC) == 0.0, "the nearest point lies on an edge")
	}

	@Test
	fun degenerateTrianglesAreSkipped() {
		// Triangle 0 is a sliver of zero area through the query point; triangle 1 is real.
		val positions = floatArrayOf(0f, 0f, 10f, 0f, 20f, 0f, 0f, 10f)
		val locator = TriangleLocator(positions, intArrayOf(0, 1, 2, 0, 1, 3))
		assertEquals(1, assertNotNull(locator.locate(5.0, 0.0)).triangleIndex)
		assertNull(TriangleLocator(positions, intArrayOf(0, 1, 2)).locate(5.0, 0.0))
	}

	@Test
	fun theGridAgreesWithABruteForceScan() {
		val random = Random(101)
		val pointCount = 200
		val points = DoubleArray(2 * pointCount) { random.nextDouble(0.0, 100.0) }
		val mesh = triangulate(points)
		val positions = FloatArray(points.size) { points[it].toFloat() }
		val locator = TriangleLocator(positions, mesh.triangles)

		repeat(3000) {
			val x = random.nextDouble(-20.0, 120.0)
			val y = random.nextDouble(-20.0, 120.0)
			val location = assertNotNull(locator.locate(x, y))
			val expectedInside = firstContaining(positions, mesh.triangles, x, y)

			if (expectedInside >= 0) {
				assertTrue(location.isInside)
				assertEquals(expectedInside, location.triangleIndex)
			} else {
				assertFalse(location.isInside)
				val rebuilt = rebuild(positions, mesh.triangles, location)
				val distance = sqrt((rebuilt[0] - x) * (rebuilt[0] - x) + (rebuilt[1] - y) * (rebuilt[1] - y))
				assertEquals(location.distance, distance, 1e-9)
				assertTrue(abs(location.distance - nearestDistance(positions, mesh.triangles, x, y)) < 1e-9)
			}
		}
	}

	/**
	 * The point a location's weights describe.
	 *
	 * @param FloatArray       positions The vertices.
	 * @param IntArray         triangles The mesh.
	 * @param TriangleLocation location  The location.
	 * @return DoubleArray The x and y the weights combine to.
	 */
	private fun rebuild(positions: FloatArray, triangles: IntArray, location: TriangleLocation): DoubleArray {
		val first = triangles[3 * location.triangleIndex]
		val second = triangles[3 * location.triangleIndex + 1]
		val third = triangles[3 * location.triangleIndex + 2]
		val x = location.weightA * positions[2 * first] + location.weightB * positions[2 * second] + location.weightC * positions[2 * third]
		val y = location.weightA * positions[2 * first + 1] + location.weightB * positions[2 * second + 1] + location.weightC * positions[2 * third + 1]

		return doubleArrayOf(x, y)
	}

	/**
	 * The lowest-indexed triangle containing a point, by scanning them all.
	 *
	 * @param FloatArray positions The vertices.
	 * @param IntArray   triangles The mesh.
	 * @param Double     x         The point's x.
	 * @param Double     y         The point's y.
	 * @return Int The triangle, or -1.
	 */
	private fun firstContaining(positions: FloatArray, triangles: IntArray, x: Double, y: Double): Int {
		for (triangle in 0 until triangles.size / 3) {
			val corners = IntArray(3) { triangles[3 * triangle + it] }
			val sides =
				DoubleArray(3) { edge ->
					val start = corners[edge]
					val end = corners[(edge + 1) % 3]
					orient2d(positions[2 * start].toDouble(), positions[2 * start + 1].toDouble(), positions[2 * end].toDouble(), positions[2 * end + 1].toDouble(), x, y)
				}

			if (sides.all { it >= 0.0 } || sides.all { it <= 0.0 }) {
				return triangle
			}
		}

		return -1
	}

	/**
	 * The distance from a point to the nearest triangle edge, by scanning them all.
	 *
	 * @param FloatArray positions The vertices.
	 * @param IntArray   triangles The mesh.
	 * @param Double     x         The point's x.
	 * @param Double     y         The point's y.
	 * @return Double The distance.
	 */
	private fun nearestDistance(positions: FloatArray, triangles: IntArray, x: Double, y: Double): Double {
		var nearest = Double.POSITIVE_INFINITY

		for (entry in triangles.indices) {
			val start = triangles[entry]
			val end = triangles[if (entry % 3 == 2) entry - 2 else entry + 1]
			val startX = positions[2 * start].toDouble()
			val startY = positions[2 * start + 1].toDouble()
			val alongX = positions[2 * end] - startX
			val alongY = positions[2 * end + 1] - startY
			val fraction = (((x - startX) * alongX + (y - startY) * alongY) / (alongX * alongX + alongY * alongY)).coerceIn(0.0, 1.0)
			val offsetX = startX + fraction * alongX - x
			val offsetY = startY + fraction * alongY - y
			nearest = minOf(nearest, sqrt(offsetX * offsetX + offsetY * offsetY))
		}

		return nearest
	}

	private companion object {
		/** A 10 x 10 square. */
		val SQUARE_POSITIONS = floatArrayOf(0f, 0f, 10f, 0f, 10f, 10f, 0f, 10f)

		/** The square split along its (0, 0) - (10, 10) diagonal. */
		val SQUARE_TRIANGLES = intArrayOf(0, 1, 2, 0, 2, 3)
	}
}