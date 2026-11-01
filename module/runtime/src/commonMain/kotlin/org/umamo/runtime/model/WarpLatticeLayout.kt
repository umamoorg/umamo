package org.umamo.runtime.model

/**
 * A warp lattice of [rows] x [columns] cells, laid out as a mesh: its control points, the grid lines between
 * them, its quad cells, and the two triangles each cell splits into for connectivity.
 *
 * The layout is the one every form, the evaluator, and the formats already share (docs/format/UMA.md, the
 * geometry accessors): `(rows + 1) x (columns + 1)` points, row-major with the column varying fastest, row 0
 * at v = 0 and column 0 at u = 0.  Held here once so a selection, an overlay, and an edit cannot each
 * re-derive it.
 *
 * The lattice is quad-based: it is edited and drawn as grid lines and cells, never as triangles.  The
 * triangles exist only for code that needs triangle connectivity (a connected-only proportional halo, face
 * picking), and split each cell along the same diagonal as the evaluator's triangle-split mode.
 *
 * @property Int rows The cell count along v.
 * @property Int columns The cell count along u.
 */
class WarpLatticeLayout(val rows: Int, val columns: Int) {
	/** The control points per row of the lattice. */
	val pointsPerRow: Int get() = columns + 1

	/** The lattice's control-point count. */
	val pointCount: Int get() = (rows + 1) * (columns + 1)

	/** The lattice's cell count. */
	val cellCount: Int get() = rows * columns

	/**
	 * The grid lines as point-index pairs: every horizontal segment row by row, then every vertical segment
	 * column by column, each pair lower index first.
	 */
	val edgeEndpoints: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val endpoints = IntArray((rows + 1) * columns * 2 + rows * (columns + 1) * 2)
		var slot = 0

		for (row in 0..rows) {
			for (column in 0 until columns) {
				endpoints[slot++] = pointIndex(row, column)
				endpoints[slot++] = pointIndex(row, column + 1)
			}
		}

		for (column in 0..columns) {
			for (row in 0 until rows) {
				endpoints[slot++] = pointIndex(row, column)
				endpoints[slot++] = pointIndex(row + 1, column)
			}
		}

		endpoints
	}

	/**
	 * The lattice's outline as its own compact point list: the `2 * (rows + columns)` border points, as indices into
	 * the lattice's points.  The four corners come first, as (0, 0), (0, columns), (rows, columns), (rows, 0), so a
	 * draw that dots only the first four dots the corners alone; the other border points follow in ring order.
	 */
	val outlinePointIndices: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val ring = outlineRing
		val indices = IntArray(ring.size)

		for (ringPosition in ring.indices) {
			indices[outlineSlotOfRing[ringPosition]] = ring[ringPosition]
		}

		indices
	}

	/**
	 * The outline's closed border ring as index pairs into [outlinePointIndices] (not into the lattice's points): one
	 * edge per border segment, each joining two neighbors around the ring - the grid lines along the lattice's four
	 * sides, and nothing inside.
	 */
	val outlineEdgeEndpoints: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val slots = outlineSlotOfRing
		val endpoints = IntArray(slots.size * 2)

		for (ringPosition in slots.indices) {
			endpoints[ringPosition * 2] = slots[ringPosition]
			endpoints[ringPosition * 2 + 1] = slots[(ringPosition + 1) % slots.size]
		}

		endpoints
	}

	/**
	 * The border points in ring order: along row 0, down the last column, back along the last row, and up column 0,
	 * each corner once.
	 */
	private val outlineRing: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val ring = IntArray(2 * (rows + columns))
		var slot = 0

		for (column in 0 until columns) {
			ring[slot++] = pointIndex(0, column)
		}

		for (row in 0 until rows) {
			ring[slot++] = pointIndex(row, columns)
		}

		for (column in columns downTo 1) {
			ring[slot++] = pointIndex(rows, column)
		}

		for (row in rows downTo 1) {
			ring[slot++] = pointIndex(row, 0)
		}

		ring
	}

	/** Each ring position's slot in the compact outline: the four corners take slots 0 to 3, the rest follow in ring order. */
	private val outlineSlotOfRing: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val ringSize = 2 * (rows + columns)
		val cornerPositions = intArrayOf(0, columns, columns + rows, 2 * columns + rows)
		val slots = IntArray(ringSize) { -1 }

		for (cornerSlot in cornerPositions.indices) {
			slots[cornerPositions[cornerSlot]] = cornerSlot
		}

		var nextSlot = cornerPositions.size

		for (ringPosition in 0 until ringSize) {
			if (slots[ringPosition] < 0) {
				slots[ringPosition] = nextSlot++
			}
		}

		slots
	}

	/**
	 * The cells as four point indices each, around the cell: (row, column), (row, column + 1),
	 * (row + 1, column + 1), (row + 1, column).  Cell `row * columns + column` is the cell's ordinal.
	 */
	val cellCorners: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val corners = IntArray(cellCount * 4)
		var slot = 0

		for (row in 0 until rows) {
			for (column in 0 until columns) {
				corners[slot++] = pointIndex(row, column)
				corners[slot++] = pointIndex(row, column + 1)
				corners[slot++] = pointIndex(row + 1, column + 1)
				corners[slot++] = pointIndex(row + 1, column)
			}
		}

		corners
	}

	/**
	 * Two triangles per cell, three point indices each, cell by cell in cell order: (row, column),
	 * (row, column + 1), (row + 1, column), then (row, column + 1), (row + 1, column + 1), (row + 1, column) -
	 * the diagonal the evaluator's triangle-split mode uses.  For connectivity only; never drawn.
	 */
	val cellTriangles: IntArray by lazy(LazyThreadSafetyMode.PUBLICATION) {
		val triangles = IntArray(cellCount * 6)
		var slot = 0

		for (row in 0 until rows) {
			for (column in 0 until columns) {
				val topLeft = pointIndex(row, column)
				val topRight = pointIndex(row, column + 1)
				val bottomLeft = pointIndex(row + 1, column)
				val bottomRight = pointIndex(row + 1, column + 1)
				triangles[slot++] = topLeft
				triangles[slot++] = topRight
				triangles[slot++] = bottomLeft
				triangles[slot++] = topRight
				triangles[slot++] = bottomRight
				triangles[slot++] = bottomLeft
			}
		}

		triangles
	}

	/**
	 * The index of the control point at ([row], [column]).
	 *
	 * @param Int row The point's row, 0 to [rows].
	 * @param Int column The point's column, 0 to [columns].
	 * @return Int The point's index into an interleaved control-point array (x at twice it, y right after).
	 */
	fun pointIndex(row: Int, column: Int): Int = row * pointsPerRow + column
}

/** This warp's lattice layout. */
val Deformer.Warp.latticeLayout: WarpLatticeLayout get() = WarpLatticeLayout(rows, columns)