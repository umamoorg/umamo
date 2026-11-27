package org.umamo.geometry.triangulation.refine

import org.umamo.geometry.triangulation.HalfEdgeTriangulation
import org.umamo.geometry.triangulation.assertConstrainedDelaunayTriangulation
import org.umamo.geometry.triangulation.classifyRegions
import kotlin.math.PI
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Refines with a full structural validation and the region oracle after every added vertex.
 *
 * @param DoubleArray       points        The points.
 * @param List<IntArray>    boundaries    The boundary rings.
 * @param List<IntArray>    segments      The segment polylines.
 * @param RefinementOptions options       The target and the limits.
 * @param SteinerPointKind  steinerPoints Which interior points to insert.
 * @return RefinedTriangulation The result.
 */
internal fun refineValidated(
	points: DoubleArray,
	boundaries: List<IntArray> = emptyList(),
	segments: List<IntArray> = emptyList(),
	options: RefinementOptions,
	steinerPoints: SteinerPointKind = SteinerPointKind.OffCenter,
): RefinedTriangulation =
	refineTriangulation(points, boundaries, segments, options, steinerPoints) { store ->
		store.validate()
		assertRegionsCurrent(store)
	}

/**
 * Asserts that the region flags a store maintains equal a classification computed from scratch.
 *
 * @param HalfEdgeTriangulation store The store.
 */
internal fun assertRegionsCurrent(store: HalfEdgeTriangulation) {
	val classification = classifyRegions(store)
	assertTrue(classification.consistent, "the boundary rings stay closed")

	for (triangle in 0 until store.triangleCount) {
		assertEquals(classification.inside[triangle], store.regions[triangle], "triangle $triangle's maintained region")
	}
}

/**
 * Asserts what every refinement result must hold: a constrained Delaunay triangulation over its
 * points, the input points first and unchanged, every added point a Float value, no added point closer
 * than the separation to a vertex it joins, no limit hit unless expected, and every triangle left below
 * the target given up on.
 *
 * @param DoubleArray          input        The input points.
 * @param RefinedTriangulation result       The result.
 * @param RefinementOptions    options      The options it ran with.
 * @param Boolean              mayStopAtCap Whether the cap may stop it.
 */
internal fun assertRefinedMesh(input: DoubleArray, result: RefinedTriangulation, options: RefinementOptions, mayStopAtCap: Boolean = false) {
	val points = result.points
	assertEquals(input.size / 2, result.inputPointCount)

	for (component in input.indices) {
		assertEquals(input[component], points[component], "input component $component is unchanged")
	}

	for (component in input.size until points.size) {
		assertEquals(points[component].toFloat().toDouble(), points[component], "added component $component is a Float value")
	}

	assertConstrainedDelaunayTriangulation(points, result.triangulation, coversHull = false)
	val triangles = result.triangulation.triangles

	for (entry in triangles.indices) {
		val vertex = triangles[entry]
		val next = triangles[3 * (entry / 3) + (entry + 1) % 3]

		if (vertex >= result.inputPointCount || next >= result.inputPointCount) {
			val length = sqrt(squaredDistance(points[2 * vertex], points[2 * vertex + 1], points[2 * next], points[2 * next + 1]))
			assertTrue(length >= options.minimumSeparation * (1.0 - 1e-9), "edge $vertex - $next with an added end is $length, under the separation ${options.minimumSeparation}")
		}
	}

	val statistics = result.statistics
	assertFalse(statistics.stoppedByBackstop, "the backstop never fires")
	assertEquals(result.inputPointCount + statistics.addedPointCount, points.size / 2, "every added point is counted")

	if (!mayStopAtCap) {
		assertFalse(statistics.stoppedByCap, "the cap was not reached: $statistics")
	}

	if (!statistics.stoppedByCap) {
		assertEquals(statistics.underTargetTriangleCount, statistics.unfixableTriangleCount, "every triangle left below the target was given up on: $statistics")
		assertEquals(statistics.underTargetTriangleCount, countBelow(points, triangles, options.minimumAngleDegrees), "the reported count matches the mesh")
	}
}

/**
 * Counts the triangles whose smallest angle, measured with acos, is below a target.
 *
 * @param DoubleArray points        The points.
 * @param IntArray    triangles     Three indices per triangle.
 * @param Double      targetDegrees The target.
 * @return Int The count.
 */
internal fun countBelow(points: DoubleArray, triangles: IntArray, targetDegrees: Double): Int = (0 until triangles.size / 3).count { triangle -> smallestAngleDegrees(points, triangles, triangle) < targetDegrees }

/**
 * A triangle's smallest angle in degrees, measured with acos.
 *
 * @param DoubleArray points    The points.
 * @param IntArray    triangles Three indices per triangle.
 * @param Int         triangle  The triangle.
 * @return Double The smallest angle.
 */
internal fun smallestAngleDegrees(points: DoubleArray, triangles: IntArray, triangle: Int): Double {
	var smallest = 180.0

	for (corner in 0 until 3) {
		val vertex = triangles[3 * triangle + corner]
		val next = triangles[3 * triangle + (corner + 1) % 3]
		val other = triangles[3 * triangle + (corner + 2) % 3]
		val toNextX = points[2 * next] - points[2 * vertex]
		val toNextY = points[2 * next + 1] - points[2 * vertex + 1]
		val toOtherX = points[2 * other] - points[2 * vertex]
		val toOtherY = points[2 * other + 1] - points[2 * vertex + 1]
		val cosine = (toNextX * toOtherX + toNextY * toOtherY) / (sqrt(toNextX * toNextX + toNextY * toNextY) * sqrt(toOtherX * toOtherX + toOtherY * toOtherY))
		smallest = minOf(smallest, acos(cosine.coerceIn(-1.0, 1.0)) * 180.0 / PI)
	}

	return smallest
}

/**
 * A star-shaped polygon around the origin: angles in order with jitter, radii in a band.
 *
 * @param Random random        The source.
 * @param Int    vertexCount   The number of vertices.
 * @param Double minimumRadius The smallest vertex radius.
 * @param Double maximumRadius The largest vertex radius.
 * @return DoubleArray The polygon's vertices, counterclockwise.
 */
internal fun starPolygon(random: Random, vertexCount: Int, minimumRadius: Double, maximumRadius: Double): DoubleArray {
	val polygon = DoubleArray(2 * vertexCount)

	for (vertex in 0 until vertexCount) {
		val angle = 2.0 * PI * (vertex + random.nextDouble(-0.3, 0.3)) / vertexCount
		val radius = random.nextDouble(minimumRadius, maximumRadius)
		polygon[2 * vertex] = radius * cos(angle)
		polygon[2 * vertex + 1] = radius * sin(angle)
	}

	return polygon
}

/**
 * A closed ring over consecutive point indices.
 *
 * @param Int first The first index.
 * @param Int count The number of points.
 * @return IntArray The ring.
 */
internal fun ringOf(first: Int, count: Int): IntArray = IntArray(count) { position -> first + position }

/**
 * Rounds every component to a Float value, as the mesher's points are.
 *
 * @param DoubleArray points The points.
 * @return DoubleArray The rounded points.
 */
internal fun floatRounded(points: DoubleArray): DoubleArray = DoubleArray(points.size) { component -> points[component].toFloat().toDouble() }