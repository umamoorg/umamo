package org.umamo.geometry.triangulation

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Constraint insertion: a constraint overrides the Delaunay choice, cuts through many edges, splits at
 * vertices lying on it, shares pieces with an overlapping constraint, and is rejected - leaving the
 * triangulation exactly as it was - when it crosses one already present.  Plus input validation and
 * a seeded fuzz that validates the internal structure after every constraint.
 */
class ConstraintInsertionTest {
	@Test
	fun aDiagonalConstraintOverridesTheTieBreak() {
		val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

		for (diagonal in listOf(intArrayOf(0, 2), intArrayOf(1, 3))) {
			val result = triangulate(square, segments = listOf(diagonal))
			assertContentEquals(diagonal.sortedArray(), result.constrainedEdges)
			assertTrue(result.rejectedConstraints.isEmpty())
			assertConstrainedDelaunayTriangulation(square, result, coversHull = true)
		}
	}

	@Test
	fun aLongSegmentCutsThroughManyEdges() {
		val random = Random(79)
		val interiorCount = 300
		val points = DoubleArray(2 * interiorCount + 4) { random.nextDouble(1.0, 99.0) }
		// Two far points on opposite sides, joined by a segment across the whole cloud.
		points[2 * interiorCount] = 0.0
		points[2 * interiorCount + 1] = 37.0
		points[2 * interiorCount + 2] = 100.0
		points[2 * interiorCount + 3] = 61.0
		val result = triangulate(points, segments = listOf(intArrayOf(interiorCount, interiorCount + 1)))
		assertTrue(result.rejectedConstraints.isEmpty())
		assertContentEquals(intArrayOf(interiorCount, interiorCount + 1), result.constrainedEdges)
		assertConstrainedDelaunayTriangulation(points, result, coversHull = true)
	}

	@Test
	fun aConstraintThroughVerticesSplitsAtEachOfThem() {
		val points = squareGrid(5)
		// From (0, 0) to (4, 4): the diagonal passes through (1, 1), (2, 2), and (3, 3).
		val result = triangulate(points, segments = listOf(intArrayOf(0, 24)))
		assertContentEquals(intArrayOf(0, 6, 6, 12, 12, 18, 18, 24), result.constrainedEdges)
		assertConstrainedDelaunayTriangulation(points, result, coversHull = true)
	}

	@Test
	fun overlappingCollinearConstraintsShareTheirPieces() {
		val points = squareGrid(7)
		// Two overlapping stretches of the middle row, y = 3: (0..4, 3) and (2..6, 3).
		val result = triangulate(points, segments = listOf(intArrayOf(21, 25), intArrayOf(23, 27)))
		assertTrue(result.rejectedConstraints.isEmpty())
		assertContentEquals(intArrayOf(21, 22, 22, 23, 23, 24, 24, 25, 25, 26, 26, 27), result.constrainedEdges)
		assertConstrainedDelaunayTriangulation(points, result, coversHull = true)
	}

	@Test
	fun aCrossingSegmentIsRejectedAndChangesNothing() {
		val random = Random(83)
		val points = DoubleArray(2 * 60) { random.nextDouble(10.0, 90.0) }
		val withEnds = points + doubleArrayOf(0.0, 50.0, 100.0, 50.0, 50.0, 0.0, 50.0, 100.0)
		val horizontal = intArrayOf(60, 61)
		val vertical = intArrayOf(62, 63)
		val both = triangulate(withEnds, segments = listOf(horizontal, vertical))
		val alone = triangulate(withEnds, segments = listOf(horizontal))

		assertEquals(1, both.rejectedConstraints.size)
		val rejection = both.rejectedConstraints.single()
		assertEquals(ConstraintKind.Segment, rejection.kind)
		assertEquals(1, rejection.polylineIndex)
		assertEquals(0, rejection.edgeIndex)
		assertEquals(62, rejection.startPoint)
		assertEquals(63, rejection.endPoint)
		assertEquals(ConstraintRejectionReason.CrossesConstraint, rejection.reason)
		assertTrue(properlyCross(withEnds, 62, 63, rejection.crossedStartPoint, rejection.crossedEndPoint), "the reported edge must be the one crossed")
		assertContentEquals(alone.triangles, both.triangles)
		assertContentEquals(alone.constrainedEdges, both.constrainedEdges)
	}

	@Test
	fun boundariesAreInsertedBeforeSegments() {
		val points = squareGrid(5)
		// A ring around the middle 2 x 2 block and a segment cutting through it: boundaries go in
		// first, so the segment is the one rejected and the ring stays closed.
		val ring = intArrayOf(6, 8, 18, 16)
		val crossing = intArrayOf(1, 23)
		val result = triangulate(points, boundaries = listOf(ring), segments = listOf(crossing))
		assertEquals(ConstraintKind.Segment, result.rejectedConstraints.single().kind)
		assertTrue(result.classificationConsistent)
		assertEquals(4.0, totalArea(points, result))
	}

	@Test
	fun zeroLengthEdgesAreDroppedSilently() {
		// Points 4 and 5 duplicate points 0 and 2.
		val points = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 0.0, 0.0, 10.0, 10.0)
		val result = triangulate(points, segments = listOf(intArrayOf(3, 3), intArrayOf(0, 4), intArrayOf(4, 5)))
		assertTrue(result.rejectedConstraints.isEmpty())
		// The (4, 5) edge reads as (0, 2) through the aliasing.
		assertContentEquals(intArrayOf(0, 2), result.constrainedEdges)
	}

	@Test
	fun constraintsWithoutATriangulationAreReported() {
		val collinear = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0)
		val result = triangulate(collinear, boundaries = listOf(intArrayOf(0, 1, 2)), segments = listOf(intArrayOf(0, 2), intArrayOf(1, 1)))
		assertEquals(0, result.triangleCount)
		assertEquals(4, result.rejectedConstraints.size)
		assertTrue(result.rejectedConstraints.all { it.reason == ConstraintRejectionReason.NoTriangulation })
		assertEquals(listOf(ConstraintKind.Boundary, ConstraintKind.Boundary, ConstraintKind.Boundary, ConstraintKind.Segment), result.rejectedConstraints.map { it.kind })
	}

	@Test
	fun outOfRangeIndicesAreRejected() {
		val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
		assertFailsWith<IllegalArgumentException> { triangulate(square, segments = listOf(intArrayOf(0, 4))) }
		assertFailsWith<IllegalArgumentException> { triangulate(square, boundaries = listOf(intArrayOf(-1, 1, 2))) }
	}

	@Test
	fun randomConstraintsKeepEveryInvariant() {
		for (seed in 1..10) {
			val random = Random(4000 + seed)
			val pointCount = random.nextInt(8, 120)
			// Half the sets are snapped to a coarse grid, where constraints run through vertices.
			val snapped = seed % 2 == 0
			val points = DoubleArray(2 * pointCount) { if (snapped) random.nextInt(0, 12).toDouble() else random.nextDouble(0.0, 50.0) }
			val segments = List(random.nextInt(1, 25)) { intArrayOf(random.nextInt(pointCount), random.nextInt(pointCount)) }
			val result = triangulate(points, segments = segments)
			assertConstrainedDelaunayTriangulation(points, result, coversHull = true)
			assertEveryAcceptedSegmentIsPresent(points, result, segments)

			for (rejection in result.rejectedConstraints) {
				assertTrue(properlyCross(points, rejection.startPoint, rejection.endPoint, rejection.crossedStartPoint, rejection.crossedEndPoint))
			}

			// The same insertions with the internal structure validated after each one.
			insertAll(points, segments, prefersFlipsLeavingNoCrossing = true)
		}
	}

	@Test
	fun plainSloanAloneReachesTheSameTriangulation() {
		// Random doubles are in general position, where the constrained Delaunay triangulation is
		// unique: the fallback alone must land exactly where the preferred flips do.
		for (seed in 1..10) {
			val random = Random(6000 + seed)
			val pointCount = random.nextInt(8, 120)
			val points = DoubleArray(2 * pointCount) { random.nextDouble(0.0, 50.0) }
			val segments = List(random.nextInt(1, 25)) { intArrayOf(random.nextInt(pointCount), random.nextInt(pointCount)) }
			val preferred = insertAll(points, segments, prefersFlipsLeavingNoCrossing = true) ?: continue
			val plain = insertAll(points, segments, prefersFlipsLeavingNoCrossing = false) ?: continue
			assertEquals(normalizedTriangleSet(preferred.realTriangles()), normalizedTriangleSet(plain.realTriangles()))
			assertContentEquals(preferred.constrainedEdgePairs(), plain.constrainedEdgePairs())
		}
	}

	/**
	 * Triangulates and inserts two-point segments directly through the internal inserter, validating
	 * the structure after each one.
	 *
	 * @param DoubleArray    points                        The points.
	 * @param List<IntArray> segments                      The segments, in order.
	 * @param Boolean        prefersFlipsLeavingNoCrossing The inserter's flip preference.
	 * @return HalfEdgeTriangulation? The constrained triangulation, or null when the points span none.
	 */
	private fun insertAll(points: DoubleArray, segments: List<IntArray>, prefersFlipsLeavingNoCrossing: Boolean): HalfEdgeTriangulation? {
		val plan = planInsertion(points)
		val store = buildDelaunay(points, plan) ?: return null
		val inserter = ConstraintInserter(store, prefersFlipsLeavingNoCrossing)

		for (segment in segments) {
			val start = plan.canonicalIndex[segment[0]]
			val end = plan.canonicalIndex[segment[1]]

			if (start != end) {
				inserter.insert(start, end, isBoundary = false)
				store.validate()
			}
		}

		return store
	}

	/**
	 * Asserts that each accepted segment is in the result as the chain of constrained pieces between
	 * the distinct points lying on it.
	 *
	 * @param DoubleArray    points   The points.
	 * @param Triangulation  result   The result.
	 * @param List<IntArray> segments The two-point segments requested.
	 */
	private fun assertEveryAcceptedSegmentIsPresent(points: DoubleArray, result: Triangulation, segments: List<IntArray>) {
		val constrained = HashSet<Long>()

		for (position in 0 until result.constrainedEdges.size / 2) {
			constrained.add(edgeKey(result.constrainedEdges[2 * position], result.constrainedEdges[2 * position + 1]))
		}

		val rejected = result.rejectedConstraints.map { it.polylineIndex }.toSet()

		for ((segmentIndex, segment) in segments.withIndex()) {
			val start = result.canonicalIndex[segment[0]]
			val end = result.canonicalIndex[segment[1]]

			if (segmentIndex in rejected || start == end) {
				continue
			}

			val onSegment =
				(0 until points.size / 2)
					.filter { result.canonicalIndex[it] == it }
					.filter { it == start || it == end || (orientOf(points, start, end, it) == 0.0 && strictlyBetween(points, start, end, it)) }
					.sortedWith(compareBy({ points[2 * it] * signAlong(points, start, end, 0) }, { points[2 * it + 1] * signAlong(points, start, end, 1) }))

			for (position in 0 until onSegment.size - 1) {
				val low = minOf(onSegment[position], onSegment[position + 1])
				val high = maxOf(onSegment[position], onSegment[position + 1])
				assertTrue(constrained.contains(edgeKey(low, high)), "segment $segmentIndex is missing its piece ($low, $high)")
			}
		}
	}

	/**
	 * The direction a segment runs along one axis, for sorting the points on it.
	 *
	 * @param DoubleArray points The points.
	 * @param Int         start  The segment's start.
	 * @param Int         end    The segment's end.
	 * @param Int         axis   0 for x, 1 for y.
	 * @return Double 1.0 or -1.0.
	 */
	private fun signAlong(points: DoubleArray, start: Int, end: Int, axis: Int): Double = if (points[2 * end + axis] >= points[2 * start + axis]) 1.0 else -1.0

	/**
	 * A side x side integer lattice, row by row: point (column, row) has index row * side + column.
	 *
	 * @param Int side The lattice size.
	 * @return DoubleArray The points.
	 */
	private fun squareGrid(side: Int): DoubleArray {
		val points = DoubleArray(2 * side * side)

		for (row in 0 until side) {
			for (column in 0 until side) {
				points[2 * (row * side + column)] = column.toDouble()
				points[2 * (row * side + column) + 1] = row.toDouble()
			}
		}

		return points
	}
}