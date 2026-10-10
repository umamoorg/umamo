package org.umamo.geometry.polyline

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Arc-length resampling: even spacing per span with the interval rounded to divide it, keypoints
 * copied exactly, at least one piece per span, rings without keypoints, open polylines keeping both
 * ends, samples lying on the original polyline, and input validation.
 */
class ResampledPolylineTest {
	@Test
	fun aSquareRingResamplesEvenlyBetweenItsCorners() {
		val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
		val result = resampleClosedRing(square, intArrayOf(0, 1, 2, 3), 2.5)
		assertEquals(16, result.pointCount)
		assertContentEquals(intArrayOf(0, 4, 8, 12), result.keypointOutputIndices)
		assertContentEquals(doubleArrayOf(0.0, 0.0, 2.5, 0.0, 5.0, 0.0, 7.5, 0.0, 10.0, 0.0), result.points.copyOf(10))
		assertContentEquals(doubleArrayOf(10.0, 10.0, 7.5, 10.0), result.points.copyOfRange(16, 20))
	}

	@Test
	fun theIntervalRoundsToDivideEachSpanEvenly() {
		val line = doubleArrayOf(0.0, 0.0, 10.0, 0.0)
		// 10 / 3 rounds to 3 pieces, each 10 / 3 long rather than 3.
		val thirds = resamplePolyline(line, intArrayOf(), 3.0)
		assertEquals(4, thirds.pointCount)
		assertEquals(10.0 / 3.0, thirds.points[2], 1e-12)
		assertEquals(20.0 / 3.0, thirds.points[4], 1e-12)
	}

	@Test
	fun keypointsAreCopiedExactly() {
		val random = Random(89)
		val ring = DoubleArray(2 * 40) { random.nextDouble(-100.0, 100.0) }
		val keypoints = intArrayOf(0, 7, 8, 21, 39)
		val result = resampleClosedRing(ring, keypoints, 3.7)

		for ((position, keypoint) in keypoints.withIndex()) {
			val output = result.keypointOutputIndices[position]
			assertEquals(ring[2 * keypoint], result.points[2 * output])
			assertEquals(ring[2 * keypoint + 1], result.points[2 * output + 1])
		}
	}

	@Test
	fun aSpanShorterThanTheIntervalKeepsOnePiece() {
		val ring = doubleArrayOf(0.0, 0.0, 1.0, 0.0, 1.0, 1.0)
		val result = resampleClosedRing(ring, intArrayOf(0, 1, 2), 50.0)
		assertContentEquals(ring, result.points)
	}

	@Test
	fun aRingWithoutKeypointsStartsAtItsFirstVertex() {
		val square = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)
		val result = resampleClosedRing(square, intArrayOf(), 4.0)
		// A 40-long perimeter in 10 pieces of 4: corners (10, 0) and (0, 10) are not samples.
		assertEquals(10, result.pointCount)
		assertContentEquals(intArrayOf(0), result.keypointOutputIndices)
		assertEquals(0.0, result.points[0])
		assertEquals(0.0, result.points[1])
		assertAllOnPolyline(square, closed = true, result.points)
		assertEvenSpacingAlongPerimeter(result.points, 4.0)
	}

	@Test
	fun anOpenPolylineKeepsBothEnds() {
		val bent = doubleArrayOf(0.0, 0.0, 6.0, 0.0, 6.0, 8.0)
		val result = resamplePolyline(bent, intArrayOf(), 1.4)
		assertContentEquals(intArrayOf(0, result.pointCount - 1), result.keypointOutputIndices)
		assertEquals(6.0, result.points[2 * result.pointCount - 2])
		assertEquals(8.0, result.points[2 * result.pointCount - 1])
		// 14 long, 1.4 apart: ten pieces, the corner (6, 0) among neither keypoints nor samples.
		assertEquals(11, result.pointCount)
		assertAllOnPolyline(bent, closed = false, result.points)
	}

	@Test
	fun aSinglePointPolylineStaysOnePoint() {
		val result = resamplePolyline(doubleArrayOf(3.0, 4.0), intArrayOf(), 1.0)
		assertContentEquals(doubleArrayOf(3.0, 4.0), result.points)
		assertContentEquals(intArrayOf(0), result.keypointOutputIndices)
	}

	@Test
	fun malformedRequestsAreRejected() {
		val line = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 20.0, 0.0)
		assertFailsWith<IllegalArgumentException> { resamplePolyline(line, intArrayOf(), 0.0) }
		assertFailsWith<IllegalArgumentException> { resamplePolyline(line, intArrayOf(), Double.NaN) }
		assertFailsWith<IllegalArgumentException> { resamplePolyline(line, intArrayOf(3), 1.0) }
		assertFailsWith<IllegalArgumentException> { resampleClosedRing(line, intArrayOf(2, 1), 1.0) }
		assertFailsWith<IllegalArgumentException> { resampleClosedRing(doubleArrayOf(0.0, 0.0, 1.0), intArrayOf(), 1.0) }
	}

	/**
	 * Asserts that every sample lies on the polyline, to within rounding.
	 *
	 * @param DoubleArray polyline The original vertices.
	 * @param Boolean     closed   Whether the last vertex joins the first.
	 * @param DoubleArray samples  The resampled points.
	 */
	private fun assertAllOnPolyline(polyline: DoubleArray, closed: Boolean, samples: DoubleArray) {
		val vertexCount = polyline.size / 2
		val segmentCount = if (closed) vertexCount else vertexCount - 1

		for (sample in 0 until samples.size / 2) {
			var nearest = Double.POSITIVE_INFINITY

			for (segment in 0 until segmentCount) {
				val next = (segment + 1) % vertexCount
				nearest = minOf(nearest, distanceToSegment(samples[2 * sample], samples[2 * sample + 1], polyline[2 * segment], polyline[2 * segment + 1], polyline[2 * next], polyline[2 * next + 1]))
			}

			assertTrue(nearest < 1e-9, "sample $sample is $nearest off the polyline")
		}
	}

	/**
	 * Asserts that consecutive samples of a ring are equally far apart along a square's perimeter,
	 * measured as path length (samples straddling a corner are closer as the crow flies).
	 *
	 * @param DoubleArray samples  The resampled ring.
	 * @param Double      expected The expected path spacing.
	 */
	private fun assertEvenSpacingAlongPerimeter(samples: DoubleArray, expected: Double) {
		val positions = (0 until samples.size / 2).map { perimeterPosition(samples[2 * it], samples[2 * it + 1]) }

		for (sample in 1 until positions.size) {
			assertEquals(expected, positions[sample] - positions[sample - 1], 1e-9)
		}
	}

	/**
	 * Where a point on the 10 x 10 square's perimeter lies along it, counterclockwise with y up from
	 * the origin.
	 *
	 * @param Double x The point's x.
	 * @param Double y The point's y.
	 * @return Double The path length from the origin.
	 */
	private fun perimeterPosition(x: Double, y: Double): Double =
		when {
			abs(y) < 1e-9 -> x
			abs(x - 10.0) < 1e-9 -> 10.0 + y
			abs(y - 10.0) < 1e-9 -> 30.0 - x
			else -> 40.0 - y
		}

	/**
	 * The distance from a point to a segment.
	 *
	 * @param Double pointX The point's x.
	 * @param Double pointY The point's y.
	 * @param Double startX The segment's start x.
	 * @param Double startY The segment's start y.
	 * @param Double endX   The segment's end x.
	 * @param Double endY   The segment's end y.
	 * @return Double The distance.
	 */
	private fun distanceToSegment(pointX: Double, pointY: Double, startX: Double, startY: Double, endX: Double, endY: Double): Double {
		val alongX = endX - startX
		val alongY = endY - startY
		val lengthSquared = alongX * alongX + alongY * alongY
		val fraction = if (lengthSquared > 0.0) (((pointX - startX) * alongX + (pointY - startY) * alongY) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
		val offsetX = startX + fraction * alongX - pointX
		val offsetY = startY + fraction * alongY - pointY

		return sqrt(offsetX * offsetX + offsetY * offsetY)
	}
}