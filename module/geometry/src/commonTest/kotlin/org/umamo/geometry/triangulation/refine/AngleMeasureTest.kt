package org.umamo.geometry.triangulation.refine

import kotlin.math.PI
import kotlin.math.asin
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tan
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The angle tests refinement decides with: the series sine against the library's, a triangle's
 * smallest angle against acos, the lens against the angle it stands for, and Float stepping.
 */
class AngleMeasureTest {
	@Test
	fun theSeriesSineMatchesTheLibrarySine() {
		for (step in 0..360) {
			val degrees = step * 0.25
			val expected = sin(degrees * PI / 180.0)

			assertEquals(expected * expected, sineSquaredOfDegrees(degrees), 1e-15, "at $degrees degrees")
		}
	}

	@Test
	fun theHalfAngleCotangentMatchesTheLibrary() {
		for (degrees in listOf(10.0, 20.0, 28.0, 33.0, 45.0)) {
			assertEquals(1.0 / tan(degrees * PI / 360.0), cotangentOfHalfAngle(sineSquaredOfDegrees(degrees)), 1e-12, "at $degrees degrees")
		}
	}

	@Test
	fun aTriangleShapeFindsItsSmallestAngle() {
		val random = Random(17)
		val shape = TriangleShape()

		repeat(500) {
			val corners = DoubleArray(6) { random.nextDouble(-50.0, 50.0) }

			if (orientationOf(corners) <= 0.0) {
				// Swap two corners so the triangle is positively oriented.
				corners[2] = corners[4].also { corners[4] = corners[2] }
				corners[3] = corners[5].also { corners[5] = corners[3] }
			}

			shape.measure(corners[0], corners[1], corners[2], corners[3], corners[4], corners[5])
			val measured = smallestAngleDegrees(corners, intArrayOf(0, 1, 2), 0)
			val fromSine = asin(sqrt(shape.smallestSineSquared)) * 180.0 / PI

			assertEquals(measured, fromSine, 1e-6, "the smallest angle")
			val opposite = (0 until 3).minBy { corner -> oppositeLengthSquared(corners, corner) }
			assertEquals(oppositeLengthSquared(corners, opposite), oppositeLengthSquared(corners, shape.smallestCorner), "the smallest angle faces the shortest edge")
		}
	}

	@Test
	fun theLensHoldsWhatSeesTheSegmentWiderThanItsAngle() {
		// A 30-degree target: the 120-degree lens.
		val cosineSquared = lensCosineSquared(sineSquaredOfDegrees(30.0))
		assertEquals(0.25, cosineSquared, 1e-15)

		assertTrue(liesInLens(-1.0, 0.0, 1.0, 0.0, 0.0, 0.5, cosineSquared), "126.9 degrees")
		assertFalse(liesInLens(-1.0, 0.0, 1.0, 0.0, 0.0, 0.6, cosineSquared), "118.1 degrees")
		assertTrue(liesInLens(-1.0, 0.0, 1.0, 0.0, 0.3, 0.0, cosineSquared), "on the segment: 180 degrees")
		assertFalse(liesInLens(-1.0, 0.0, 1.0, 0.0, 0.0, 1.0, cosineSquared), "90 degrees: the diametral circle, not the lens")
		assertFalse(liesInLens(-1.0, 0.0, 1.0, 0.0, 2.0, 0.0, cosineSquared), "beyond an end")
		assertFalse(liesInLens(-1.0, 0.0, 1.0, 0.0, 0.0, -0.6, cosineSquared), "118.1 degrees below")
		assertTrue(liesInLens(-1.0, 0.0, 1.0, 0.0, 0.0, -0.5, cosineSquared), "126.9 degrees below")
	}

	@Test
	fun floatNeighborsStepOneUnitInTheLastPlace() {
		assertEquals(Float.fromBits(0x3F800001), floatNeighbor(1.0f, 1))
		assertEquals(Float.fromBits(0x3F7FFFFF), floatNeighbor(1.0f, -1))
		assertEquals(-Float.fromBits(0x3F7FFFFF), floatNeighbor(-1.0f, 1))
		assertEquals(-Float.fromBits(0x3F800001), floatNeighbor(-1.0f, -1))
		assertEquals(Float.MIN_VALUE, floatNeighbor(0.0f, 1))
		assertEquals(-Float.MIN_VALUE, floatNeighbor(-0.0f, -1))
		assertEquals(0.0f, floatNeighbor(Float.MIN_VALUE, -1))
		assertEquals(2.5f, floatNeighbor(2.5f, 0))
	}

	/**
	 * Twice a triangle's signed area.
	 *
	 * @param DoubleArray corners Three corners.
	 * @return Double Positive when counterclockwise.
	 */
	private fun orientationOf(corners: DoubleArray): Double = (corners[2] - corners[0]) * (corners[5] - corners[1]) - (corners[3] - corners[1]) * (corners[4] - corners[0])

	/**
	 * The squared length of the edge opposite a corner.
	 *
	 * @param DoubleArray corners Three corners.
	 * @param Int         corner  The corner.
	 * @return Double The squared length.
	 */
	private fun oppositeLengthSquared(corners: DoubleArray, corner: Int): Double {
		val start = (corner + 1) % 3
		val end = (corner + 2) % 3

		return squaredDistance(corners[2 * start], corners[2 * start + 1], corners[2 * end], corners[2 * end + 1])
	}
}