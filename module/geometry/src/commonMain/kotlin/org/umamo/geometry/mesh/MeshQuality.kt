package org.umamo.geometry.mesh

import org.umamo.geometry.predicate.orient2d
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.ceil
import kotlin.math.sqrt

/**
 * A mesh's triangle quality, for diagnostics: how small its smallest angles are.  Near-equilateral
 * triangles (smallest angle near 60 degrees) deform best; slivers (a few degrees) deform badly.
 *
 * A plain class: [smallestAngleHistogram] is an array.
 *
 * @property Double   minimumAngleDegrees     The smallest angle of any non-degenerate triangle, or 0.0 when there is none.
 * @property IntArray smallestAngleHistogram  Non-degenerate triangles counted by their smallest angle in buckets [bucketDegrees) wide: [0, b), [b, 2b), ..., the last one closed at 60.
 * @property Int      degenerateTriangleCount Triangles of exactly zero area, left out of both of the above.
 * @property Double   bucketDegrees           The histogram's bucket width in degrees.
 */
public class MeshQuality(
	public val minimumAngleDegrees: Double,
	public val smallestAngleHistogram: IntArray,
	public val degenerateTriangleCount: Int,
	public val bucketDegrees: Double,
) {
	/**
	 * The non-degenerate triangles whose smallest angle is below a whole number of buckets.
	 *
	 * @param Double degrees A multiple of [bucketDegrees], 0 to 60.
	 * @return Int The count.
	 */
	public fun countBelow(degrees: Double): Int {
		var count = 0

		for (bucket in smallestAngleHistogram.indices) {
			if ((bucket + 1) * bucketDegrees <= degrees) {
				count += smallestAngleHistogram[bucket]
			}
		}

		return count
	}
}

/**
 * Measures this mesh's triangle quality.  Angles come from acos, so they are diagnostic only - never
 * an input to a decision that must be deterministic across platforms.
 *
 * @param Double bucketDegrees The histogram's bucket width in degrees (positive, at most 60).
 * @return MeshQuality The angle statistics.
 */
public fun PlanarTriangleMesh.measureQuality(bucketDegrees: Double = DEFAULT_BUCKET_DEGREES): MeshQuality {
	require(bucketDegrees > 0.0 && bucketDegrees <= LARGEST_SMALLEST_ANGLE) { "the bucket width $bucketDegrees is outside (0, 60] degrees" }
	var minimumAngle = Double.POSITIVE_INFINITY
	val bucketCount = smallestAngleBucketCount(bucketDegrees)
	val histogram = IntArray(bucketCount)
	var degenerateCount = 0

	for (triangle in 0 until triangleCount) {
		val smallest = smallestAngleDegrees(triangle)

		if (smallest.isNaN()) {
			degenerateCount++

			continue
		}

		minimumAngle = minOf(minimumAngle, smallest)
		histogram[(smallest / bucketDegrees).toInt().coerceIn(0, bucketCount - 1)]++
	}

	return MeshQuality(if (minimumAngle.isFinite()) minimumAngle else 0.0, histogram, degenerateCount, bucketDegrees)
}

/**
 * How many buckets of a width a smallest-angle histogram has: a triangle's smallest angle is at most 60
 * degrees.
 *
 * @param Double bucketDegrees The bucket width in degrees (positive, at most 60).
 * @return Int The bucket count.
 */
public fun smallestAngleBucketCount(bucketDegrees: Double): Int = ceil(LARGEST_SMALLEST_ANGLE / bucketDegrees).toInt()

/**
 * One triangle's smallest angle, from acos, so diagnostic only (see [measureQuality]).
 *
 * @param Int triangle The triangle.
 * @return Double The smallest angle in degrees, or NaN for a degenerate triangle (its corners on one line), which has none.
 */
public fun PlanarTriangleMesh.smallestAngleDegrees(triangle: Int): Double {
	val first = triangles[3 * triangle]
	val second = triangles[3 * triangle + 1]
	val third = triangles[3 * triangle + 2]
	val firstX = positions[2 * first].toDouble()
	val firstY = positions[2 * first + 1].toDouble()
	val secondX = positions[2 * second].toDouble()
	val secondY = positions[2 * second + 1].toDouble()
	val thirdX = positions[2 * third].toDouble()
	val thirdY = positions[2 * third + 1].toDouble()

	if (orient2d(firstX, firstY, secondX, secondY, thirdX, thirdY) == 0.0) {
		return Double.NaN
	}

	return minOf(
		angleAt(firstX, firstY, secondX, secondY, thirdX, thirdY),
		angleAt(secondX, secondY, thirdX, thirdY, firstX, firstY),
		angleAt(thirdX, thirdY, firstX, firstY, secondX, secondY),
	)
}

/**
 * The interior angle at a corner of a triangle, in degrees.
 *
 * @param Double cornerX The corner's x.
 * @param Double cornerY The corner's y.
 * @param Double nextX   The next corner's x.
 * @param Double nextY   The next corner's y.
 * @param Double otherX  The remaining corner's x.
 * @param Double otherY  The remaining corner's y.
 * @return Double The angle in degrees.
 */
private fun angleAt(cornerX: Double, cornerY: Double, nextX: Double, nextY: Double, otherX: Double, otherY: Double): Double {
	val toNextX = nextX - cornerX
	val toNextY = nextY - cornerY
	val toOtherX = otherX - cornerX
	val toOtherY = otherY - cornerY
	val cosine = (toNextX * toOtherX + toNextY * toOtherY) / (sqrt(toNextX * toNextX + toNextY * toNextY) * sqrt(toOtherX * toOtherX + toOtherY * toOtherY))

	return acos(cosine.coerceIn(-1.0, 1.0)) * 180.0 / PI
}

/** The histogram's default bucket width in degrees. */
private const val DEFAULT_BUCKET_DEGREES: Double = 10.0

/** A triangle's smallest angle never exceeds 60 degrees, so the histogram ends there. */
private const val LARGEST_SMALLEST_ANGLE: Double = 60.0