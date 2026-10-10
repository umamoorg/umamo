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
internal fun edgeKey(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)

/**
 * Whether a point collinear with a segment lies strictly between its ends.
 *
 * @param DoubleArray points The points.
 * @param Int         start  The segment's start.
 * @param Int         end    The segment's end.
 * @param Int         query  The collinear point.
 * @return Boolean True when the query is inside the open segment.
 */
internal fun strictlyBetween(points: DoubleArray, start: Int, end: Int, query: Int): Boolean {
	val startX = points[2 * start]
	val startY = points[2 * start + 1]
	val endX = points[2 * end]
	val endY = points[2 * end + 1]
	val queryX = points[2 * query]
	val queryY = points[2 * query + 1]
	// Collinear, so comparing along any axis the segment spans decides it exactly.
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

/**
 * Asserts that a result is a constrained Delaunay triangulation, by brute force: every triangle is
 * positively oriented and uses canonical indices only, each edge has at most one triangle per side,
 * no point sits on an output edge's open segment, every edge shared by two output triangles is
 * either a constraint or locally Delaunay, and the reported constraints are distinct (low, high)
 * pairs that never properly cross one another.  When the result covers the convex hull (no
 * boundaries), every distinct point is a vertex, every constraint is an output edge, and Euler's count
 * holds as well.
 *
 * @param DoubleArray   points      The triangulated points.
 * @param Triangulation result      The result under test.
 * @param Boolean       coversHull  Whether the result should cover the whole convex hull.
 */
internal fun assertConstrainedDelaunayTriangulation(points: DoubleArray, result: Triangulation, coversHull: Boolean) {
	val pointCount = points.size / 2
	val canonical = result.canonicalIndex
	val distinct = (0 until pointCount).filter { canonical[it] == it }
	val triangles = result.triangles
	val constrained = HashSet<Long>()

	for (position in 0 until result.constrainedEdges.size / 2) {
		val low = result.constrainedEdges[2 * position]
		val high = result.constrainedEdges[2 * position + 1]
		assertTrue(low < high, "constraint ($low, $high) is not a (low, high) pair")
		assertTrue(canonical[low] == low && canonical[high] == high, "constraint ($low, $high) uses a non-canonical vertex")
		assertTrue(constrained.add(edgeKey(low, high)), "constraint ($low, $high) is listed twice")
	}

	val constraintList = constrained.toList()

	for (firstPosition in constraintList.indices) {
		for (secondPosition in firstPosition + 1 until constraintList.size) {
			val first = constraintList[firstPosition]
			val second = constraintList[secondPosition]
			assertTrue(
				!properlyCross(points, (first ushr 32).toInt(), first.toInt(), (second ushr 32).toInt(), second.toInt()),
				"constraints ${first ushr 32}-${first.toInt()} and ${second ushr 32}-${second.toInt()} cross",
			)
		}
	}

	// Each directed edge maps to the third corner of its triangle.
	val apexOf = HashMap<Long, Int>()

	for (triangleIndex in 0 until result.triangleCount) {
		val corners = intArrayOf(triangles[3 * triangleIndex], triangles[3 * triangleIndex + 1], triangles[3 * triangleIndex + 2])

		for (vertex in corners) {
			assertTrue(vertex in 0 until pointCount && canonical[vertex] == vertex, "triangle $triangleIndex uses non-canonical vertex $vertex")
		}

		assertTrue(orientOf(points, corners[0], corners[1], corners[2]) > 0.0, "triangle $triangleIndex is not positively oriented")

		for (corner in 0 until 3) {
			val key = edgeKey(corners[corner], corners[(corner + 1) % 3])
			assertTrue(apexOf.put(key, corners[(corner + 2) % 3]) == null, "directed edge ${corners[corner]}->${corners[(corner + 1) % 3]} appears twice")
		}
	}

	for ((key, apex) in apexOf) {
		val start = (key ushr 32).toInt()
		val end = key.toInt()

		for (other in distinct) {
			if (other != start && other != end && orientOf(points, start, end, other) == 0.0 && strictlyBetween(points, start, end, other)) {
				fail("point $other lies on the open segment of edge $start->$end")
			}
		}

		val farApex = apexOf[edgeKey(end, start)] ?: continue

		if (constrained.contains(edgeKey(minOf(start, end), maxOf(start, end)))) {
			continue
		}

		val inside =
			incircle(
				points[2 * start],
				points[2 * start + 1],
				points[2 * end],
				points[2 * end + 1],
				points[2 * apex],
				points[2 * apex + 1],
				points[2 * farApex],
				points[2 * farApex + 1],
			)
		assertTrue(inside <= 0.0, "unconstrained edge $start->$end is not locally Delaunay")
	}

	if (coversHull && triangles.isNotEmpty()) {
		val used = triangles.toSet()
		assertEquals(distinct.toSet(), used, "every distinct point is a vertex")

		for (key in constrained) {
			val low = (key ushr 32).toInt()
			val high = key.toInt()
			assertTrue(apexOf.containsKey(edgeKey(low, high)) || apexOf.containsKey(edgeKey(high, low)), "constraint ($low, $high) is not an edge")
		}

		val boundaryEdges = apexOf.keys.count { key -> !apexOf.containsKey(edgeKey(key.toInt(), (key ushr 32).toInt())) }
		assertEquals(2 * distinct.size - 2 - boundaryEdges, result.triangleCount, "Euler's count T = 2n - 2 - h")
	}
}

/**
 * The summed area of a result's triangles.
 *
 * @param DoubleArray   points The points.
 * @param Triangulation result The result.
 * @return Double The total area (every triangle is positively oriented, so it is a plain sum).
 */
internal fun totalArea(points: DoubleArray, result: Triangulation): Double {
	var doubledArea = 0.0

	for (triangleIndex in 0 until result.triangleCount) {
		val first = result.triangles[3 * triangleIndex]
		val second = result.triangles[3 * triangleIndex + 1]
		val third = result.triangles[3 * triangleIndex + 2]
		doubledArea += (points[2 * second] - points[2 * first]) * (points[2 * third + 1] - points[2 * first + 1]) -
			(points[2 * second + 1] - points[2 * first + 1]) * (points[2 * third] - points[2 * first])
	}

	return doubledArea / 2.0
}

/**
 * Whether two segments cross at a single point interior to both.
 *
 * @param DoubleArray points      The points.
 * @param Int         firstStart  The first segment's start.
 * @param Int         firstEnd    The first segment's end.
 * @param Int         secondStart The second segment's start.
 * @param Int         secondEnd   The second segment's end.
 * @return Boolean True for a proper crossing.
 */
internal fun properlyCross(points: DoubleArray, firstStart: Int, firstEnd: Int, secondStart: Int, secondEnd: Int): Boolean {
	val startSide = orientOf(points, firstStart, firstEnd, secondStart)
	val endSide = orientOf(points, firstStart, firstEnd, secondEnd)
	val otherStartSide = orientOf(points, secondStart, secondEnd, firstStart)
	val otherEndSide = orientOf(points, secondStart, secondEnd, firstEnd)

	return strictlyOpposite(startSide, endSide) && strictlyOpposite(otherStartSide, otherEndSide)
}

/**
 * Whether two predicate values have strictly opposite signs (compared by sign, so tiny magnitudes
 * cannot underflow a product to zero).
 *
 * @param Double first  One value.
 * @param Double second The other.
 * @return Boolean True when one is positive and the other negative.
 */
private fun strictlyOpposite(first: Double, second: Double): Boolean = (first > 0.0 && second < 0.0) || (first < 0.0 && second > 0.0)