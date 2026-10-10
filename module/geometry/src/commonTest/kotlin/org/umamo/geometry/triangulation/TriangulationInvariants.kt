package org.umamo.geometry.triangulation

import org.umamo.geometry.predicate.incircle
import org.umamo.geometry.predicate.orient2d
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Asserts that a result is the Delaunay triangulation of its points' convex hull, by brute force:
 * every triangle is positively oriented and uses canonical indices only, each edge has at most one
 * triangle per side, no point sits on an edge's open segment, every distinct point is used, the
 * boundary is convex and holds every point on its inner side, Euler's count T = 2n - 2 - h holds (h
 * counting collinear hull vertices), and - unless skipped - no point lies strictly inside any
 * triangle's circumcircle.
 *
 * @param DoubleArray   points            The triangulated points.
 * @param Triangulation result            The result under test.
 * @param Boolean       checkEmptyCircles Whether to run the O(T * n) circumcircle check.
 */
internal fun assertDelaunayTriangulation(points: DoubleArray, result: Triangulation, checkEmptyCircles: Boolean = true) {
	val pointCount = points.size / 2
	val canonical = result.canonicalIndex
	assertEquals(pointCount, canonical.size, "canonicalIndex has one entry per point")
	val distinct = (0 until pointCount).filter { canonical[it] == it }
	val triangles = result.triangles
	assertEquals(0, triangles.size % 3, "triangles come in triples")
	if (triangles.isEmpty()) {
		assertTrue(distinct.size < 3 || allCollinear(points, distinct), "an empty result needs fewer than three distinct points or all of them collinear")
		return
	}

	val directedEdges = HashSet<Long>()
	for (triangleIndex in 0 until result.triangleCount) {
		val first = triangles[3 * triangleIndex]
		val second = triangles[3 * triangleIndex + 1]
		val third = triangles[3 * triangleIndex + 2]
		for (vertex in intArrayOf(first, second, third)) {
			assertTrue(vertex in 0 until pointCount && canonical[vertex] == vertex, "triangle $triangleIndex uses non-canonical vertex $vertex")
		}
		assertTrue(orientOf(points, first, second, third) > 0.0, "triangle $triangleIndex ($first, $second, $third) is not positively oriented")
		for ((start, end) in listOf(first to second, second to third, third to first)) {
			assertTrue(directedEdges.add(edgeKey(start, end)), "directed edge $start->$end appears twice")
		}
	}

	val used = HashSet<Int>()
	for (vertex in triangles) {
		used.add(vertex)
	}
	assertEquals(distinct.toSet(), used, "every distinct point is a vertex")

	var boundaryEdges = 0
	for (key in directedEdges) {
		val start = (key ushr 32).toInt()
		val end = key.toInt()
		val isBoundary = edgeKey(end, start) !in directedEdges
		for (other in distinct) {
			if (other == start || other == end) {
				continue
			}
			val side = orientOf(points, start, end, other)
			if (side == 0.0 && strictlyBetween(points, start, end, other)) {
				fail("point $other lies on the open segment of edge $start->$end")
			}
			if (isBoundary) {
				assertTrue(side >= 0.0, "point $other lies outside boundary edge $start->$end")
			}
		}
		if (isBoundary) {
			boundaryEdges++
		}
	}
	assertEquals(2 * distinct.size - 2 - boundaryEdges, result.triangleCount, "Euler's count T = 2n - 2 - h")

	if (checkEmptyCircles) {
		for (triangleIndex in 0 until result.triangleCount) {
			val first = triangles[3 * triangleIndex]
			val second = triangles[3 * triangleIndex + 1]
			val third = triangles[3 * triangleIndex + 2]
			for (other in distinct) {
				if (other == first || other == second || other == third) {
					continue
				}
				val inside =
					incircle(
						points[2 * first],
						points[2 * first + 1],
						points[2 * second],
						points[2 * second + 1],
						points[2 * third],
						points[2 * third + 1],
						points[2 * other],
						points[2 * other + 1],
					)
				assertTrue(inside <= 0.0, "point $other is inside the circumcircle of triangle $triangleIndex")
			}
		}
	}
}

/**
 * The triangles as a set of rotation-normalized triples (smallest index first), so two results can
 * be compared regardless of slot order or starting corner.
 *
 * @param IntArray triangles Three indices per triangle.
 * @return Set The normalized triples.
 */
internal fun normalizedTriangleSet(triangles: IntArray): Set<List<Int>> {
	val normalized = HashSet<List<Int>>()
	for (triangleIndex in 0 until triangles.size / 3) {
		val corners = listOf(triangles[3 * triangleIndex], triangles[3 * triangleIndex + 1], triangles[3 * triangleIndex + 2])
		val lowest = corners.indexOf(corners.min())
		normalized.add(List(3) { corners[(lowest + it) % 3] })
	}
	return normalized
}

/**
 * orient2d over three indexed points.
 *
 * @param DoubleArray points The points.
 * @param Int         first  The first index.
 * @param Int         second The second index.
 * @param Int         third  The third index.
 * @return Double The predicate's value.
 */
internal fun orientOf(points: DoubleArray, first: Int, second: Int, third: Int): Double =
	orient2d(
		points[2 * first],
		points[2 * first + 1],
		points[2 * second],
		points[2 * second + 1],
		points[2 * third],
		points[2 * third + 1],
	)

/**
 * Packs a directed edge into one Long.
 *
 * @param Int start The start vertex.
 * @param Int end   The end vertex.
 * @return Long The key.
 */
private fun edgeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

/**
 * Whether a point collinear with a segment lies strictly between its ends.
 *
 * @param DoubleArray points The points.
 * @param Int         start  The segment's start.
 * @param Int         end    The segment's end.
 * @param Int         query  The collinear point.
 * @return Boolean True when the query is inside the open segment.
 */
private fun strictlyBetween(points: DoubleArray, start: Int, end: Int, query: Int): Boolean {
	val startX = points[2 * start]
	val startY = points[2 * start + 1]
	val endX = points[2 * end]
	val endY = points[2 * end + 1]
	val queryX = points[2 * query]
	val queryY = points[2 * query + 1]
	// Collinear, so comparing along the dominant axis decides it exactly.
	return if (startX != endX) {
		(queryX > minOf(startX, endX)) && (queryX < maxOf(startX, endX))
	} else {
		(queryY > minOf(startY, endY)) && (queryY < maxOf(startY, endY))
	}
}

/**
 * Whether every listed point is collinear.
 *
 * @param DoubleArray points   The points.
 * @param List        vertices The distinct points to test.
 * @return Boolean True when no three of them span a triangle.
 */
private fun allCollinear(points: DoubleArray, vertices: List<Int>): Boolean {
	if (vertices.size < 3) {
		return true
	}
	val first = vertices[0]
	val second = vertices[1]
	return vertices.drop(2).all { orientOf(points, first, second, it) == 0.0 }
}