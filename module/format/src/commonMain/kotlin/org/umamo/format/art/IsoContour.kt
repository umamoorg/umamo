package org.umamo.format.art

/*
 * Sub-pixel iso-contours of a scalar field sampled on a grid, by marching squares.
 *
 * A node is INSIDE when its value is strictly below the level.  Each crossing is linearly
 * interpolated along its grid edge, with the parameter clamped to [1/64, 63/64], so every ring vertex
 * lies in the open interior of its own edge: rings never touch a node, rings at one level are
 * pairwise disjoint, and no ring has a zero-length edge.  A saddle square (two inside corners on one
 * diagonal) is decided by the average of its four corners - a rule monotone in the level, so a ring
 * at one level never crosses a ring at another.
 *
 * Rings keep the inside on the LEFT of travel in raw coordinates (the right on screen, y down), so an
 * outer boundary has positive shoelace area and a hole negative - the convention AlphaContour uses.
 * Rings are closed implicitly and start at their lowest crossing-edge id, so the output is a function
 * of the field and level alone.  A ring is only guaranteed closed when every border node is outside.
 */

/** The smallest edge parameter a crossing may take; the largest is 1 minus this. */
private const val EDGE_CLAMP: Double = 1.0 / 64.0

/**
 * Traces the closed iso-contours of a grid field at a level.
 *
 * @param FloatArray values  The field, row-major, one value per node.
 * @param Int        columns The grid width in nodes.
 * @param Int        rows    The grid height in nodes.
 * @param Double     level   The iso level.
 * @param Double     originX The x of node (0, 0).
 * @param Double     originY The y of node (0, 0).
 * @param Double     spacing The distance between neighboring nodes.
 * @return List<DoubleArray> The rings, x0, y0, x1, y1, ...
 */
internal fun traceIsoRings(values: FloatArray, columns: Int, rows: Int, level: Double, originX: Double, originY: Double, spacing: Double): List<DoubleArray> {
	// Each crossing edge is the start of exactly one directed square segment and the end of one.
	val nextEdge = HashMap<Int, Int>()
	val squareEdges = IntArray(4)
	val cornerInside = BooleanArray(4)

	for (row in 0 until rows - 1) {
		for (column in 0 until columns - 1) {
			// Corners counterclockwise in raw coordinates: top-left, top-right, bottom-right, bottom-left.
			val topLeft = values[row * columns + column]
			val topRight = values[row * columns + column + 1]
			val bottomRight = values[(row + 1) * columns + column + 1]
			val bottomLeft = values[(row + 1) * columns + column]
			cornerInside[0] = topLeft < level
			cornerInside[1] = topRight < level
			cornerInside[2] = bottomRight < level
			cornerInside[3] = bottomLeft < level

			if (cornerInside[0] == cornerInside[1] && cornerInside[1] == cornerInside[2] && cornerInside[2] == cornerInside[3]) {
				continue
			}

			// Edge k runs from corner k to corner k + 1.
			squareEdges[0] = horizontalEdgeId(column, row, columns)
			squareEdges[1] = verticalEdgeId(column + 1, row, columns)
			squareEdges[2] = horizontalEdgeId(column, row + 1, columns)
			squareEdges[3] = verticalEdgeId(column, row, columns)
			val centerInside = (topLeft.toDouble() + topRight + bottomRight + bottomLeft) / 4.0 < level

			for (edge in 0 until 4) {
				val isExit = cornerInside[edge] && !cornerInside[(edge + 1) % 4]

				if (!isExit) {
					continue
				}

				// A saddle's center decides which entry an exit joins: the next one round the square
				// when the center is inside (the outside corners are cut off), else the previous one.
				nextEdge[squareEdges[edge]] = squareEdges[partnerEntry(cornerInside, edge, centerInside)]
			}
		}
	}

	val starts = nextEdge.keys.toIntArray()
	starts.sort()
	val visited = HashSet<Int>()
	val rings = ArrayList<DoubleArray>()

	for (start in starts) {
		if (start in visited) {
			continue
		}

		val ring = ArrayList<Double>()
		var edge = start

		do {
			visited.add(edge)
			appendCrossing(values, columns, level, originX, originY, spacing, edge, ring)
			edge = checkNotNull(nextEdge[edge]) { "iso ring broke open at edge $edge" }
		} while (edge != start)

		rings.add(ring.toDoubleArray())
	}

	return rings
}

/**
 * The entry edge an exit edge connects to inside one square.
 *
 * @param BooleanArray cornerInside The four corners' inside flags, counterclockwise.
 * @param Int          exitEdge     The exit edge (inside corner first).
 * @param Boolean      centerInside Whether the square's center counts as inside (decides saddles).
 * @return Int The entry edge to join.
 */
private fun partnerEntry(cornerInside: BooleanArray, exitEdge: Int, centerInside: Boolean): Int {
	val step = if (centerInside) 1 else 3

	for (distance in 1..3) {
		val candidate = (exitEdge + step * distance) % 4
		val isEntry = !cornerInside[candidate] && cornerInside[(candidate + 1) % 4]

		if (isEntry) {
			return candidate
		}
	}

	error("square has an exit edge but no entry edge")
}

/**
 * Appends one crossing's position to a ring.
 *
 * @param FloatArray        values  The field.
 * @param Int               columns The grid width in nodes.
 * @param Double            level   The iso level.
 * @param Double            originX The x of node (0, 0).
 * @param Double            originY The y of node (0, 0).
 * @param Double            spacing The node spacing.
 * @param Int               edge    The crossing edge id.
 * @param ArrayList<Double> ring    Receives x then y.
 */
private fun appendCrossing(values: FloatArray, columns: Int, level: Double, originX: Double, originY: Double, spacing: Double, edge: Int, ring: ArrayList<Double>) {
	val node = edge ushr 1
	val column = node % columns
	val row = node / columns
	val isVertical = (edge and 1) == 1
	val endColumn = if (isVertical) column else column + 1
	val endRow = if (isVertical) row + 1 else row
	val startValue = values[node].toDouble()
	val endValue = values[endRow * columns + endColumn].toDouble()
	val parameter = ((level - startValue) / (endValue - startValue)).coerceIn(EDGE_CLAMP, 1.0 - EDGE_CLAMP)
	ring.add(originX + (column + parameter * (endColumn - column)) * spacing)
	ring.add(originY + (row + parameter * (endRow - row)) * spacing)
}

/**
 * The id of the horizontal edge from node (column, row) to (column + 1, row).
 *
 * @param Int column  The left node's column.
 * @param Int row     The node row.
 * @param Int columns The grid width.
 * @return Int The edge id.
 */
private fun horizontalEdgeId(column: Int, row: Int, columns: Int): Int = 2 * (row * columns + column)

/**
 * The id of the vertical edge from node (column, row) to (column, row + 1).
 *
 * @param Int column  The node column.
 * @param Int row     The upper node's row.
 * @param Int columns The grid width.
 * @return Int The edge id.
 */
private fun verticalEdgeId(column: Int, row: Int, columns: Int): Int = 2 * (row * columns + column) + 1