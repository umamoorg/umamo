package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.locate.TriangleLocator
import org.umamo.geometry.triangulation.triangulate
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Greedy insertion of a guide: a guide whose fields are affine adds nothing, a fold is followed until
 * nothing strays past the tolerance, every inserted vertex is a guide vertex's Float point bit for bit,
 * errors read in the plane's units through each field's scale, angle refinement runs first and stops
 * short of the guide's share - all of the cap is refinement's when the guide has nothing to hold - what
 * the guide holds stands, nothing inside the guide strays past the
 * tolerance (measured over the two surfaces, not the guide's candidates), a guide vertex on the
 * boundary splits it where the check allows, a crease running out past the boundary splits it where it
 * crosses, a crease with both ends outside the domain is held across it, a crease grazing a boundary
 * corner stays held, a segment that bounds nothing gives way where a crease cannot split it, and two
 * creases that cross where neither can split keep the first held rather than trading places forever.
 */
class GuideInsertionTest {
	/** The domain: a rectangle around the guide, so every guide vertex lies inside it. */
	private val domain = doubleArrayOf(-10.0, -10.0, 110.0, -10.0, 110.0, 30.0, -10.0, 30.0)

	@Test
	fun anAffineGuideAddsNothing() {
		val grid = gridGuidePoints()
		val field = FloatArray(grid.size) { component -> if (component % 2 == 0) 2f * grid[component] + grid[component + 1] else grid[component - 1] - 0.5f * grid[component] }
		val guide = guideOf(grid, listOf(field))
		val result = refineValidated(domain, listOf(ringOf(0, 4)), options = guideOptions(guide, tolerance = 0.01))

		assertEquals(0, result.statistics.guidePointCount)
		assertTrue(result.statistics.guideRemainingError < 1e-9, "${result.statistics}")
	}

	@Test
	fun aFoldIsFollowedUntilItIsWithinTheTolerance() {
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		val options = guideOptions(guide, tolerance = 0.5)
		val result = refineValidated(domain, listOf(ringOf(0, 4)), options = options)
		val statistics = result.statistics

		assertRefinedMesh(domain, result, options)
		assertTrue(statistics.guidePointCount > 0)
		assertFalse(statistics.guideStoppedByCap)
		assertTrue(statistics.guideRemainingError <= 0.5, "${statistics.guideRemainingError}")
		val added = addedPointsOf(result)

		for (y in listOf(0f, 10f, 20f)) {
			assertTrue(50f to y in added, "the fold's crest at (50, $y) is a vertex")
		}
	}

	@Test
	fun everyInsertedVertexIsAGuideVertexBitForBit() {
		// A rotated, uneven guide, so its points are not round numbers, inside a domain that holds it all.
		val angle = 0.3
		val grid = gridGuidePoints().let { points -> FloatArray(points.size) { component -> rotated(points, component, angle) * 0.9137f + 3.141f } }
		val guide = guideOf(grid, listOf(foldField(gridGuidePoints(), amplitude = 6f)))
		val roomy = doubleArrayOf(-10.0, -10.0, 110.0, -10.0, 110.0, 60.0, -10.0, 60.0)
		val result = refineValidated(roomy, listOf(ringOf(0, 4)), options = guideOptions(guide, tolerance = 0.3))
		val guidePoints = (0 until grid.size / 2).map { vertex -> grid[2 * vertex] to grid[2 * vertex + 1] }.toSet()

		assertTrue(result.statistics.guidePointCount > 0)

		for (point in addedPointsOf(result)) {
			assertTrue(point in guidePoints, "added point $point is a guide vertex")
		}
	}

	@Test
	fun errorsReadInThePlanesUnitsThroughEachFieldsScale() {
		val grid = gridGuidePoints()
		// A fold of 0.8 units under a tolerance of 1: nothing to insert - and the same field three times
		// larger, with its scale saying so, reads the same.
		val fold = foldField(grid, amplitude = 0.8f)
		val tripled = FloatArray(fold.size) { component -> 3f * fold[component] }
		val triangles = triangulate(grid.toDoubleArray()).triangles
		val scale = areaScaleOf(grid, triangles, tripled)
		assertEquals(3.0, scale, 1e-6)

		val plain = refineValidated(domain, listOf(ringOf(0, 4)), options = guideOptions(guideOf(grid, listOf(fold)), tolerance = 1.0))
		val scaled = refineValidated(domain, listOf(ringOf(0, 4)), options = guideOptions(PiecewiseLinearGuide(grid, triangles, listOf(tripled), doubleArrayOf(scale), candidatesOf(grid)), tolerance = 1.0))

		assertEquals(0, plain.statistics.guidePointCount)
		assertEquals(0, scaled.statistics.guidePointCount, "${scaled.statistics}")
	}

	@Test
	fun angleRefinementStopsShortOfTheGuidesShare() {
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		val options = RefinementOptions(33.0, 4, 0.01, 0.01, guide = guide, guideTolerance = 0.01, guidePointShare = 0.5)
		val result = refineValidated(domain, listOf(ringOf(0, 4)), options = options)
		val statistics = result.statistics

		assertTrue(statistics.addedPointCount - statistics.guidePointCount <= 2, "angle refinement kept to its half: $statistics")
		assertEquals(4, statistics.addedPointCount, "the guide took what was left: $statistics")
		assertTrue(statistics.guideStoppedByCap, "$statistics")
		assertTrue(statistics.guideRemainingError > 0.01, "$statistics")
	}

	@Test
	fun aGuideWithNothingToHoldLeavesRefinementTheWholeCap() {
		// An affine guide never strays, so it claims no share: refinement of a thin strip, which wants
		// more than the quarter of the cap a share would leave it, comes out exactly as without a guide.
		val grid = gridGuidePoints()
		val field = FloatArray(grid.size) { component -> if (component % 2 == 0) 2f * grid[component] + grid[component + 1] else grid[component - 1] - 0.5f * grid[component] }
		val strip = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 8.0, 0.0, 8.0)
		val guided = RefinementOptions(30.0, 24, 0.01, 0.01, guide = guideOf(grid, listOf(field)), guideTolerance = 0.01)
		val plain = RefinementOptions(30.0, 24, 0.01, 0.01)
		val withGuide = refineValidated(strip, listOf(ringOf(0, 4)), options = guided)
		val without = refineValidated(strip, listOf(ringOf(0, 4)), options = plain)

		assertTrue(without.statistics.addedPointCount > 6, "refinement wants more than a share would leave it: ${without.statistics}")
		assertEquals(0, withGuide.statistics.guidePointCount)
		assertEquals(without.statistics.addedPointCount, withGuide.statistics.addedPointCount, "${withGuide.statistics}")
		assertContentEquals(without.points, withGuide.points)
		assertContentEquals(without.triangulation.triangles, withGuide.triangulation.triangles)
	}

	@Test
	fun aGuideVertexOnTheBoundarySplitsItWhereTheCheckAllows() {
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		// The domain's bottom edge runs along the guide's bottom row, and its right side cuts the guide.
		val tight = doubleArrayOf(0.0, 0.0, 70.0, 0.0, 70.0, 30.0, 0.0, 30.0)
		val allowed = guideOptions(guide, tolerance = 0.5)
		val refused = RefinementOptions(0.0, 2000, 0.01, 0.01, boundarySplitCheck = { _, _, _, _, _, _, _ -> false }, guide = guide, guideTolerance = 0.5)
		val split = refineValidated(tight, listOf(ringOf(0, 4)), options = allowed)
		val kept = refineValidated(tight, listOf(ringOf(0, 4)), options = refused)

		assertRefinedMesh(tight, split, allowed)
		assertRefinedMesh(tight, kept, refused)
		assertTrue(50f to 0f in addedPointsOf(split), "the fold's crest on the boundary splits it")
		assertTrue(split.statistics.guideRemainingError <= 0.5, "${split.statistics}")

		for ((x, y) in addedPointsOf(kept)) {
			assertTrue(y > 0f && x < 70f, "($x, $y) is strictly inside the domain")
		}
	}

	@Test
	fun aCreaseRunningPastTheBoundarySplitsItWhereItCrosses() {
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		// The domain's top edge cuts the guide between its last two rows, so the top row lies outside.
		val cut = doubleArrayOf(-10.0, -10.0, 110.0, -10.0, 110.0, 15.0, -10.0, 15.0)
		val options = guideOptions(guide, tolerance = 0.5)
		val result = refineValidated(cut, listOf(ringOf(0, 4)), options = options)
		val added = addedPointsOf(result)

		assertRefinedMesh(cut, result, options)
		assertTrue(result.statistics.guideRemainingError <= 0.5, "${result.statistics}")

		for (x in listOf(40f, 50f, 60f)) {
			assertTrue(x to 15f in added, "the fold's crease at x = $x splits the top edge")
		}

		for ((x, y) in added) {
			assertTrue(y < 15f || x in listOf(40f, 50f, 60f), "($x, $y) is inside the domain or where a crease crosses its top")
		}
	}

	@Test
	fun nothingInsideTheGuideStraysPastTheTolerance() {
		// Every guide vertex lifted by its own amount, so creases run every way between them, and the
		// domain's top cutting the guide between its last two rows.
		val grid = gridGuidePoints()
		var seed = 12345L
		val lifted =
			FloatArray(grid.size) { component ->
				seed = (seed * 6364136223846793005L + 1442695040888963407L)
				grid[component] + 6f * ((seed ushr 40).toFloat() / (1L shl 24).toFloat() - 0.5f)
			}
		val guide = guideOf(grid, listOf(lifted))
		val cut = doubleArrayOf(-10.0, -10.0, 110.0, -10.0, 110.0, 15.0, -10.0, 15.0)
		val options = guideOptions(guide, tolerance = 0.25)
		val result = refineValidated(cut, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(cut, result, options)
		assertFalse(result.statistics.guideStoppedByCap)
		// Checked against the two surfaces directly, not through the guide's own candidates: the mesh's
		// interpolation of the guide's values at its vertices, against the guide, over a fine grid.
		val worst = largestDeviation(guide, result, 0.0..100.0, 0.0..15.0, 0.125)
		assertTrue(worst <= 0.25 + 1e-6, "$worst")
	}

	@Test
	fun aCreaseWithBothEndsOutsideTheDomainIsHeldAcrossIt() {
		// A band between the guide's first two rows: no guide vertex lies in it, and every crease crosses it.
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		val band = doubleArrayOf(-10.0, 2.0, 110.0, 2.0, 110.0, 8.0, -10.0, 8.0)
		val options = guideOptions(guide, tolerance = 0.25)
		val result = refineValidated(band, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(band, result, options)
		assertTrue(result.statistics.guidePointCount > 0)
		assertTrue(largestDeviation(guide, result, 0.0..100.0, 2.0..8.0, 0.125) <= 0.25 + 1e-6)
	}

	@Test
	fun aSegmentHuggingACreasesEndGivesWayToIt() {
		// A neutral segment a hair above the guide's middle row: the fold's crest vertex at (50, 10) goes in
		// beside it, too close for the crest's upper edge to split the segment where it crosses.
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		val hairAbove = 10.0 + 1.0 / 256.0
		val withSegment = domain + doubleArrayOf(20.0, hairAbove, 80.0, hairAbove)
		val options = guideOptions(guide, tolerance = 0.25)
		val result = refineValidated(withSegment, listOf(ringOf(0, 4)), listOf(intArrayOf(4, 5)), options = options)

		assertRefinedMesh(withSegment, result, options)
		assertTrue(largestDeviation(guide, result, 0.0..100.0, 0.0..20.0, 0.125) <= 0.25 + 1e-6)
	}

	@Test
	fun aCreaseGrazingABoundaryCornerStaysHeld() {
		// A thin notch into the domain from the right, its tip a hair past the fold's crest line: the crest's
		// upper edge leaves and re-enters the domain right beside the tip, too close for either split.
		val grid = gridGuidePoints()
		val guide = guideOf(grid, listOf(foldField(grid, amplitude = 10f)))
		val tipX = 50.0 - 1.0 / 256.0
		val notched = doubleArrayOf(-10.0, -10.0, 110.0, -10.0, 110.0, 14.9, tipX, 15.0, 110.0, 15.1, 110.0, 30.0, -10.0, 30.0)
		val options = guideOptions(guide, tolerance = 0.25)
		val result = refineValidated(notched, listOf(ringOf(0, 7)), options = options)

		assertRefinedMesh(notched, result, options)
		val below = largestDeviation(guide, result, 0.0..100.0, 0.0..14.875, 0.125)
		val above = largestDeviation(guide, result, 0.0..100.0, 15.125..20.0, 0.125)
		assertTrue(maxOf(below, above) <= 0.25 + 1e-6, "$below, $above")
	}

	@Test
	fun creasesThatCrossWhereNeitherCanSplitKeepTheFirstHeld() {
		// A folded guide, two triangles laid over each other, so the crease along y = 10 crosses the one
		// along x = 50; a domain vertex a hair from the crossing leaves no room to split either there.
		val points = floatArrayOf(10f, 10f, 90f, 10f, 50f, 28f, 50f, -8f)
		val triangles = intArrayOf(0, 1, 2, 3, 1, 2)
		val field = floatArrayOf(10f, 10f, 90f, 10f, 50f, 28f, 50f, -2f)
		val guide = PiecewiseLinearGuide(points, triangles, listOf(field), doubleArrayOf(areaScaleOf(points, triangles, field)), candidatesOf(points))
		val withNearVertex = domain + doubleArrayOf(50.0, 10.05)
		val options = RefinementOptions(0.0, 2000, minimumSeparation = 0.5, minimumSegmentLength = 0.01, guide = guide, guideTolerance = 0.01)
		val result = refineValidated(withNearVertex, listOf(ringOf(0, 4)), options = options)

		assertFalse(result.statistics.stoppedByBackstop, "${result.statistics}")
		val constrained = (0 until result.triangulation.constrainedEdges.size / 2).map { edge -> setOf(result.triangulation.constrainedEdges[2 * edge], result.triangulation.constrainedEdges[2 * edge + 1]) }
		val vertexAt = { x: Float, y: Float -> (0 until result.points.size / 2).first { vertex -> result.points[2 * vertex] == x.toDouble() && result.points[2 * vertex + 1] == y.toDouble() } }
		val acrossHeld = setOf(vertexAt(10f, 10f), vertexAt(90f, 10f)) in constrained
		val upHeld = setOf(vertexAt(50f, -8f), vertexAt(50f, 28f)) in constrained
		assertTrue(acrossHeld != upHeld, "exactly one crease is held, the other goes without: $constrained")
	}

	@Test
	fun aScaledOrRotatedCopyReadsItsScale() {
		val grid = gridGuidePoints()
		val triangles = triangulate(grid.toDoubleArray()).triangles
		val rotatedCopy = FloatArray(grid.size) { component -> rotated(grid, component, 1.1) + 40f }
		val halved = FloatArray(grid.size) { component -> grid[component] / 2f }

		assertEquals(1.0, areaScaleOf(grid, triangles, rotatedCopy), 1e-5)
		assertEquals(0.5, areaScaleOf(grid, triangles, halved), 1e-9)
		assertEquals(0.0, areaScaleOf(grid, triangles, FloatArray(grid.size)))
	}

	/**
	 * A grid of guide points over (0, 0) - (100, 20), ten apart.
	 *
	 * @return FloatArray The points.
	 */
	private fun gridGuidePoints(): FloatArray {
		val points = ArrayList<Float>()

		for (row in 0..2) {
			for (column in 0..10) {
				points.add(10f * column)
				points.add(10f * row)
			}
		}

		return points.toFloatArray()
	}

	/**
	 * A field that is the plane with a tent along x: up by [amplitude] at x = 50, back to nothing by x = 40
	 * and x = 60 - linear between grid columns, so the grid guide holds it exactly.
	 *
	 * @param FloatArray points    The grid points the tent is read at.
	 * @param Float      amplitude The tent's height.
	 * @return FloatArray The field.
	 */
	private fun foldField(points: FloatArray, amplitude: Float): FloatArray =
		FloatArray(points.size) { component ->
			if (component % 2 == 0) {
				points[component]
			} else {
				points[component] + amplitude * maxOf(0f, 1f - abs(points[component - 1] - 50f) / 10f)
			}
		}

	/**
	 * A guide over the grid's own triangulation, every field at scale 1, every vertex a candidate.
	 *
	 * @param FloatArray       points The guide's points.
	 * @param List<FloatArray> fields The fields.
	 * @return PiecewiseLinearGuide The guide.
	 */
	private fun guideOf(points: FloatArray, fields: List<FloatArray>): PiecewiseLinearGuide =
		PiecewiseLinearGuide(points, triangulate(points.toDoubleArray()).triangles, fields, DoubleArray(fields.size) { 1.0 }, candidatesOf(points))

	/**
	 * Every guide vertex.
	 *
	 * @param FloatArray points The guide's points.
	 * @return IntArray The candidates.
	 */
	private fun candidatesOf(points: FloatArray): IntArray = IntArray(points.size / 2) { vertex -> vertex }

	/**
	 * Options that only follow a guide: no angle target, a generous cap, small floors.
	 *
	 * @param PiecewiseLinearGuide guide     The guide.
	 * @param Double               tolerance The tolerance.
	 * @return RefinementOptions The options.
	 */
	private fun guideOptions(guide: PiecewiseLinearGuide, tolerance: Double): RefinementOptions = RefinementOptions(0.0, 2000, 0.01, 0.01, guide = guide, guideTolerance = tolerance)

	/**
	 * The largest distance, over a grid of sample points, between a guide and a refined mesh's
	 * interpolation of the guide's values at the mesh's vertices.
	 *
	 * @param PiecewiseLinearGuide                guide  The guide.
	 * @param RefinedTriangulation                result The refined mesh.
	 * @param ClosedFloatingPointRange<Double>    xRange The sampled x range.
	 * @param ClosedFloatingPointRange<Double>    yRange The sampled y range.
	 * @param Double                              step   The spacing between samples.
	 * @return Double The largest distance, over the first field.
	 */
	private fun largestDeviation(guide: PiecewiseLinearGuide, result: RefinedTriangulation, xRange: ClosedFloatingPointRange<Double>, yRange: ClosedFloatingPointRange<Double>, step: Double): Double {
		val meshPoints = FloatArray(result.points.size) { component -> result.points[component].toFloat() }
		val triangles = result.triangulation.triangles
		val locator = TriangleLocator(meshPoints, triangles)
		val vertexValues = DoubleArray(2 * guide.fieldCount)
		val meshValues = Array(result.points.size / 2) { vertex -> DoubleArray(2 * guide.fieldCount).also { into -> guide.valuesAt(result.points[2 * vertex], result.points[2 * vertex + 1], into) } }
		var worst = 0.0
		var x = xRange.start

		while (x <= xRange.endInclusive) {
			var y = yRange.start

			while (y <= yRange.endInclusive) {
				val location = assertNotNull(locator.locate(x, y))
				assertTrue(location.isInside, "($x, $y) lies on the mesh")
				guide.valuesAt(x, y, vertexValues)
				val first = meshValues[triangles[3 * location.triangleIndex]]
				val second = meshValues[triangles[3 * location.triangleIndex + 1]]
				val third = meshValues[triangles[3 * location.triangleIndex + 2]]
				val interpolatedX = location.weightA * first[0] + location.weightB * second[0] + location.weightC * third[0]
				val interpolatedY = location.weightA * first[1] + location.weightB * second[1] + location.weightC * third[1]
				worst = maxOf(worst, hypot(vertexValues[0] - interpolatedX, vertexValues[1] - interpolatedY))
				y += step
			}

			x += step
		}

		return worst
	}

	/**
	 * The points a refinement added, as Float pairs.
	 *
	 * @param RefinedTriangulation result The result.
	 * @return Set<Pair<Float, Float>> The added points.
	 */
	private fun addedPointsOf(result: RefinedTriangulation): Set<Pair<Float, Float>> =
		(result.inputPointCount until result.points.size / 2).map { vertex -> result.points[2 * vertex].toFloat() to result.points[2 * vertex + 1].toFloat() }.toSet()

	/**
	 * One component of a point rotated about the origin.
	 *
	 * @param FloatArray points    The points.
	 * @param Int        component The component (x at even, y at odd).
	 * @param Double     angle     The rotation in radians.
	 * @return Float The rotated component.
	 */
	private fun rotated(points: FloatArray, component: Int, angle: Double): Float {
		val point = component / 2
		val x = points[2 * point].toDouble()
		val y = points[2 * point + 1].toDouble()

		return (if (component % 2 == 0) x * cos(angle) - y * sin(angle) else x * sin(angle) + y * cos(angle)).toFloat()
	}

	/**
	 * Widens Float points to Doubles.
	 *
	 * @return DoubleArray The points.
	 */
	private fun FloatArray.toDoubleArray(): DoubleArray = DoubleArray(size) { component -> this[component].toDouble() }
}