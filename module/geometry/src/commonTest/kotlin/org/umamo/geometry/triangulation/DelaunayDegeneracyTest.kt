package org.umamo.geometry.triangulation

import org.umamo.geometry.predicate.ULP_AT_HALF
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The Delaunay triangulation on the input real meshes are made of: lattices (every quad cocircular,
 * every row collinear), cocircular rings, collinear runs before the first triangle can form, exact
 * duplicates, coordinates at the edge of double precision, and points within an ulp of a line.
 */
class DelaunayDegeneracyTest {
	@Test
	fun aSquareGridTriangulatesWithItsCollinearHull() {
		val side = 12
		val points = DoubleArray(2 * side * side)
		for (row in 0 until side) {
			for (column in 0 until side) {
				points[2 * (row * side + column)] = column.toDouble()
				points[2 * (row * side + column) + 1] = row.toDouble()
			}
		}
		val result = triangulate(points)
		// Every unit square splits in two, whichever diagonal the tie-break chose.
		assertEquals(2 * (side - 1) * (side - 1), result.triangleCount)
		assertDelaunayTriangulation(points, result)
	}

	@Test
	fun aHexagonalLatticeTriangulatesIntoItsEquilateralTriangles() {
		val rows = 10
		val columns = 10
		val rowHeight = sqrt(3.0) / 2.0
		val points = DoubleArray(2 * rows * columns)
		for (row in 0 until rows) {
			for (column in 0 until columns) {
				points[2 * (row * columns + column)] = column + if (row % 2 == 1) 0.5 else 0.0
				points[2 * (row * columns + column) + 1] = row * rowHeight
			}
		}
		assertDelaunayTriangulation(points, triangulate(points))
	}

	@Test
	fun aCocircularRingTriangulatesWithoutZeroAreaTriangles() {
		// The twelve integer points at distance 5 from the origin, plus the center.
		val ring = doubleArrayOf(5.0, 0.0, 4.0, 3.0, 3.0, 4.0, 0.0, 5.0, -3.0, 4.0, -4.0, 3.0, -5.0, 0.0, -4.0, -3.0, -3.0, -4.0, 0.0, -5.0, 3.0, -4.0, 4.0, -3.0)
		val ringResult = triangulate(ring)
		assertEquals(10, ringResult.triangleCount)
		assertDelaunayTriangulation(ring, ringResult)

		val withCenter = ring + doubleArrayOf(0.0, 0.0)
		val centerResult = triangulate(withCenter)
		assertEquals(12, centerResult.triangleCount)
		assertDelaunayTriangulation(withCenter, centerResult)
	}

	@Test
	fun collinearPointsYieldNothing() {
		val points = DoubleArray(2 * 20)
		for (pointIndex in 0 until 20) {
			points[2 * pointIndex] = pointIndex.toDouble()
			points[2 * pointIndex + 1] = 2.0 * pointIndex + 1.0
		}
		assertEquals(0, triangulate(points).triangleCount)
	}

	@Test
	fun collinearPointsBeforeTheFirstTriangleStillJoinIt() {
		// Nineteen points on a line and one apex: a fan whose hull keeps every collinear point.
		val points = DoubleArray(2 * 20)
		for (pointIndex in 0 until 19) {
			points[2 * pointIndex] = pointIndex.toDouble()
			points[2 * pointIndex + 1] = 0.0
		}
		points[38] = 9.5
		points[39] = 3.0
		val result = triangulate(points)
		assertEquals(18, result.triangleCount)
		assertDelaunayTriangulation(points, result)
	}

	@Test
	fun duplicatesAliasToTheirLowestIndex() {
		val points =
			doubleArrayOf(
				0.0,
				0.0,
				10.0,
				0.0,
				10.0,
				10.0,
				10.0,
				0.0,
				-0.0,
				-0.0,
				0.0,
				10.0,
				10.0,
				10.0,
			)
		val result = triangulate(points)
		assertContentEquals(intArrayOf(0, 1, 2, 1, 0, 5, 2), result.canonicalIndex)
		assertEquals(2, result.triangleCount)
		assertDelaunayTriangulation(points, result)
	}

	@Test
	fun onlyDuplicatesOfOnePointYieldNothing() {
		val result = triangulate(doubleArrayOf(3.0, 4.0, 3.0, 4.0, 3.0, 4.0))
		assertEquals(0, result.triangleCount)
		assertContentEquals(intArrayOf(0, 0, 0), result.canonicalIndex)
	}

	@Test
	fun extremeScalesAndOffsetsTriangulate() {
		for (scale in doubleArrayOf(1e-30, 1e-6, 1.0, 1e6, 1e30)) {
			val points = DoubleArray(2 * 64)
			for (pointIndex in 0 until 64) {
				points[2 * pointIndex] = ((pointIndex * 37) % 64) * scale
				points[2 * pointIndex + 1] = ((pointIndex * 11) % 64) * scale
			}
			assertDelaunayTriangulation(points, triangulate(points))
		}
		// A lattice far from the origin: each coordinate is exact, but its spacing is two ulps.
		val farPoints = DoubleArray(2 * 49)
		for (pointIndex in 0 until 49) {
			farPoints[2 * pointIndex] = 4e15 + (pointIndex % 7)
			farPoints[2 * pointIndex + 1] = 4e15 + (pointIndex / 7)
		}
		assertDelaunayTriangulation(farPoints, triangulate(farPoints))
	}

	@Test
	fun pointsWithinAnUlpOfALineTriangulate() {
		// Kettner's grid squeezed onto a line: most triples are collinear or nearly so.
		val points = DoubleArray(2 * 64)
		for (pointIndex in 0 until 32) {
			points[2 * pointIndex] = 0.5 + pointIndex * ULP_AT_HALF
			points[2 * pointIndex + 1] = 0.5 + (pointIndex % 5) * ULP_AT_HALF
		}
		for (pointIndex in 32 until 64) {
			points[2 * pointIndex] = 0.5 + (pointIndex % 7) * ULP_AT_HALF
			points[2 * pointIndex + 1] = 0.5 + pointIndex * ULP_AT_HALF
		}
		val result = triangulate(points)
		assertTrue(result.triangleCount > 0)
		assertDelaunayTriangulation(points, result)
	}
}