package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.areaOf
import org.umamo.geometry.triangulation.triangulate
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Refinement over the shapes that make it hard: a thin rectangle (long boundary edges, every
 * triangle a sliver), an L (a reflex corner), a comb (narrow teeth), and a 10-degree wedge (an input
 * angle no vertex can widen).  Each is checked as a constrained Delaunay triangulation after every
 * added vertex, and at the end for its angles, its limits, and its statistics.
 */
class QualityRefinementTest {
	@Test
	fun aThinRectangleReachesTheTarget() {
		val points = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 3.0, 0.0, 3.0)
		val options = optionsOf(30.0)
		val result = refineValidated(points, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(0, result.statistics.underTargetTriangleCount, "${result.statistics}")
		assertEquals(300.0, areaOf(result.points, result.triangulation.triangles), 1e-3)
	}

	@Test
	fun anLReachesTheTarget() {
		val points = doubleArrayOf(0.0, 0.0, 40.0, 0.0, 40.0, 10.0, 10.0, 10.0, 10.0, 40.0, 0.0, 40.0)
		val options = optionsOf(30.0)
		val result = refineValidated(points, listOf(ringOf(0, 6)), options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(0, result.statistics.underTargetTriangleCount, "${result.statistics}")
	}

	@Test
	fun aCombReachesTheTarget() {
		val points = combOf(teeth = 6, toothWidth = 2.0, gapWidth = 3.0, toothHeight = 20.0, baseHeight = 4.0)
		val options = optionsOf(28.0)
		val result = refineValidated(points, listOf(ringOf(0, points.size / 2)), options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(0, result.statistics.underTargetTriangleCount, "${result.statistics}")
	}

	@Test
	fun aTenDegreeWedgeTerminatesWithOnlyItsApexLeft() {
		val angle = 10.0 * PI / 180.0
		val points = floatRounded(doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0 * cos(angle), 100.0 * sin(angle)))
		val options = optionsOf(25.0, maximumAddedPoints = 4000)
		val result = refineValidated(points, listOf(ringOf(0, 3)), options = options)

		assertRefinedMesh(points, result, options)
		val triangles = result.triangulation.triangles
		val belowTarget = (0 until triangles.size / 3).filter { triangle -> smallestAngleDegrees(result.points, triangles, triangle) < 25.0 }
		assertTrue(belowTarget.isNotEmpty(), "the input angle stays below the target")

		for (triangle in belowTarget) {
			assertTrue((0 until 3).any { corner -> triangles[3 * triangle + corner] == 0 }, "triangle $triangle below the target touches the apex")
		}
	}

	@Test
	fun theCapIsExact() {
		val points = combOf(teeth = 6, toothWidth = 2.0, gapWidth = 3.0, toothHeight = 20.0, baseHeight = 4.0)
		val options = optionsOf(30.0, maximumAddedPoints = 7)
		val result = refineValidated(points, listOf(ringOf(0, points.size / 2)), options = options)

		assertRefinedMesh(points, result, options, mayStopAtCap = true)
		assertEquals(7, result.statistics.addedPointCount)
		assertTrue(result.statistics.stoppedByCap)
	}

	@Test
	fun aZeroCapAddsNothingAndSaysTheTargetWasNotMet() {
		val points = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 3.0, 0.0, 3.0)
		val result = triangulateAndRefine(points, listOf(ringOf(0, 4)), options = optionsOf(30.0, maximumAddedPoints = 0))

		assertEquals(0, result.statistics.addedPointCount)
		assertEquals(2, result.statistics.underTargetTriangleCount)
		assertTrue(result.statistics.stoppedByCap, "no room is a cap reached: ${result.statistics}")
		assertTrue(!triangulateAndRefine(points, listOf(ringOf(0, 4)), options = optionsOf(0.0, maximumAddedPoints = 0)).statistics.stoppedByCap, "no target, nothing missed")
	}

	@Test
	fun aCheckThatRefusesEverySplitLeavesTheDomainAsItWas() {
		val points = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 3.0, 0.0, 3.0)
		val refusedEdges = HashSet<List<Double>>()
		val options =
			optionsOf(30.0, boundarySplitCheck = { startX, startY, endX, endY, _, _, _ ->
				refusedEdges.add(listOf(startX, startY, endX, endY))
				false
			})
		val result = refineValidated(points, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(0, result.statistics.boundarySplitCount)
		assertTrue(result.statistics.refusedBoundarySplitCount > 0, "refinement wanted to split the boundary: ${result.statistics}")
		assertEquals(refusedEdges.size, result.statistics.refusedBoundarySplitCount, "one refusal per boundary edge, however many points it offered: ${result.statistics}")
		assertEquals(300.0, areaOf(result.points, result.triangulation.triangles))
		assertContentEquals(intArrayOf(0, 1, 0, 3, 1, 2, 2, 3), result.triangulation.constrainedEdges, "the boundary is the input's four edges")
	}

	@Test
	fun theCheckSeesEachBoundarySplitAndWhichSideItCuts() {
		val points = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 3.0, 0.0, 3.0)
		val asked = ArrayList<Boolean>()
		val options =
			optionsOf(30.0, boundarySplitCheck = { startX, startY, endX, endY, splitX, splitY, cutsInside ->
				assertTrue(splitX >= minOf(startX, endX) && splitX <= maxOf(startX, endX) && splitY >= minOf(startY, endY) - 1e-3 && splitY <= maxOf(startY, endY) + 1e-3)
				asked.add(cutsInside)
				true
			})
		val result = refineValidated(points, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(result.statistics.boundarySplitCount, asked.size, "every accepted boundary split was asked once")
		assertTrue(asked.none { cutsInside -> cutsInside }, "an axis-aligned boundary always splits exactly on itself")
	}

	@Test
	fun addedPointsAreFloatValuesAwayFromTheAxes() {
		// A rotated rectangle, so split points rarely land exactly on a boundary edge.
		val angle = 0.37
		val corners = doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 4.0, 0.0, 4.0)
		val points = floatRounded(DoubleArray(8) { component -> rotated(corners, component, angle) })
		val options = optionsOf(30.0)
		val result = refineValidated(points, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(points, result, options)
		assertTrue(result.statistics.boundarySplitCount > 0)
		assertEquals(0, result.statistics.underTargetTriangleCount, "${result.statistics}")
		assertEquals(areaOf(points, intArrayOf(0, 1, 2, 0, 2, 3)), areaOf(result.points, result.triangulation.triangles), 1e-3)
	}

	@Test
	fun refinementIsDeterministic() {
		val points = combOf(teeth = 4, toothWidth = 1.5, gapWidth = 2.5, toothHeight = 15.0, baseHeight = 3.0)
		val options = optionsOf(30.0)
		val first = triangulateAndRefine(points, listOf(ringOf(0, points.size / 2)), options = options)
		val second = triangulateAndRefine(points, listOf(ringOf(0, points.size / 2)), options = options)

		assertContentEquals(first.points, second.points)
		assertContentEquals(first.triangulation.triangles, second.triangulation.triangles)
		assertEquals(first.statistics, second.statistics)
	}

	@Test
	fun refinementIsTheSameOnEveryPlatform() {
		// The same bits on the JVM and under Kotlin/Native: every decision is exact or plain IEEE arithmetic.
		val random = Random(4242)
		val directionCount = STAR_DIRECTIONS.size / 2
		val star =
			DoubleArray(2 * directionCount) { component ->
				// Integer directions in counterclockwise order, scaled with sqrt alone: no platform's sine can differ.
				val direction = component / 2
				val length = sqrt((STAR_DIRECTIONS[2 * direction] * STAR_DIRECTIONS[2 * direction] + STAR_DIRECTIONS[2 * direction + 1] * STAR_DIRECTIONS[2 * direction + 1]).toDouble())
				STAR_DIRECTIONS[component] / length
			}

		for (direction in 0 until directionCount) {
			val radius = random.nextDouble(20.0, 40.0)
			star[2 * direction] *= radius
			star[2 * direction + 1] *= radius
		}

		val points = floatRounded(star + DoubleArray(2 * 20) { random.nextDouble(-12.0, 12.0) })
		val result = triangulateAndRefine(points, listOf(ringOf(0, directionCount)), options = optionsOf(28.0))

		assertEquals(PINNED_CHECKSUM, checksumOf(result), "${result.statistics}")
	}

	@Test
	fun offCentersAddFewerPointsThanCircumcentersAcrossDomains() {
		// Per domain the counts wander a little either way; across domains off-centers come out ahead.
		for (targetDegrees in listOf(25.0, 30.0)) {
			var offCenterTotal = 0
			var circumcenterTotal = 0

			for (seed in 1..6) {
				val random = Random(9000 + seed)
				val points = floatRounded(starPolygon(random, 24, 30.0, 60.0) + DoubleArray(2 * 12) { random.nextDouble(-20.0, 20.0) })
				val boundaries = listOf(ringOf(0, 24))
				val options = optionsOf(targetDegrees)
				val offCenters = refineValidated(points, boundaries, options = options)
				val circumcenters = refineValidated(points, boundaries, options = options, steinerPoints = SteinerPointKind.Circumcenter)

				assertRefinedMesh(points, offCenters, options)
				assertRefinedMesh(points, circumcenters, options)
				assertEquals(0, circumcenters.statistics.offCenterCount)
				offCenterTotal += offCenters.statistics.addedPointCount
				circumcenterTotal += circumcenters.statistics.addedPointCount
			}

			assertTrue(offCenterTotal < circumcenterTotal, "at $targetDegrees degrees: off-centers $offCenterTotal vs circumcenters $circumcenterTotal")
		}
	}

	@Test
	fun aZeroTargetIsThePlainTriangulation() {
		val points = combOf(teeth = 4, toothWidth = 1.5, gapWidth = 2.5, toothHeight = 15.0, baseHeight = 3.0)
		val boundaries = listOf(ringOf(0, points.size / 2))
		val result = triangulateAndRefine(points, boundaries, options = optionsOf(0.0))
		val plain = triangulate(points, boundaries)

		assertContentEquals(points, result.points)
		assertContentEquals(plain.triangles, result.triangulation.triangles)
		assertContentEquals(plain.constrainedEdges, result.triangulation.constrainedEdges)
		assertEquals(0, result.statistics.addedPointCount)
	}

	@Test
	fun withoutBoundariesTheHullBoundsTheDomain() {
		// A square hull around a tight cluster of points, which triangulates into slivers.
		val points = floatRounded(doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 100.0, 0.0, 100.0, 50.0, 50.0, 50.5, 50.2, 49.7, 51.0, 50.1, 49.4))
		val options = optionsOf(25.0)
		val result = refineValidated(points, options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(0, result.statistics.underTargetTriangleCount, "${result.statistics}")
		assertEquals(10000.0, areaOf(result.points, result.triangulation.triangles), 1e-6)
	}

	@Test
	fun aClusterTighterThanTheSeparationIsLeftAlone() {
		// Three points 0.02 apart: grading down to them would need vertices closer than the floor allows.
		val points = floatRounded(doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0, 5.0, 5.0, 5.02, 5.0, 5.0, 5.02))
		val options = RefinementOptions(30.0, 2000, minimumSeparation = 0.05, minimumSegmentLength = 0.05)
		val result = refineValidated(points, listOf(ringOf(0, 4)), options = options)

		assertRefinedMesh(points, result, options)
		assertTrue(result.statistics.unfixableTriangleCount > 0, "the floor stopped refinement next to the cluster: ${result.statistics}")
	}

	@Test
	fun interiorSegmentsAreRefinedAroundAndKept() {
		// A square with an inner ring a little inside it, as the mesher's inner ring sits inside its outline.
		val points = doubleArrayOf(0.0, 0.0, 40.0, 0.0, 40.0, 40.0, 0.0, 40.0, 5.0, 5.0, 35.0, 5.0, 35.0, 35.0, 5.0, 35.0)
		val options = optionsOf(30.0)
		val result = refineValidated(points, listOf(ringOf(0, 4)), listOf(intArrayOf(4, 5, 6, 7, 4)), options = options)

		assertRefinedMesh(points, result, options)
		assertEquals(0, result.statistics.underTargetTriangleCount, "${result.statistics}")
		assertEquals(1600.0, areaOf(result.points, result.triangulation.triangles), 1e-6)
	}

	/**
	 * Refinement options with floors small enough to stay out of the way.
	 *
	 * @param Double              targetDegrees      The target.
	 * @param Int                 maximumAddedPoints The cap.
	 * @param BoundarySplitCheck? boundarySplitCheck The check.
	 * @return RefinementOptions The options.
	 */
	private fun optionsOf(targetDegrees: Double, maximumAddedPoints: Int = 2000, boundarySplitCheck: BoundarySplitCheck? = null): RefinementOptions =
		RefinementOptions(targetDegrees, maximumAddedPoints, minimumSeparation = 0.05, minimumSegmentLength = 0.05, boundarySplitCheck = boundarySplitCheck)

	/**
	 * A comb: a base strip with narrow teeth standing on it, as one counterclockwise ring.
	 *
	 * @param Int    teeth       The number of teeth.
	 * @param Double toothWidth  Each tooth's width.
	 * @param Double gapWidth    The gap between teeth.
	 * @param Double toothHeight Each tooth's height above the base.
	 * @param Double baseHeight  The base's height.
	 * @return DoubleArray The ring's points.
	 */
	private fun combOf(teeth: Int, toothWidth: Double, gapWidth: Double, toothHeight: Double, baseHeight: Double): DoubleArray {
		val ring = ArrayList<Double>()
		val width = teeth * toothWidth + (teeth - 1) * gapWidth
		ring.addAll(listOf(0.0, 0.0, width, 0.0))

		// Up the right side, then the teeth from right to left.
		for (tooth in teeth - 1 downTo 0) {
			val left = tooth * (toothWidth + gapWidth)
			val right = left + toothWidth

			if (tooth < teeth - 1) {
				ring.addAll(listOf(right, baseHeight))
			}

			ring.addAll(listOf(right, baseHeight + toothHeight, left, baseHeight + toothHeight))

			if (tooth > 0) {
				ring.addAll(listOf(left, baseHeight))
			}
		}

		return ring.toDoubleArray()
	}

	/**
	 * An FNV-1a hash over a result's point bits and triangle indices.
	 *
	 * @param RefinedTriangulation result The result.
	 * @return Long The hash.
	 */
	private fun checksumOf(result: RefinedTriangulation): Long {
		var hash = -0x340d631b7bdddcdbL

		for (component in result.points) {
			hash = (hash xor component.toRawBits()) * 0x100000001b3L
		}

		for (vertex in result.triangulation.triangles) {
			hash = (hash xor vertex.toLong()) * 0x100000001b3L
		}

		return hash
	}

	/**
	 * One component of a point rotated about the origin.
	 *
	 * @param DoubleArray points    The points.
	 * @param Int         component The component (x at even, y at odd).
	 * @param Double      angle     The rotation in radians.
	 * @return Double The rotated component.
	 */
	private fun rotated(points: DoubleArray, component: Int, angle: Double): Double {
		val point = component / 2
		val x = points[2 * point]
		val y = points[2 * point + 1]

		return if (component % 2 == 0) x * cos(angle) - y * sin(angle) else x * sin(angle) + y * cos(angle)
	}

	private companion object {
		/** The refined star's hash, the same on every platform; a change to refinement's choices changes it on purpose. */
		const val PINNED_CHECKSUM: Long = 5973915513949535883L

		/** Primitive integer directions in counterclockwise order, x, y, ... */
		val STAR_DIRECTIONS = intArrayOf(3, 0, 3, 1, 2, 1, 3, 2, 1, 1, 2, 3, 1, 2, 1, 3, 0, 3, -1, 3, -1, 2, -2, 3, -1, 1, -3, 2, -2, 1, -3, 1, -3, 0, -3, -1, -2, -1, -3, -2, -1, -1, -2, -3, -1, -2, -1, -3, 0, -3, 1, -3, 1, -2, 2, -3, 1, -1, 3, -2, 2, -1, 3, -1)
	}
}