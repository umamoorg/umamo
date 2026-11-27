package org.umamo.render.eval

import kotlin.math.abs
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the closed-form lattice Jacobian against the map it differentiates: inside every cell of a curved lattice, in
 * both blend modes, it agrees with a wide central difference of [latticePointAt], and outside the lattice it
 * declines rather than guessing.
 */
class LatticeJacobianTest {
	/** A curved 3x2 lattice about 100 units across. */
	private val lattice =
		FloatArray(24) { componentIndex ->
			val pointIndex = componentIndex / 2
			val column = pointIndex % 4
			val row = pointIndex / 4
			if (componentIndex % 2 == 0) column * 33f + 6f * sin(row * 1.7f) else row * 50f + 9f * sin(column * 1.1f)
		}

	/** The closed form matches a central difference at points well inside cells, in both modes. */
	@Test
	fun theClosedFormMatchesTheMap() {
		val step = 1e-3f
		val jacobian = FloatArray(4)
		val ahead = FloatArray(2)
		val behind = FloatArray(2)

		for (bilinear in listOf(true, false)) {
			for (sample in 0 until 50) {
				// Fractions chosen to stay a step away from every cell border and the triangle diagonal.
				val u = 0.04f + (sample % 10) * 0.097f
				val v = 0.07f + (sample / 10) * 0.19f
				assertTrue(latticeJacobianAt(lattice, 3, 2, bilinear, u, v, jacobian))

				latticePointAt(lattice, 3, 2, bilinear, u + step, v, ahead, 0)
				latticePointAt(lattice, 3, 2, bilinear, u - step, v, behind, 0)
				val alongU = floatArrayOf((ahead[0] - behind[0]) / (2 * step), (ahead[1] - behind[1]) / (2 * step))
				latticePointAt(lattice, 3, 2, bilinear, u, v + step, ahead, 0)
				latticePointAt(lattice, 3, 2, bilinear, u, v - step, behind, 0)
				val alongV = floatArrayOf((ahead[0] - behind[0]) / (2 * step), (ahead[1] - behind[1]) / (2 * step))

				for ((expected, actual) in listOf(alongU[0] to jacobian[0], alongV[0] to jacobian[1], alongU[1] to jacobian[2], alongV[1] to jacobian[3])) {
					assertTrue(abs(expected - actual) < 0.5f, "bilinear=$bilinear at ($u, $v): $actual against $expected")
				}
			}
		}
	}

	/** Outside the lattice there is no cell to differentiate, so nothing is written. */
	@Test
	fun outsideTheLatticeItDeclines() {
		val jacobian = FloatArray(4)

		assertFalse(latticeJacobianAt(lattice, 3, 2, true, -0.1f, 0.5f, jacobian))
		assertFalse(latticeJacobianAt(lattice, 3, 2, true, 0.5f, 1.2f, jacobian))
		assertTrue(latticeJacobianAt(lattice, 3, 2, true, 1f, 1f, jacobian), "the far corner is inside")
	}
}