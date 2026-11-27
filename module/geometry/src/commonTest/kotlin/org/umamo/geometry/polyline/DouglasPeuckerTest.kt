package org.umamo.geometry.polyline

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Douglas-Peucker keypoints: ends kept on an open polyline, collinear vertices dropped at zero
 * tolerance, corners kept, the deviation bound on random polylines and rings, ascending output, the
 * closed ring's anchor (its default and a chosen one), and the integer overload's exact tie and its
 * agreement with the Double one.
 */
class DouglasPeuckerTest {
	@Test
	fun shortPolylinesKeepEverything() {
		assertContentEquals(intArrayOf(), douglasPeuckerKeypoints(DoubleArray(0), 1.0, closed = false))
		assertContentEquals(intArrayOf(0, 1), douglasPeuckerKeypoints(doubleArrayOf(0.0, 0.0, 5.0, 5.0), 1.0, closed = true))
	}

	@Test
	fun collinearVerticesDropAtZeroTolerance() {
		val line = doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 2.0, 3.0, 3.0, 4.0, 4.0)
		assertContentEquals(intArrayOf(0, 4), douglasPeuckerKeypoints(line, 0.0, closed = false))
	}

	@Test
	fun aSquaresCornersSurviveItsMidpoints() {
		// Midpoints on every side; the lowest vertex (0, 0) anchors the ring.
		val square = doubleArrayOf(5.0, 0.0, 10.0, 0.0, 10.0, 5.0, 10.0, 10.0, 5.0, 10.0, 0.0, 10.0, 0.0, 5.0, 0.0, 0.0)
		assertContentEquals(intArrayOf(1, 3, 5, 7), douglasPeuckerKeypoints(square, 0.5, closed = true))
	}

	@Test
	fun droppedVerticesStayWithinTheTolerance() {
		val random = Random(139)

		repeat(200) {
			val vertexCount = random.nextInt(3, 60)
			val closed = random.nextBoolean()
			val points = DoubleArray(2 * vertexCount) { random.nextDouble(-50.0, 50.0) }
			val tolerance = random.nextDouble(0.0, 20.0)
			val kept = douglasPeuckerKeypoints(points, tolerance, closed)

			for (position in 1 until kept.size) {
				assertTrue(kept[position] > kept[position - 1], "kept indices ascend")
			}

			if (!closed) {
				assertTrue(kept.first() == 0 && kept.last() == vertexCount - 1, "an open polyline keeps both ends")
			}

			// Every dropped vertex lies within the tolerance of the segment between its kept neighbors.
			for (keptPosition in kept.indices) {
				if (!closed && keptPosition == kept.size - 1) {
					continue
				}

				val start = kept[keptPosition]
				val end = kept[(keptPosition + 1) % kept.size]
				var vertex = (start + 1) % vertexCount

				while (vertex != end) {
					val deviation = distanceToSegment(points, vertex, start, end)
					assertTrue(deviation <= tolerance + 1e-9, "vertex $vertex deviates $deviation > $tolerance")
					vertex = (vertex + 1) % vertexCount
				}
			}
		}
	}

	@Test
	fun aChosenRingAnchorIsKeptWithTheVertexFarthestFromIt() {
		// At an infinite tolerance only the two anchors survive: vertex 2 (10, 5) and the farthest from
		// it, (0, 10) - tied with (0, 0), and the lower index wins.
		val square = doubleArrayOf(5.0, 0.0, 10.0, 0.0, 10.0, 5.0, 10.0, 10.0, 5.0, 10.0, 0.0, 10.0, 0.0, 5.0, 0.0, 0.0)
		assertContentEquals(intArrayOf(2, 5), douglasPeuckerKeypoints(square, Double.POSITIVE_INFINITY, closed = true, ringAnchor = 2))
		// Left to itself the ring anchors at its lowest vertex, (0, 0), and the corner opposite.
		assertContentEquals(intArrayOf(3, 7), douglasPeuckerKeypoints(square, Double.POSITIVE_INFINITY, closed = true))
	}

	@Test
	fun theIntegerOverloadDropsAVertexExactlyAtTheTolerance() {
		// (4, 7) lies exactly 1 px from the segment (0, 0) - (6, 8): |4 * 8 - 7 * 6| / 10.  Measured
		// through a Double projection it comes out a few ulps over 1 and would be kept.
		val polyline = intArrayOf(0, 0, 4, 7, 6, 8)
		assertContentEquals(intArrayOf(0, 2), douglasPeuckerKeypoints(polyline, 1.0, closed = false))
		assertContentEquals(intArrayOf(0, 1, 2), douglasPeuckerKeypoints(polyline, 0.999, closed = false))
	}

	@Test
	fun theIntegerAndDoubleOverloadsAgreeAwayFromTies() {
		val random = Random(1012)

		repeat(300) {
			val vertexCount = random.nextInt(3, 80)
			val closed = random.nextBoolean()
			val points = IntArray(2 * vertexCount) { random.nextInt(-400, 400) }
			// A tolerance no lattice distance can equal, so rounding cannot decide a comparison.
			val tolerance = random.nextDouble(0.0, 30.0) + 0.000123
			val anchor = if (closed && random.nextBoolean()) random.nextInt(vertexCount) else null
			val fromIntegers = douglasPeuckerKeypoints(points, tolerance, closed, anchor)
			val fromDoubles = douglasPeuckerKeypoints(DoubleArray(points.size) { component -> points[component].toDouble() }, tolerance, closed, anchor)
			assertContentEquals(fromDoubles, fromIntegers, "$vertexCount vertices at $tolerance, anchor $anchor")

			if (anchor != null) {
				assertTrue(anchor in fromIntegers, "the chosen anchor is kept")
			}
		}
	}

	@Test
	fun malformedRequestsAreRejected() {
		assertFailsWith<IllegalArgumentException> { douglasPeuckerKeypoints(doubleArrayOf(0.0, 0.0, 1.0), 1.0, closed = false) }
		assertFailsWith<IllegalArgumentException> { douglasPeuckerKeypoints(doubleArrayOf(0.0, 0.0, 1.0, 1.0), -1.0, closed = false) }
		assertFailsWith<IllegalArgumentException> { douglasPeuckerKeypoints(doubleArrayOf(0.0, 0.0, 1.0, 1.0), Double.NaN, closed = false) }
		assertFailsWith<IllegalArgumentException> { douglasPeuckerKeypoints(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 0.0), 1.0, closed = false, ringAnchor = 0) }
		assertFailsWith<IllegalArgumentException> { douglasPeuckerKeypoints(doubleArrayOf(0.0, 0.0, 1.0, 1.0, 2.0, 0.0), 1.0, closed = true, ringAnchor = 3) }
		assertFailsWith<IllegalArgumentException> { douglasPeuckerKeypoints(intArrayOf(0, 0, 1 shl 30, 0), 1.0, closed = false) }
	}

	/**
	 * The distance from a vertex to the segment between two others.
	 *
	 * @param DoubleArray points The vertices.
	 * @param Int         vertex The vertex.
	 * @param Int         start  The segment's start.
	 * @param Int         end    The segment's end.
	 * @return Double The distance.
	 */
	private fun distanceToSegment(points: DoubleArray, vertex: Int, start: Int, end: Int): Double {
		val alongX = points[2 * end] - points[2 * start]
		val alongY = points[2 * end + 1] - points[2 * start + 1]
		val lengthSquared = alongX * alongX + alongY * alongY
		val offsetX = points[2 * vertex] - points[2 * start]
		val offsetY = points[2 * vertex + 1] - points[2 * start + 1]
		val fraction = if (lengthSquared > 0.0) ((offsetX * alongX + offsetY * alongY) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
		val gapX = offsetX - fraction * alongX
		val gapY = offsetY - fraction * alongY

		return sqrt(gapX * gapX + gapY * gapY)
	}
}