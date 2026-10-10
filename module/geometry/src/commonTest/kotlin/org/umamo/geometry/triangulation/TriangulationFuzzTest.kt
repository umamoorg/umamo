package org.umamo.geometry.triangulation

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test

/**
 * Seeded fuzzing over the point-set shapes that stress an incremental triangulator: snapped to a
 * coarse grid (duplicates, collinear rows, cocircular quads), clustered with outliers (long walks,
 * a wide Hilbert span), on circles and lines, and a mix.  Every insertion is followed by a full
 * structural validation, and every result by the brute-force Delaunay check.
 */
class TriangulationFuzzTest {
	@Test
	fun snappedPointSetsStayValid() {
		for (seed in 1..12) {
			val random = Random(1000 + seed)
			val pointCount = random.nextInt(3, 160)
			val points = DoubleArray(2 * pointCount) { random.nextInt(0, 9).toDouble() }
			assertValidAtEveryStep(points)
		}
	}

	@Test
	fun clusteredPointSetsWithOutliersStayValid() {
		for (seed in 1..12) {
			val random = Random(2000 + seed)
			val pointCount = random.nextInt(3, 160)
			val points = DoubleArray(2 * pointCount)
			for (pointIndex in 0 until pointCount) {
				val isOutlier = random.nextInt(20) == 0
				val spread = if (isOutlier) 1e6 else 1e-3
				points[2 * pointIndex] = random.nextDouble(-spread, spread)
				points[2 * pointIndex + 1] = random.nextDouble(-spread, spread)
			}
			assertValidAtEveryStep(points)
		}
	}

	@Test
	fun pointsOnCirclesAndLinesStayValid() {
		for (seed in 1..12) {
			val random = Random(3000 + seed)
			val pointCount = random.nextInt(3, 160)
			val points = DoubleArray(2 * pointCount)
			for (pointIndex in 0 until pointCount) {
				when (random.nextInt(3)) {
					0 -> {
						val angle = random.nextInt(0, 24) * PI / 12.0
						points[2 * pointIndex] = 10.0 * cos(angle)
						points[2 * pointIndex + 1] = 10.0 * sin(angle)
					}
					1 -> {
						val along = random.nextInt(-20, 21).toDouble()
						points[2 * pointIndex] = along
						points[2 * pointIndex + 1] = 0.5 * along + 3.0
					}
					else -> {
						points[2 * pointIndex] = random.nextDouble(-12.0, 12.0)
						points[2 * pointIndex + 1] = random.nextDouble(-12.0, 12.0)
					}
				}
			}
			assertValidAtEveryStep(points)
		}
	}

	/**
	 * Triangulates with a structural validation after every insertion, then checks the public result
	 * by brute force.
	 *
	 * @param DoubleArray points The points.
	 */
	private fun assertValidAtEveryStep(points: DoubleArray) {
		buildDelaunay(points, planInsertion(points)) { store -> store.validate() }
		assertDelaunayTriangulation(points, triangulate(points))
	}
}