package org.umamo.geometry.polyline

import org.umamo.geometry.IntStack

/*
 * Douglas-Peucker simplification over polylines, reporting which vertices to KEEP rather than building
 * a new polyline: the mesher pins the kept vertices as keypoints and resamples between them, and the
 * alpha contour simplifier keeps the lattice corners it names.  Iterative (an explicit range stack), so
 * a long ring cannot exhaust a Kotlin/Native stack.  A vertex is kept when it deviates from the current
 * span's segment by strictly more than the tolerance, so a tolerance of zero drops exactly the collinear
 * vertices.  The walk is shared; the Double and Int front ends differ only in how they measure.
 */

/** The largest coordinate magnitude the Int overload accepts: every product it forms then fits a Long. */
private const val MAXIMUM_INT_COORDINATE: Int = 1 shl 29

/**
 * The vertices Douglas-Peucker keeps at a tolerance.
 *
 * An open polyline always keeps both ends.  A closed ring is anchored at [ringAnchor] (by default its
 * lowest vertex: smallest x, then smallest y, then index) and at the vertex farthest from it (ties to the
 * lower index), and each half is simplified on its own; both anchors are kept.  Every dropped vertex lies
 * within the tolerance of the segment between the kept vertices around it.
 *
 * @param DoubleArray points     The vertices, x0, y0, x1, y1, ...
 * @param Double      tolerance  The largest deviation a dropped vertex may have: non-negative, and infinite keeps only the ends or the anchors.
 * @param Boolean     closed     Whether the last vertex joins the first.
 * @param Int?        ringAnchor A closed ring's first anchor, or null for its lowest vertex; only for a closed ring.
 * @return IntArray The kept vertex indices, ascending.
 */
public fun douglasPeuckerKeypoints(points: DoubleArray, tolerance: Double, closed: Boolean, ringAnchor: Int? = null): IntArray {
	require(points.size % 2 == 0) { "points must hold x, y pairs, but has ${points.size} components" }
	val vertexCount = points.size / 2
	requireValidRequest(vertexCount, tolerance, closed, ringAnchor)

	return keypoints(
		vertexCount = vertexCount,
		tolerance = tolerance,
		closed = closed,
		ringAnchor = { ringAnchor ?: lowestVertex(points, vertexCount) },
		squaredGap = { first, second -> squaredGap(points, first, second) },
		squaredDistanceToSegment = { vertex, start, end -> squaredDistanceToSegment(points, vertex, start, end) },
	)
}

/**
 * The vertices Douglas-Peucker keeps at a tolerance, over integer vertices such as pixel-corner
 * lattice rings, measured exactly.
 *
 * The same contract as the Double overload.  Dot, cross, and length products are exact Longs and only
 * the final quotient goes through Double, so a vertex exactly at the tolerance compares the same way
 * every time (a staircase step one pixel off a long diagonal is the common case at a 1 px tolerance).
 * While every coordinate stays within 2^25 of zero the gaps that pick a ring's second anchor are exact
 * too.
 *
 * @param IntArray points     The vertices, x0, y0, x1, y1, ..., each coordinate within 2^29 of zero.
 * @param Double   tolerance  The largest deviation a dropped vertex may have: non-negative, and infinite keeps only the ends or the anchors.
 * @param Boolean  closed     Whether the last vertex joins the first.
 * @param Int?     ringAnchor A closed ring's first anchor, or null for its lowest vertex; only for a closed ring.
 * @return IntArray The kept vertex indices, ascending.
 */
public fun douglasPeuckerKeypoints(points: IntArray, tolerance: Double, closed: Boolean, ringAnchor: Int? = null): IntArray {
	require(points.size % 2 == 0) { "points must hold x, y pairs, but has ${points.size} components" }
	require(points.all { coordinate -> coordinate in -MAXIMUM_INT_COORDINATE..MAXIMUM_INT_COORDINATE }) {
		"every coordinate must lie within $MAXIMUM_INT_COORDINATE of zero"
	}
	val vertexCount = points.size / 2
	requireValidRequest(vertexCount, tolerance, closed, ringAnchor)

	return keypoints(
		vertexCount = vertexCount,
		tolerance = tolerance,
		closed = closed,
		ringAnchor = { ringAnchor ?: lowestVertex(points, vertexCount) },
		squaredGap = { first, second -> squaredGap(points, first, second) },
		squaredDistanceToSegment = { vertex, start, end -> squaredDistanceToSegment(points, vertex, start, end) },
	)
}

/**
 * Checks what both overloads share: the tolerance, and a ring anchor only on a closed ring and inside it.
 *
 * @param Int     vertexCount The number of vertices.
 * @param Double  tolerance   The tolerance asked for.
 * @param Boolean closed      Whether the polyline is a ring.
 * @param Int?    ringAnchor  The anchor asked for, or null.
 */
private fun requireValidRequest(vertexCount: Int, tolerance: Double, closed: Boolean, ringAnchor: Int?) {
	require(tolerance >= 0.0) { "tolerance must be non-negative: $tolerance" }

	if (ringAnchor != null) {
		require(closed) { "a ring anchor applies only to a closed ring" }
		require(ringAnchor in 0 until vertexCount) { "ring anchor $ringAnchor is not one of the $vertexCount vertices" }
	}
}

/**
 * The shared walk: picks the anchors, simplifies each span between them, and lists the kept vertices.
 *
 * @param Int      vertexCount              The number of vertices.
 * @param Double   tolerance                The deviation allowed.
 * @param Boolean  closed                   Whether the last vertex joins the first.
 * @param Function ringAnchor               A closed ring's first anchor, asked only when one is needed.
 * @param Function squaredGap               The squared distance between two vertices.
 * @param Function squaredDistanceToSegment The squared distance from a vertex to the segment between two others.
 * @return IntArray The kept vertex indices, ascending.
 */
private inline fun keypoints(
	vertexCount: Int,
	tolerance: Double,
	closed: Boolean,
	ringAnchor: () -> Int,
	squaredGap: (first: Int, second: Int) -> Double,
	squaredDistanceToSegment: (vertex: Int, start: Int, end: Int) -> Double,
): IntArray {
	if (vertexCount <= 2) {
		return IntArray(vertexCount) { it }
	}

	val keep = BooleanArray(vertexCount)
	val pending = IntStack()
	val toleranceSquared = tolerance * tolerance

	if (closed) {
		val anchor = ringAnchor()
		val opposite = farthestVertex(vertexCount, anchor, squaredGap)
		keep[anchor] = true
		keep[opposite] = true
		simplifySpan(vertexCount, anchor, opposite, toleranceSquared, keep, pending, squaredDistanceToSegment)
		simplifySpan(vertexCount, opposite, anchor, toleranceSquared, keep, pending, squaredDistanceToSegment)
	} else {
		keep[0] = true
		keep[vertexCount - 1] = true
		simplifySpan(vertexCount, 0, vertexCount - 1, toleranceSquared, keep, pending, squaredDistanceToSegment)
	}

	var keptCount = 0

	for (vertex in 0 until vertexCount) {
		if (keep[vertex]) {
			keptCount++
		}
	}

	val kept = IntArray(keptCount)
	var written = 0

	for (vertex in 0 until vertexCount) {
		if (keep[vertex]) {
			kept[written] = vertex
			written++
		}
	}

	return kept
}

/**
 * Simplifies the vertices strictly between two kept ones, walking forward (and wrapping, for a ring)
 * from [first] to [last].
 *
 * @param Int          vertexCount              The number of vertices.
 * @param Int          first                    The span's kept start.
 * @param Int          last                     The span's kept end.
 * @param Double       toleranceSquared         The squared deviation allowed.
 * @param BooleanArray keep                     Marks kept vertices.
 * @param IntStack     pending                  The empty range stack to work on.
 * @param Function     squaredDistanceToSegment The squared distance from a vertex to the segment between two others.
 */
private inline fun simplifySpan(
	vertexCount: Int,
	first: Int,
	last: Int,
	toleranceSquared: Double,
	keep: BooleanArray,
	pending: IntStack,
	squaredDistanceToSegment: (vertex: Int, start: Int, end: Int) -> Double,
) {
	// Ranges are pairs of UNWRAPPED positions, so a ring's span may run past the last index.
	pending.push(first)
	pending.push(if (last > first) last else last + vertexCount)

	while (pending.isNotEmpty()) {
		val end = pending.pop()
		val start = pending.pop()
		var farthest = -1
		var farthestDistance = toleranceSquared

		for (position in start + 1 until end) {
			val distance = squaredDistanceToSegment(position % vertexCount, start % vertexCount, end % vertexCount)

			// Strictly greater: ties keep the earlier vertex and a zero tolerance drops collinear ones.
			if (distance > farthestDistance) {
				farthestDistance = distance
				farthest = position
			}
		}

		if (farthest >= 0) {
			keep[farthest % vertexCount] = true
			pending.push(start)
			pending.push(farthest)
			pending.push(farthest)
			pending.push(end)
		}
	}
}

/**
 * The vertex farthest from another, ties to the lower index.
 *
 * @param Int      vertexCount The number of vertices.
 * @param Int      anchor      The vertex measured from.
 * @param Function squaredGap  The squared distance between two vertices.
 * @return Int The farthest vertex (the next one when every vertex coincides with the anchor).
 */
private inline fun farthestVertex(vertexCount: Int, anchor: Int, squaredGap: (first: Int, second: Int) -> Double): Int {
	var farthest = (anchor + 1) % vertexCount
	var farthestDistance = -1.0

	for (vertex in 0 until vertexCount) {
		if (vertex == anchor) {
			continue
		}

		val distance = squaredGap(anchor, vertex)

		if (distance > farthestDistance) {
			farthestDistance = distance
			farthest = vertex
		}
	}

	return farthest
}

/**
 * The lowest vertex: smallest x, then smallest y, then lowest index.
 *
 * @param DoubleArray points      The vertices.
 * @param Int         vertexCount The number of vertices.
 * @return Int The vertex index.
 */
private fun lowestVertex(points: DoubleArray, vertexCount: Int): Int {
	var lowest = 0

	for (vertex in 1 until vertexCount) {
		val x = points[2 * vertex]
		val y = points[2 * vertex + 1]
		val lowestX = points[2 * lowest]

		if (x < lowestX || (x == lowestX && y < points[2 * lowest + 1])) {
			lowest = vertex
		}
	}

	return lowest
}

/**
 * The lowest vertex: smallest x, then smallest y, then lowest index.
 *
 * @param IntArray points      The vertices.
 * @param Int      vertexCount The number of vertices.
 * @return Int The vertex index.
 */
private fun lowestVertex(points: IntArray, vertexCount: Int): Int {
	var lowest = 0

	for (vertex in 1 until vertexCount) {
		val x = points[2 * vertex]
		val y = points[2 * vertex + 1]
		val lowestX = points[2 * lowest]

		if (x < lowestX || (x == lowestX && y < points[2 * lowest + 1])) {
			lowest = vertex
		}
	}

	return lowest
}

/**
 * The squared distance between two vertices.
 *
 * @param DoubleArray points The vertices.
 * @param Int         first  One vertex.
 * @param Int         second The other.
 * @return Double The squared distance.
 */
private fun squaredGap(points: DoubleArray, first: Int, second: Int): Double {
	val deltaX = points[2 * second] - points[2 * first]
	val deltaY = points[2 * second + 1] - points[2 * first + 1]

	return deltaX * deltaX + deltaY * deltaY
}

/**
 * The squared distance between two vertices, summed exactly in a Long.
 *
 * @param IntArray points The vertices.
 * @param Int      first  One vertex.
 * @param Int      second The other.
 * @return Double The squared distance.
 */
private fun squaredGap(points: IntArray, first: Int, second: Int): Double {
	val deltaX = (points[2 * second] - points[2 * first]).toLong()
	val deltaY = (points[2 * second + 1] - points[2 * first + 1]).toLong()

	return (deltaX * deltaX + deltaY * deltaY).toDouble()
}

/**
 * The squared distance from a vertex to the segment between two others.
 *
 * @param DoubleArray points The vertices.
 * @param Int         vertex The vertex measured.
 * @param Int         start  The segment's start vertex.
 * @param Int         end    The segment's end vertex.
 * @return Double The squared distance.
 */
private fun squaredDistanceToSegment(points: DoubleArray, vertex: Int, start: Int, end: Int): Double {
	val startX = points[2 * start]
	val startY = points[2 * start + 1]
	val alongX = points[2 * end] - startX
	val alongY = points[2 * end + 1] - startY
	val pointX = points[2 * vertex]
	val pointY = points[2 * vertex + 1]
	val lengthSquared = alongX * alongX + alongY * alongY
	val fraction = if (lengthSquared > 0.0) (((pointX - startX) * alongX + (pointY - startY) * alongY) / lengthSquared).coerceIn(0.0, 1.0) else 0.0
	val offsetX = startX + fraction * alongX - pointX
	val offsetY = startY + fraction * alongY - pointY

	return offsetX * offsetX + offsetY * offsetY
}

/**
 * The squared distance from a vertex to the segment between two others, clamped to the segment's ends.
 *
 * Dot, cross, and length products are exact Longs; only the final quotient goes through Double.  A
 * zero-length segment (two coinciding vertices of a ring that touches itself) degrades to the distance
 * to its start.
 *
 * @param IntArray points The vertices.
 * @param Int      vertex The vertex measured.
 * @param Int      start  The segment's start vertex.
 * @param Int      end    The segment's end vertex.
 * @return Double The squared distance.
 */
private fun squaredDistanceToSegment(points: IntArray, vertex: Int, start: Int, end: Int): Double {
	val startX = points[2 * start]
	val startY = points[2 * start + 1]
	val alongX = (points[2 * end] - startX).toLong()
	val alongY = (points[2 * end + 1] - startY).toLong()
	val toPointX = (points[2 * vertex] - startX).toLong()
	val toPointY = (points[2 * vertex + 1] - startY).toLong()
	val lengthSquared = alongX * alongX + alongY * alongY

	if (lengthSquared == 0L) {
		return (toPointX * toPointX + toPointY * toPointY).toDouble()
	}

	val projection = toPointX * alongX + toPointY * alongY

	if (projection <= 0L) {
		return (toPointX * toPointX + toPointY * toPointY).toDouble()
	}

	if (projection >= lengthSquared) {
		val fromEndX = (points[2 * vertex] - points[2 * end]).toLong()
		val fromEndY = (points[2 * vertex + 1] - points[2 * end + 1]).toLong()

		return (fromEndX * fromEndX + fromEndY * fromEndY).toDouble()
	}

	val cross = toPointX * alongY - toPointY * alongX

	return cross.toDouble() * cross.toDouble() / lengthSquared.toDouble()
}