package org.umamo.geometry.mesh

import org.umamo.geometry.predicate.orient2d
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.sqrt

/**
 * A mesh's triangle quality, for diagnostics: how small its smallest angles are.  Near-equilateral
 * triangles (smallest angle near 60 degrees) deform best; slivers (a few degrees) deform badly.
 *
 * A plain class: [smallestAngleHistogram] is an array.
 *
 * @property Double   minimumAngleDegrees     The smallest angle of any non-degenerate triangle, or 0.0 when there is none.
 * @property IntArray smallestAngleHistogram  Non-degenerate triangles counted by their smallest angle in 10-degree buckets: [0, 10), [10, 20), ..., [50, 60].
 * @property Int      degenerateTriangleCount Triangles of exactly zero area, left out of both of the above.
 */
public class MeshQuality(
	public val minimumAngleDegrees: Double,
	public val smallestAngleHistogram: IntArray,
	public val degenerateTriangleCount: Int,
)

/**
 * Measures this mesh's triangle quality.  Angles come from acos, so they are diagnostic only - never
 * an input to a decision that must be deterministic across platforms.
 *
 * @return MeshQuality The angle statistics.
 */
public fun PlanarTriangleMesh.measureQuality(): MeshQuality {
	var minimumAngle = Double.POSITIVE_INFINITY
	val histogram = IntArray(HISTOGRAM_BUCKETS)
	var degenerateCount = 0

	for (triangle in 0 until triangleCount) {
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
			degenerateCount++
			continue
		}

		val smallest =
			minOf(
				angleAt(firstX, firstY, secondX, secondY, thirdX, thirdY),
				angleAt(secondX, secondY, thirdX, thirdY, firstX, firstY),
				angleAt(thirdX, thirdY, firstX, firstY, secondX, secondY),
			)
		minimumAngle = minOf(minimumAngle, smallest)
		histogram[(smallest / BUCKET_DEGREES).toInt().coerceIn(0, HISTOGRAM_BUCKETS - 1)]++
	}

	return MeshQuality(if (minimumAngle.isFinite()) minimumAngle else 0.0, histogram, degenerateCount)
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

/** The histogram's bucket width in degrees. */
private const val BUCKET_DEGREES: Double = 10.0

/** The histogram's bucket count: a triangle's smallest angle never exceeds 60 degrees. */
private const val HISTOGRAM_BUCKETS: Int = 6