package org.umamo.geometry.triangulation

/*
 * The order points enter the triangulation, and which points enter at all.
 *
 * Exact duplicates are found with a lexicographic sort and aliased to their lowest input index before
 * anything is inserted, so point location never meets a point it already has.  The survivors are
 * sorted along a Hilbert curve: consecutive insertions land near each other, so each walk from the
 * last triangle is short.  Both sorts end on the input index, so the order - and therefore the whole
 * triangulation, ties included - is a function of the input alone.
 */

/** The largest Hilbert grid coordinate: 16 bits per axis. */
private const val HILBERT_GRID_MAXIMUM: Int = 65535

/** The Hilbert grid's side length. */
private const val HILBERT_GRID_SIZE: Int = 65536

/** Masks the input index back out of a packed sort key. */
private const val INDEX_MASK: Long = 0x7FFFFFFFL

/**
 * Which points to insert, and in what order.
 *
 * A plain class: both fields are arrays.
 *
 * @property IntArray canonicalIndex For each input point, the lowest index holding the same coordinates.
 * @property IntArray order          The canonical points in insertion order.
 */
internal class InsertionPlan(val canonicalIndex: IntArray, val order: IntArray)

/**
 * Validates the points, aliases exact duplicates, and orders the survivors along a Hilbert curve.
 *
 * @param DoubleArray points The points, x0, y0, x1, y1, ...; every component must be finite.
 * @return InsertionPlan The aliasing and the insertion order.
 */
internal fun planInsertion(points: DoubleArray): InsertionPlan {
	require(points.size % 2 == 0) { "points must hold x, y pairs, but has ${points.size} components" }

	for (componentIndex in points.indices) {
		require(points[componentIndex].isFinite()) { "point component $componentIndex is not finite: ${points[componentIndex]}" }
	}

	val pointCount = points.size / 2
	val canonicalIndex = IntArray(pointCount)
	val unique = aliasDuplicates(points, pointCount, canonicalIndex)

	return InsertionPlan(canonicalIndex, hilbertOrder(points, unique))
}

/**
 * Finds exact duplicates and records each point's canonical index.  Zero and negative zero count as
 * the same coordinate, as `==` does.
 *
 * @param DoubleArray points         The points.
 * @param Int         pointCount     The number of points.
 * @param IntArray    canonicalIndex Receives each point's canonical index.
 * @return IntArray The canonical points, ascending.
 */
private fun aliasDuplicates(points: DoubleArray, pointCount: Int, canonicalIndex: IntArray): IntArray {
	// Adding 0.0 turns -0.0 into 0.0, so compareTo groups the zeros that == already calls equal.
	val byCoordinates = MutableList(pointCount) { it }

	byCoordinates.sortWith { left, right ->
		val byX = (points[2 * left] + 0.0).compareTo(points[2 * right] + 0.0)
		if (byX != 0) {
			byX
		} else {
			val byY = (points[2 * left + 1] + 0.0).compareTo(points[2 * right + 1] + 0.0)
			if (byY != 0) byY else left.compareTo(right)
		}
	}

	var uniqueCount = 0
	var runLeader = -1

	for (vertex in byCoordinates) {
		val sameAsLeader =
			runLeader >= 0 &&
				points[2 * vertex] == points[2 * runLeader] &&
				points[2 * vertex + 1] == points[2 * runLeader + 1]

		if (sameAsLeader) {
			canonicalIndex[vertex] = runLeader
		} else {
			runLeader = vertex
			canonicalIndex[vertex] = vertex
			uniqueCount++
		}
	}

	val unique = IntArray(uniqueCount)
	var written = 0

	for (vertex in 0 until pointCount) {
		if (canonicalIndex[vertex] == vertex) {
			unique[written] = vertex
			written++
		}
	}

	return unique
}

/**
 * Sorts points along a Hilbert curve over their bounding square, ties broken by index.
 *
 * @param DoubleArray points The points.
 * @param IntArray    unique The points to order.
 * @return IntArray The same points in curve order.
 */
private fun hilbertOrder(points: DoubleArray, unique: IntArray): IntArray {
	if (unique.isEmpty()) {
		return unique
	}

	var minimumX = Double.POSITIVE_INFINITY
	var minimumY = Double.POSITIVE_INFINITY
	var maximumX = Double.NEGATIVE_INFINITY
	var maximumY = Double.NEGATIVE_INFINITY

	for (vertex in unique) {
		minimumX = minOf(minimumX, points[2 * vertex])
		minimumY = minOf(minimumY, points[2 * vertex + 1])
		maximumX = maxOf(maximumX, points[2 * vertex])
		maximumY = maxOf(maximumY, points[2 * vertex + 1])
	}

	// One scale for both axes keeps the curve's cells square; a zero or overflowing span sends every
	// point to cell zero, where the index alone orders them.
	val span = maxOf(maximumX - minimumX, maximumY - minimumY)
	val scale = if (span > 0.0 && span.isFinite()) HILBERT_GRID_MAXIMUM / span else 0.0
	val keys =
		LongArray(unique.size) { position ->
			val vertex = unique[position]
			val gridX = gridCoordinate((points[2 * vertex] - minimumX) * scale)
			val gridY = gridCoordinate((points[2 * vertex + 1] - minimumY) * scale)
			(hilbertIndex(gridX, gridY) shl 31) or vertex.toLong()
		}

	keys.sort()

	return IntArray(unique.size) { position -> (keys[position] and INDEX_MASK).toInt() }
}

/**
 * Clamps a scaled offset onto the Hilbert grid.
 *
 * @param Double scaled The offset from the minimum, scaled to grid units.
 * @return Int A grid coordinate in 0..65535.
 */
private fun gridCoordinate(scaled: Double): Int = scaled.toInt().coerceIn(0, HILBERT_GRID_MAXIMUM)

/**
 * The distance along a 65536 x 65536 Hilbert curve of one grid cell (the classic xy2d).
 *
 * @param Int gridX The cell's x, in 0..65535.
 * @param Int gridY The cell's y, in 0..65535.
 * @return Long The cell's position along the curve, in 0..2^32 - 1.
 */
private fun hilbertIndex(gridX: Int, gridY: Int): Long {
	var cellX = gridX
	var cellY = gridY
	var distance = 0L
	var half = HILBERT_GRID_SIZE / 2

	while (half > 0) {
		val inRightHalf = if ((cellX and half) > 0) 1 else 0
		val inUpperHalf = if ((cellY and half) > 0) 1 else 0

		distance += half.toLong() * half.toLong() * ((3 * inRightHalf) xor inUpperHalf).toLong()

		// Rotate the quadrant so the sub-curve runs the same way as the whole.
		if (inUpperHalf == 0) {
			if (inRightHalf == 1) {
				cellX = HILBERT_GRID_SIZE - 1 - cellX
				cellY = HILBERT_GRID_SIZE - 1 - cellY
			}
			val swapped = cellX
			cellX = cellY
			cellY = swapped
		}
		half /= 2
	}

	return distance
}