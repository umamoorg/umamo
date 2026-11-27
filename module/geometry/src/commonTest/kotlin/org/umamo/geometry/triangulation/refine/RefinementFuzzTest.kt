package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.areaOf
import org.umamo.geometry.triangulation.ringArea
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Seeded fuzzing of refinement: star polygons with star holes and scattered points, point sets with
 * no boundary, a check that refuses every inward cut, and points snapped to a coarse grid (collinear
 * boundary runs, cocircular quads).  Every added vertex is followed by a structural validation and the
 * region oracle, and every result by the brute-force constrained Delaunay check and the refinement
 * invariants.
 */
class RefinementFuzzTest {
	@Test
	fun starPolygonsWithHolesRefineToTheTarget() {
		for (seed in 1..10) {
			val random = Random(7000 + seed)
			val outerCount = random.nextInt(12, 40)
			val holeCount = random.nextInt(6, 20)
			val outer = starPolygon(random, outerCount, 20.0, 40.0)
			val hole = starPolygon(random, holeCount, 3.0, 8.0)
			val scattered = DoubleArray(2 * random.nextInt(0, 30)) { random.nextDouble(-25.0, 25.0) }
			val points = floatRounded(outer + hole + scattered)
			val boundaries = listOf(ringOf(0, outerCount), ringOf(outerCount, holeCount))
			val targetDegrees = listOf(20.0, 25.0, 30.0)[seed % 3]
			val options = RefinementOptions(targetDegrees, 3000, 0.01, 0.01)
			val result = refineValidated(points, boundaries, options = options)

			assertRefinedMesh(points, result, options)
			val expected = abs(ringArea(points, 0, outerCount)) - abs(ringArea(points, outerCount, holeCount))
			assertEquals(expected, areaOf(result.points, result.triangulation.triangles), 1e-3 * expected, "seed $seed keeps its area")
		}
	}

	@Test
	fun pointSetsWithoutBoundariesRefineOverTheirHull() {
		for (seed in 1..8) {
			val random = Random(7100 + seed)
			val points = floatRounded(DoubleArray(2 * random.nextInt(4, 60)) { random.nextDouble(-30.0, 30.0) })
			val options = RefinementOptions(25.0, 3000, 0.01, 0.01)
			val result = refineValidated(points, options = options)

			assertRefinedMesh(points, result, options)
		}
	}

	@Test
	fun refusingEveryInwardCutOnlyEverGrowsTheDomain() {
		for (seed in 1..6) {
			val random = Random(7200 + seed)
			val outerCount = random.nextInt(12, 30)
			val points = floatRounded(starPolygon(random, outerCount, 20.0, 40.0))
			val options = RefinementOptions(28.0, 3000, 0.01, 0.01, boundarySplitCheck = { _, _, _, _, _, _, cutsInside -> !cutsInside })
			val result = refineValidated(points, listOf(ringOf(0, outerCount)), options = options)

			assertRefinedMesh(points, result, options)
			val before = abs(ringArea(points, 0, outerCount))
			assertTrue(areaOf(result.points, result.triangulation.triangles) >= before - 1e-9 * before, "seed $seed: no sliver was cut out")
		}
	}

	@Test
	fun gridSnappedPointSetsRefine() {
		for (seed in 1..8) {
			val random = Random(7300 + seed)
			// The square's border at every grid step, so the boundary runs straight through collinear vertices.
			val border = ArrayList<Double>()

			for (step in 0 until 8) {
				border.addAll(listOf(step.toDouble(), 0.0))
			}

			for (step in 0 until 8) {
				border.addAll(listOf(8.0, step.toDouble()))
			}

			for (step in 0 until 8) {
				border.addAll(listOf(8.0 - step, 8.0))
			}

			for (step in 0 until 8) {
				border.addAll(listOf(0.0, 8.0 - step))
			}

			val inner = DoubleArray(2 * random.nextInt(0, 20)) { random.nextInt(1, 8).toDouble() }
			val points = border.toDoubleArray() + inner
			val options = RefinementOptions(listOf(20.0, 28.0, 33.0)[seed % 3], 3000, 0.01, 0.01)
			val result = refineValidated(points, listOf(ringOf(0, 32)), options = options)

			assertRefinedMesh(points, result, options)
			assertEquals(64.0, areaOf(result.points, result.triangulation.triangles), 1e-6)
		}
	}
}