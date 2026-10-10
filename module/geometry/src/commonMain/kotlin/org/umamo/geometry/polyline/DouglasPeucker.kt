package org.umamo.geometry.polyline

/*
 * Douglas-Peucker simplification over Double polylines, reporting which vertices to KEEP rather than
 * building a new polyline: the mesher pins the kept vertices as keypoints and resamples between them.
 * Iterative (an explicit range stack), so a long ring cannot exhaust a Kotlin/Native stack.  A vertex
 * is kept when it deviates from the current span's segment by strictly more than the tolerance, so a
 * tolerance of zero drops exactly the collinear vertices.
 */

/**
 * The vertices Douglas-Peucker keeps at a tolerance.
 *
 * An open polyline always keeps both ends.  A closed ring is anchored at its lowest vertex (smallest
 * x, then smallest y, then index) and at the vertex farthest from it, and each half is simplified on
 * its own; both anchors are kept.  Every dropped vertex lies within the tolerance of the segment
 * between the kept vertices around it.
 *
 * @param DoubleArray points    The vertices, x0, y0, x1, y1, ...
 * @param Double      tolerance The largest deviation a dropped vertex may have (non-negative).
 * @param Boolean     closed    Whether the last vertex joins the first.
 * @return IntArray The kept vertex indices, ascending.
 */
public fun douglasPeuckerKeypoints(points: DoubleArray, tolerance: Double, closed: Boolean): IntArray {
	require(points.size % 2 == 0) { "points must hold x, y pairs, but has ${points.size} components" }
	require(tolerance >= 0.0 && tolerance.isFinite()) { "tolerance must be non-negative and finite: $tolerance" }
	val vertexCount = points.size / 2

	if (vertexCount <= 2) {
		return IntArray(vertexCount) { it }
	}

	val keep = BooleanArray(vertexCount)

	if (closed) {
		val anchor = lowestVertex(points, vertexCount)
		val opposite = farthestVertex(points, vertexCount, anchor)
		keep[anchor] = true
		keep[opposite] = true
		simplifySpan(points, vertexCount, anchor, opposite, tolerance, keep)
		simplifySpan(points, vertexCount, opposite, anchor, tolerance, keep)
	} else {
		keep[0] = true
		keep[vertexCount - 1] = true
		simplifySpan(points, vertexCount, 0, vertexCount - 1, tolerance, keep)
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
 * @param DoubleArray  points      The vertices.
 * @param Int          vertexCount The number of vertices.
 * @param Int          first       The span's kept start.
 * @param Int          last        The span's kept end.
 * @param Double       tolerance   The deviation allowed.
 * @param BooleanArray keep        Marks kept vertices.
 */
private fun simplifySpan(points: DoubleArray, vertexCount: Int, first: Int, last: Int, tolerance: Double, keep: BooleanArray) {
	val toleranceSquared = tolerance * tolerance
	// Ranges are pairs of UNWRAPPED positions, so a ring's span may run past the last index.
	val pending = ArrayList<Long>()
	val lastUnwrapped = if (last > first) last else last + vertexCount
	pending.add(packRange(first, lastUnwrapped))

	while (pending.isNotEmpty()) {
		val range = pending.removeAt(pending.size - 1)
		val start = (range ushr 32).toInt()
		val end = range.toInt()
		var farthest = -1
		var farthestDistance = toleranceSquared

		for (position in start + 1 until end) {
			val distance = squaredDistanceToSegment(points, position % vertexCount, start % vertexCount, end % vertexCount)

			// Strictly greater: ties keep the earlier vertex and a zero tolerance drops collinear ones.
			if (distance > farthestDistance) {
				farthestDistance = distance
				farthest = position
			}
		}

		if (farthest >= 0) {
			keep[farthest % vertexCount] = true
			pending.add(packRange(start, farthest))
			pending.add(packRange(farthest, end))
		}
	}
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
 * The vertex farthest from another, ties to the lower index.
 *
 * @param DoubleArray points      The vertices.
 * @param Int         vertexCount The number of vertices.
 * @param Int         anchor      The vertex measured from.
 * @return Int The farthest vertex (the next one when every vertex coincides with the anchor).
 */
private fun farthestVertex(points: DoubleArray, vertexCount: Int, anchor: Int): Int {
	var farthest = (anchor + 1) % vertexCount
	var farthestDistance = -1.0

	for (vertex in 0 until vertexCount) {
		if (vertex == anchor) {
			continue
		}

		val deltaX = points[2 * vertex] - points[2 * anchor]
		val deltaY = points[2 * vertex + 1] - points[2 * anchor + 1]
		val distance = deltaX * deltaX + deltaY * deltaY

		if (distance > farthestDistance) {
			farthestDistance = distance
			farthest = vertex
		}
	}

	return farthest
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
 * Packs a range of unwrapped positions into one Long.
 *
 * @param Int start The range's start.
 * @param Int end   The range's end.
 * @return Long The packed range.
 */
private fun packRange(start: Int, end: Int): Long = (start.toLong() shl 32) or (end.toLong() and 0xFFFFFFFFL)