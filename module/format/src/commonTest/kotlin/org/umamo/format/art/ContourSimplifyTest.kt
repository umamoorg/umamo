package org.umamo.format.art

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Unit tests for the closed-ring Douglas-Peucker simplifier: epsilon-zero identity and
 * collinear collapse, the staircase collapse the 1.0 px default exists for, the under-3-points
 * fallback, the within-epsilon deviation guarantee, and determinism.
 */
class ContourSimplifyTest {
	@Test
	fun epsilonZeroKeepsAnExactRing() {
		// Traced rings have no collinear vertices (turn-only emission), so nothing can drop.
		val ring = intArrayOf(0, 0, 1, 0, 1, 1, 2, 1, 2, 2, 0, 2)
		assertContentEquals(ring, simplifyClosedRing(ring, 0f))
	}

	@Test
	fun rectangleSurvivesAnyEpsilon() {
		val rectangle = intArrayOf(0, 0, 100, 0, 100, 100, 0, 100)
		assertContentEquals(rectangle, simplifyClosedRing(rectangle, 5f))
	}

	@Test
	fun collinearVerticesDropAtEpsilonZero() {
		// Hand-built ring with an injected collinear midpoint — the tracer never emits one.
		val ring = intArrayOf(0, 0, 5, 0, 10, 0, 10, 10, 0, 10)
		assertContentEquals(intArrayOf(0, 0, 10, 0, 10, 10, 0, 10), simplifyClosedRing(ring, 0f))
	}

	@Test
	fun staircaseCollapsesAtTheDefaultEpsilon() {
		// A unit staircase from (0, 0) to (10, 10) deviates at most sqrt(2)/2 from the true
		// diagonal, so the 1.0 px default collapses it to a single segment.
		val ringValues = mutableListOf(0, 0)
		for (stepIndex in 1..10) {
			ringValues.add(stepIndex)
			ringValues.add(stepIndex - 1)
			ringValues.add(stepIndex)
			ringValues.add(stepIndex)
		}
		ringValues.add(0)
		ringValues.add(10)
		val ring = ringValues.toIntArray()

		val simplified = simplifyClosedRing(ring, DEFAULT_CONTOUR_EPSILON)
		assertContentEquals(intArrayOf(0, 0, 10, 10, 0, 10), simplified)
		assertMaxDeviationWithin(ring, simplified, DEFAULT_CONTOUR_EPSILON.toDouble())
	}

	@Test
	fun collapseBelowThreePointsFallsBackToTheInput() {
		// A 1xN sliver rectangle at the default epsilon would keep only its two anchors.
		val sliver = intArrayOf(0, 0, 5, 0, 5, 1, 0, 1)
		assertContentEquals(sliver, simplifyClosedRing(sliver, DEFAULT_CONTOUR_EPSILON))
	}

	@Test
	fun triangleIsAlreadyMinimal() {
		val triangle = intArrayOf(0, 0, 8, 0, 0, 8)
		assertContentEquals(triangle, simplifyClosedRing(triangle, 100f))
	}

	@Test
	fun outputIsDeterministic() {
		val ring = intArrayOf(0, 0, 3, 0, 5, 1, 8, 0, 8, 8, 4, 7, 0, 8)
		val first = simplifyClosedRing(ring, 1.5f)
		val second = simplifyClosedRing(ring, 1.5f)
		assertContentEquals(first, second)
	}

	/**
	 * Asserts the Douglas-Peucker deviation guarantee numerically: every input vertex lies
	 * within epsilon of the simplified ring (its nearest segment, closure included).
	 *
	 * @param IntArray original The input ring.
	 * @param IntArray simplified The simplified ring.
	 * @param Double epsilon The tolerance the simplification ran with.
	 */
	private fun assertMaxDeviationWithin(original: IntArray, simplified: IntArray, epsilon: Double) {
		val originalCount = original.size / 2
		for (pointIndex in 0 until originalCount) {
			val distance =
				distanceToRing(
					original[pointIndex * 2].toDouble(),
					original[pointIndex * 2 + 1].toDouble(),
					simplified,
				)
			assertTrue(
				distance <= epsilon + 1e-9,
				"dropped vertex index $pointIndex deviates $distance > $epsilon",
			)
		}
		assertEquals(0, simplified.size % 2, "simplified ring stays flat pairs")
	}

	/**
	 * Distance from a point to the nearest segment of a closed flat-point ring.
	 *
	 * @param Double pointX The point's x.
	 * @param Double pointY The point's y.
	 * @param IntArray ring The ring; the last point connects to the first.
	 * @return Double The minimum distance.
	 */
	private fun distanceToRing(pointX: Double, pointY: Double, ring: IntArray): Double {
		val pointCount = ring.size / 2
		var best = Double.MAX_VALUE
		for (pointIndex in 0 until pointCount) {
			val nextIndex = (pointIndex + 1) % pointCount
			val startX = ring[pointIndex * 2].toDouble()
			val startY = ring[pointIndex * 2 + 1].toDouble()
			val endX = ring[nextIndex * 2].toDouble()
			val endY = ring[nextIndex * 2 + 1].toDouble()
			val segmentDeltaX = endX - startX
			val segmentDeltaY = endY - startY
			val lengthSquared = segmentDeltaX * segmentDeltaX + segmentDeltaY * segmentDeltaY
			val projection =
				if (lengthSquared == 0.0) {
					0.0
				} else {
					(((pointX - startX) * segmentDeltaX + (pointY - startY) * segmentDeltaY) / lengthSquared)
						.coerceIn(0.0, 1.0)
				}
			val nearestX = startX + projection * segmentDeltaX
			val nearestY = startY + projection * segmentDeltaY
			val distance =
				sqrt(
					(pointX - nearestX) * (pointX - nearestX) + (pointY - nearestY) * (pointY - nearestY),
				)
			if (distance < best) {
				best = distance
			}
		}
		return best
	}
}
