package org.umamo.runtime.model

import kotlin.math.abs
import kotlin.math.sqrt

/*
 * Which parts of a warp lattice face away from the viewer.  The viewport looks along +Y at the art plane, so a cell's
 * facing is the winding of its corners as drawn in world X/Z: the grid's own order (columns along +X, rows down -Z)
 * runs clockwise there, and a cell drawn the other way round shows its back.  Only depth tells the back of a wrap from
 * a lattice mirrored on purpose, which draw the same, so a lattice with no depth has no back.
 */

/**
 * The parts of a lattice that face away from the viewer.
 *
 * @property BooleanArray points One per control point: every cell touching it faces away.
 * @property BooleanArray edges One per grid line, in [WarpLatticeLayout.edgeEndpoints] order: every cell beside it
 *   faces away.
 * @property BooleanArray cells One per cell, in cell order: the cell faces away.
 */
class LatticeBackFacing(
	val points: BooleanArray,
	val edges: BooleanArray,
	val cells: BooleanArray,
) {
	override fun equals(other: Any?): Boolean = other is LatticeBackFacing && points.contentEquals(other.points) && edges.contentEquals(other.edges) && cells.contentEquals(other.cells)

	override fun hashCode(): Int = (points.contentHashCode() * 31 + edges.contentHashCode()) * 31 + cells.contentHashCode()
}

/** The sign a facing cell's winding has in world X/Z: the grid's own order runs clockwise with Z up. */
private const val FRONT_WINDING_SIGN = -1f

/**
 * How small the sine of the angle between a cell's diagonals may be before the cell counts as seen edge-on, neither
 * front nor back.
 */
private const val EDGE_ON_SINE = 1e-6f

/**
 * The parts of this lattice that face away from the viewer at [world] and [depth], or null when the lattice has no
 * depth or nothing of it faces away.
 *
 * @param FloatArray world The lattice's points in world space, interleaved x, z (up).
 * @param LatticeDepth depth How deep each point sits.
 * @return LatticeBackFacing? The back-facing parts, or null.
 */
fun WarpLatticeLayout.backFacingOf(world: FloatArray, depth: LatticeDepth): LatticeBackFacing? {
	if (depth.isFlat || world.size != pointCount * 2) {
		return null
	}

	val cells = BooleanArray(cellCount)
	val corners = cellCorners
	var anyBack = false

	for (cellIndex in 0 until cellCount) {
		val slot = cellIndex * 4
		val back = cellFacesAway(world, corners[slot], corners[slot + 1], corners[slot + 2], corners[slot + 3])
		cells[cellIndex] = back
		anyBack = anyBack || back
	}

	if (!anyBack) {
		return null
	}

	val points = BooleanArray(pointCount)

	for (row in 0..rows) {
		for (column in 0..columns) {
			points[pointIndex(row, column)] = everyCellBack(cells, row - 1..row, column - 1..column)
		}
	}

	val edges = BooleanArray(edgeEndpoints.size / 2)
	var edgeIndex = 0

	// The same order as edgeEndpoints: every horizontal segment row by row, then every vertical one column by column.
	for (row in 0..rows) {
		for (column in 0 until columns) {
			edges[edgeIndex++] = everyCellBack(cells, row - 1..row, column..column)
		}
	}

	for (column in 0..columns) {
		for (row in 0 until rows) {
			edges[edgeIndex++] = everyCellBack(cells, row..row, column - 1..column)
		}
	}

	return LatticeBackFacing(points, edges, cells)
}

/**
 * Whether the cell with corners [first] to [fourth], in ring order, faces away: its doubled signed area, the cross
 * product of its diagonals, has the sign opposite the grid's own.  A cell whose diagonals are nearly parallel is seen
 * edge-on and does not count.
 *
 * @param FloatArray world The points, interleaved x, z.
 * @param Int first The first corner's point index.
 * @param Int second The second corner's.
 * @param Int third The third corner's.
 * @param Int fourth The fourth corner's.
 * @return Boolean True when the cell faces away.
 */
private fun cellFacesAway(world: FloatArray, first: Int, second: Int, third: Int, fourth: Int): Boolean {
	val diagonalX = world[third * 2] - world[first * 2]
	val diagonalZ = world[third * 2 + 1] - world[first * 2 + 1]
	val crossX = world[fourth * 2] - world[second * 2]
	val crossZ = world[fourth * 2 + 1] - world[second * 2 + 1]
	val doubledArea = diagonalX * crossZ - diagonalZ * crossX
	val scale = sqrt(diagonalX * diagonalX + diagonalZ * diagonalZ) * sqrt(crossX * crossX + crossZ * crossZ)

	if (!doubledArea.isFinite() || abs(doubledArea) <= EDGE_ON_SINE * scale) {
		return false
	}

	return doubledArea * FRONT_WINDING_SIGN < 0f
}

/**
 * Whether every cell of [rowRange] x [columnRange] that the lattice has faces away; a range reaching past the grid
 * keeps only its cells inside it.
 *
 * @param BooleanArray cells The cells' facing, in cell order.
 * @param IntRange rowRange The cell rows to read.
 * @param IntRange columnRange The cell columns to read.
 * @return Boolean True when every cell read faces away.
 */
private fun WarpLatticeLayout.everyCellBack(cells: BooleanArray, rowRange: IntRange, columnRange: IntRange): Boolean {
	for (row in rowRange) {
		if (row !in 0 until rows) {
			continue
		}

		for (column in columnRange) {
			if (column in 0 until columns && !cells[row * columns + column]) {
				return false
			}
		}
	}

	return true
}