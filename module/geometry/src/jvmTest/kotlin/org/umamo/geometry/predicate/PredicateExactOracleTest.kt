package org.umamo.geometry.predicate

import java.math.BigDecimal
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * orient2d and incircle against an exact oracle on arbitrary doubles: `BigDecimal(double)` converts
 * without rounding and BigDecimal adds and multiplies exactly, so the determinant's sign is known.
 * JVM-only because commonMain has no arbitrary-precision type; the commonTest suite covers the
 * native side with oracles that need none.
 */
class PredicateExactOracleTest {
	@Test
	fun orientationMatchesTheExactSignOnNearlyCollinearPoints() {
		val random = Random(53)
		repeat(50_000) {
			val ax = random.nextDouble(-1000.0, 1000.0)
			val ay = random.nextDouble(-1000.0, 1000.0)
			val bx = random.nextDouble(-1000.0, 1000.0)
			val by = random.nextDouble(-1000.0, 1000.0)
			val along = random.nextDouble(-2.0, 3.0)
			val cx = nudge(ax + along * (bx - ax), random)
			val cy = nudge(ay + along * (by - ay), random)
			val exact = exactOrientation(ax, ay, bx, by, cx, cy)
			assertEquals(exact, signOf(orient2d(ax, ay, bx, by, cx, cy)), "($ax, $ay) ($bx, $by) ($cx, $cy)")
		}
	}

	@Test
	fun incircleMatchesTheExactSignOnNearlyCocircularPoints() {
		val random = Random(59)
		repeat(20_000) {
			val centerX = random.nextDouble(-1000.0, 1000.0)
			val centerY = random.nextDouble(-1000.0, 1000.0)
			val radius = random.nextDouble(0.01, 500.0)
			val points = DoubleArray(8)
			for (pointIndex in 0 until 4) {
				val angle = random.nextDouble(0.0, 2.0 * PI)
				points[2 * pointIndex] = nudge(centerX + radius * cos(angle), random)
				points[2 * pointIndex + 1] = nudge(centerY + radius * sin(angle), random)
			}
			val exact = exactIncircle(points)
			val computed = incircle(points[0], points[1], points[2], points[3], points[4], points[5], points[6], points[7])
			assertEquals(exact, signOf(computed), points.joinToString())
		}
	}

	/**
	 * The exact sign of orient2d, in BigDecimal.
	 *
	 * @param Double ax The first point's x.
	 * @param Double ay The first point's y.
	 * @param Double bx The second point's x.
	 * @param Double by The second point's y.
	 * @param Double cx The third point's x.
	 * @param Double cy The third point's y.
	 * @return Int The determinant's sign.
	 */
	private fun exactOrientation(ax: Double, ay: Double, bx: Double, by: Double, cx: Double, cy: Double): Int {
		val acx = exact(ax).subtract(exact(cx))
		val acy = exact(ay).subtract(exact(cy))
		val bcx = exact(bx).subtract(exact(cx))
		val bcy = exact(by).subtract(exact(cy))
		return acx.multiply(bcy).subtract(acy.multiply(bcx)).signum()
	}

	/**
	 * The exact sign of incircle, in BigDecimal.
	 *
	 * @param DoubleArray points ax, ay, bx, by, cx, cy, dx, dy.
	 * @return Int The determinant's sign.
	 */
	private fun exactIncircle(points: DoubleArray): Int {
		val adx = exact(points[0]).subtract(exact(points[6]))
		val ady = exact(points[1]).subtract(exact(points[7]))
		val bdx = exact(points[2]).subtract(exact(points[6]))
		val bdy = exact(points[3]).subtract(exact(points[7]))
		val cdx = exact(points[4]).subtract(exact(points[6]))
		val cdy = exact(points[5]).subtract(exact(points[7]))
		val alift = adx.multiply(adx).add(ady.multiply(ady))
		val blift = bdx.multiply(bdx).add(bdy.multiply(bdy))
		val clift = cdx.multiply(cdx).add(cdy.multiply(cdy))
		val determinant =
			alift.multiply(bdx.multiply(cdy).subtract(cdx.multiply(bdy)))
				.add(blift.multiply(cdx.multiply(ady).subtract(adx.multiply(cdy))))
				.add(clift.multiply(adx.multiply(bdy).subtract(bdx.multiply(ady))))
		return determinant.signum()
	}

	/**
	 * A double's exact decimal value.
	 *
	 * @param Double value The value.
	 * @return BigDecimal The same value, unrounded.
	 */
	private fun exact(value: Double): BigDecimal = BigDecimal(value)
}