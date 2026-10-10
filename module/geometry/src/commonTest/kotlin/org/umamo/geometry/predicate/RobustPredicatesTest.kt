package org.umamo.geometry.predicate

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Sign tests for orient2d and incircle against oracles that need no exact arithmetic library:
 * Kettner's ulp grid (the sign is known analytically), integer points (exact in Long), cocircular
 * integer points nudged by one ulp (the side is known geometrically), and the determinants' own
 * antisymmetries on near-degenerate input.  Runs on the JVM and natively on linuxX64; the jvmTest
 * suite adds a BigDecimal oracle on arbitrary doubles.
 */
class RobustPredicatesTest {
	@Test
	fun orientationOfTheUlpGridIsExact() {
		// Kettner et al., "Classroom examples of robustness problems in geometric computations": p is
		// on a 256 x 256 grid of ulp steps at (0.5, 0.5), q and r lie on y = x, and orient2d(p, q, r)
		// is exactly 12 * (py - px), so its sign is sign(j - i).  Naive evaluation gets it wrong.
		var naiveMistakes = 0
		for (stepX in 0 until 256) {
			for (stepY in 0 until 256) {
				val pointX = 0.5 + stepX * ULP_AT_HALF
				val pointY = 0.5 + stepY * ULP_AT_HALF
				val expected = (stepY - stepX).coerceIn(-1, 1)
				assertEquals(expected, signOf(orient2d(pointX, pointY, 12.0, 12.0, 24.0, 24.0)), "grid step ($stepX, $stepY)")
				val naive = (pointX - 24.0) * (12.0 - 24.0) - (pointY - 24.0) * (12.0 - 24.0)
				if (signOf(naive) != expected) {
					naiveMistakes++
				}
			}
		}
		assertTrue(naiveMistakes > 0, "the grid must defeat naive evaluation, or it tests nothing")
	}

	@Test
	fun orientationOfIntegerPointsMatchesLongArithmetic() {
		val random = Random(31)
		for (range in intArrayOf(8, 1 shl 20)) {
			repeat(20_000) {
				val coordinates = IntArray(6) { random.nextInt(-range, range + 1) }
				val exact = exactOrientation(coordinates)
				val computed =
					orient2d(
						coordinates[0].toDouble(),
						coordinates[1].toDouble(),
						coordinates[2].toDouble(),
						coordinates[3].toDouble(),
						coordinates[4].toDouble(),
						coordinates[5].toDouble(),
					)
				assertEquals(exact.coerceIn(-1, 1), signOf(computed), coordinates.joinToString())
			}
		}
	}

	@Test
	fun incircleOfIntegerPointsMatchesLongArithmetic() {
		val random = Random(37)
		for (range in intArrayOf(8, 1 shl 13)) {
			repeat(20_000) {
				val coordinates = IntArray(8) { random.nextInt(-range, range + 1) }
				val exact = exactIncircle(coordinates)
				val computed =
					incircle(
						coordinates[0].toDouble(),
						coordinates[1].toDouble(),
						coordinates[2].toDouble(),
						coordinates[3].toDouble(),
						coordinates[4].toDouble(),
						coordinates[5].toDouble(),
						coordinates[6].toDouble(),
						coordinates[7].toDouble(),
					)
				assertEquals(exact.coerceIn(-1, 1), signOf(computed), coordinates.joinToString())
			}
		}
	}

	@Test
	fun cocircularPointsAndTheirOneUlpNeighbors() {
		// (5, 0), (0, 5), (-5, 0) turn counterclockwise; every point at distance exactly 5 is on the
		// circle, and one ulp toward or away from the center along a nonzero axis lands inside or
		// outside.  The same holds with the whole picture moved a million units, where the
		// cancellation is far harder - the nudge is taken AFTER the shift, at the shifted magnitude.
		for (offset in doubleArrayOf(0.0, 1_000_000.0)) {
			for ((pointX, pointY) in listOf(0.0 to -5.0, 3.0 to 4.0, -4.0 to -3.0)) {
				val shiftedX = pointX + offset
				val shiftedY = pointY + offset
				assertCircleSide(0, offset, shiftedX, shiftedY)
				if (pointX != 0.0) {
					assertCircleSide(1, offset, stepToward(shiftedX, offset), shiftedY)
					assertCircleSide(-1, offset, stepToward(shiftedX, if (pointX > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY), shiftedY)
				}
				if (pointY != 0.0) {
					assertCircleSide(1, offset, shiftedX, stepToward(shiftedY, offset))
					assertCircleSide(-1, offset, shiftedX, stepToward(shiftedY, if (pointY > 0.0) Double.POSITIVE_INFINITY else Double.NEGATIVE_INFINITY))
				}
			}
		}
	}

	@Test
	fun orientationIsAntisymmetricOnNearlyCollinearPoints() {
		val random = Random(41)
		repeat(20_000) {
			val ax = random.nextDouble()
			val ay = random.nextDouble()
			val bx = random.nextDouble()
			val by = random.nextDouble()
			val along = random.nextDouble(-1.0, 2.0)
			// c lands within rounding of the line through a and b, then one more ulp nudge either way.
			val cx = nudge(ax + along * (bx - ax), random)
			val cy = nudge(ay + along * (by - ay), random)
			val sign = signOf(orient2d(ax, ay, bx, by, cx, cy))
			assertEquals(sign, signOf(orient2d(bx, by, cx, cy, ax, ay)))
			assertEquals(sign, signOf(orient2d(cx, cy, ax, ay, bx, by)))
			assertEquals(-sign, signOf(orient2d(bx, by, ax, ay, cx, cy)))
			assertEquals(-sign, signOf(orient2d(ax, ay, cx, cy, bx, by)))
		}
	}

	@Test
	fun incircleIsAntisymmetricOnNearlyCocircularPoints() {
		val random = Random(43)
		repeat(5_000) {
			val centerX = random.nextDouble(-100.0, 100.0)
			val centerY = random.nextDouble(-100.0, 100.0)
			val radius = random.nextDouble(0.5, 50.0)
			val circle = DoubleArray(8)
			for (pointIndex in 0 until 4) {
				val angle = random.nextDouble(0.0, 2.0 * PI)
				circle[2 * pointIndex] = nudge(centerX + radius * cos(angle), random)
				circle[2 * pointIndex + 1] = nudge(centerY + radius * sin(angle), random)
			}
			val sign = signOf(incircleOf(circle, 0, 1, 2, 3))
			// incircle is a 4 x 4 determinant: an even permutation keeps its sign, a swap flips it.
			assertEquals(sign, signOf(incircleOf(circle, 1, 2, 0, 3)))
			assertEquals(-sign, signOf(incircleOf(circle, 1, 0, 2, 3)))
			assertEquals(-sign, signOf(incircleOf(circle, 3, 1, 2, 0)))
			// A four-cycle is three swaps, so it flips the sign too.
			assertEquals(-sign, signOf(incircleOf(circle, 3, 0, 1, 2)))
		}
	}

	/**
	 * Asserts which side of the circle through (5, 0), (0, 5), (-5, 0) a point falls on, with the
	 * circle moved diagonally by [offset].
	 *
	 * @param Int    expected The expected sign: 1 inside, 0 on, -1 outside.
	 * @param Double offset   The shift applied to the circle.
	 * @param Double queryX   The query point's x, already shifted.
	 * @param Double queryY   The query point's y, already shifted.
	 */
	private fun assertCircleSide(expected: Int, offset: Double, queryX: Double, queryY: Double) {
		val computed = incircle(5.0 + offset, offset, offset, 5.0 + offset, -5.0 + offset, offset, queryX, queryY)
		assertEquals(expected, signOf(computed), "($queryX, $queryY) against the circle shifted by $offset")
	}

	/**
	 * The exact sign of orient2d over integer coordinates, computed in Long.
	 *
	 * @param IntArray coordinates ax, ay, bx, by, cx, cy.
	 * @return Int The determinant's sign.
	 */
	private fun exactOrientation(coordinates: IntArray): Int {
		val acx = coordinates[0].toLong() - coordinates[4]
		val acy = coordinates[1].toLong() - coordinates[5]
		val bcx = coordinates[2].toLong() - coordinates[4]
		val bcy = coordinates[3].toLong() - coordinates[5]
		return (acx * bcy - acy * bcx).coerceIn(-1L, 1L).toInt()
	}

	/**
	 * The exact sign of incircle over integer coordinates, computed in Long.
	 *
	 * @param IntArray coordinates ax, ay, bx, by, cx, cy, dx, dy, each within 2^13.
	 * @return Int The determinant's sign.
	 */
	private fun exactIncircle(coordinates: IntArray): Int {
		val adx = coordinates[0].toLong() - coordinates[6]
		val ady = coordinates[1].toLong() - coordinates[7]
		val bdx = coordinates[2].toLong() - coordinates[6]
		val bdy = coordinates[3].toLong() - coordinates[7]
		val cdx = coordinates[4].toLong() - coordinates[6]
		val cdy = coordinates[5].toLong() - coordinates[7]
		val determinant =
			(adx * adx + ady * ady) * (bdx * cdy - cdx * bdy) +
				(bdx * bdx + bdy * bdy) * (cdx * ady - adx * cdy) +
				(cdx * cdx + cdy * cdy) * (adx * bdy - bdx * ady)
		return determinant.coerceIn(-1L, 1L).toInt()
	}

	/**
	 * incircle over four points picked from an array.
	 *
	 * @param DoubleArray points The points, x0, y0, ...
	 * @param Int         first  The first circle point's index.
	 * @param Int         second The second circle point's index.
	 * @param Int         third  The third circle point's index.
	 * @param Int         query  The query point's index.
	 * @return Double The predicate's value.
	 */
	private fun incircleOf(points: DoubleArray, first: Int, second: Int, third: Int, query: Int): Double =
		incircle(
			points[2 * first],
			points[2 * first + 1],
			points[2 * second],
			points[2 * second + 1],
			points[2 * third],
			points[2 * third + 1],
			points[2 * query],
			points[2 * query + 1],
		)
}

/** One ulp of 0.5 (2^-53). */
internal const val ULP_AT_HALF: Double = 1.0 / (1L shl 53)

/**
 * The sign of a predicate value.
 *
 * @param Double value The value.
 * @return Int 1, 0, or -1.
 */
internal fun signOf(value: Double): Int =
	if (value > 0.0) {
		1
	} else if (value < 0.0) {
		-1
	} else {
		0
	}

/**
 * The neighbor of a finite double one ulp toward a target.
 *
 * @param Double value  A finite, nonzero value.
 * @param Double target The direction to step in; must differ from [value].
 * @return Double The adjacent double on the target's side.
 */
internal fun stepToward(value: Double, target: Double): Double {
	val bits = value.toRawBits()
	// For positive values the raw bits grow with the value; for negative values they shrink.
	val growsWithBits = value > 0.0
	val stepUp = target > value
	return Double.fromBits(if (stepUp == growsWithBits) bits + 1 else bits - 1)
}

/**
 * Moves a double by at most one ulp in a random direction.
 *
 * @param Double value  The value.
 * @param Random random The source of the direction.
 * @return Double The value, or one of its neighbors.
 */
internal fun nudge(value: Double, random: Random): Double {
	if (value == 0.0) {
		return value
	}
	return Double.fromBits(value.toRawBits() + random.nextInt(-1, 2))
}