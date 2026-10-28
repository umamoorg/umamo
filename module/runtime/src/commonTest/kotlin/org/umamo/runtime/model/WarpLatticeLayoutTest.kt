package org.umamo.runtime.model

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the warp lattice's layout: the point indexing every form and the evaluator share, the grid lines, the
 * quad cells, and the connectivity triangles, which split each cell on the evaluator's triangle diagonal.
 */
class WarpLatticeLayoutTest {
	/** Points run row-major with the column fastest, as the evaluator's cell index assumes. */
	@Test
	fun pointsRunRowMajorWithTheColumnFastest() {
		val layout = WarpLatticeLayout(rows = 2, columns = 3)

		assertEquals(12, layout.pointCount)
		assertEquals(0, layout.pointIndex(0, 0))
		assertEquals(3, layout.pointIndex(0, 3))
		assertEquals(4, layout.pointIndex(1, 0))
		assertEquals(11, layout.pointIndex(2, 3))
	}

	/** Every grid line appears once, lower index first, and no edge crosses a cell diagonally. */
	@Test
	fun theEdgesAreTheGridLinesOnly() {
		for ((rows, columns) in listOf(1 to 1, 2 to 3, 5 to 5)) {
			val layout = WarpLatticeLayout(rows, columns)
			val edges = layout.edgeEndpoints.toList().chunked(2)

			assertEquals((rows + 1) * columns + rows * (columns + 1), edges.size, "$rows x $columns")
			assertEquals(edges.size, edges.toSet().size, "no edge twice in $rows x $columns")

			for ((low, high) in edges) {
				assertTrue(low < high, "lower index first")
				val sameRow = low / layout.pointsPerRow == high / layout.pointsPerRow && high - low == 1
				val sameColumn = high - low == layout.pointsPerRow
				assertTrue(sameRow || sameColumn, "edge $low-$high is a grid line in $rows x $columns")
			}
		}
	}

	/** A 1x1 lattice is one cell around its four points, split on the evaluator's diagonal (top right to bottom left). */
	@Test
	fun aSingleCellHasFourCornersAndTwoTriangles() {
		val layout = WarpLatticeLayout(rows = 1, columns = 1)

		assertContentEquals(intArrayOf(0, 1, 3, 2), layout.cellCorners)
		assertContentEquals(intArrayOf(0, 1, 2, 1, 3, 2), layout.cellTriangles)
	}

	/** Cells and triangles come in cell order, one quad and two triangles per cell. */
	@Test
	fun cellsAndTrianglesCoverTheLattice() {
		val layout = WarpLatticeLayout(rows = 2, columns = 3)

		assertEquals(6, layout.cellCount)
		assertEquals(24, layout.cellCorners.size)
		assertEquals(36, layout.cellTriangles.size)
		// The second row's first cell starts at point (1, 0).
		assertContentEquals(intArrayOf(4, 5, 9, 8), layout.cellCorners.copyOfRange(12, 16))
	}
}