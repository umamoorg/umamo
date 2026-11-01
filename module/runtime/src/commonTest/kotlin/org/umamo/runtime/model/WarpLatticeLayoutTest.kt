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

	/** The outline lists every border point once, the four corners first and in ring order around the lattice. */
	@Test
	fun theOutlineListsTheBorderPointsCornersFirst() {
		for ((rows, columns) in listOf(1 to 1, 2 to 3, 5 to 5, 1 to 4)) {
			val layout = WarpLatticeLayout(rows, columns)
			val outline = layout.outlinePointIndices
			val border =
				(0 until layout.pointCount).filter { pointIndex ->
					val row = pointIndex / layout.pointsPerRow
					val column = pointIndex % layout.pointsPerRow
					row == 0 || row == rows || column == 0 || column == columns
				}

			assertEquals(2 * (rows + columns), outline.size, "$rows x $columns")
			assertEquals(border.toSet(), outline.toSet(), "every border point once in $rows x $columns")
			assertEquals(outline.size, outline.toSet().size, "no point twice in $rows x $columns")
			assertContentEquals(
				intArrayOf(layout.pointIndex(0, 0), layout.pointIndex(0, columns), layout.pointIndex(rows, columns), layout.pointIndex(rows, 0)),
				outline.copyOfRange(0, 4),
				"corners first in $rows x $columns",
			)
		}
	}

	/** The outline's edges close one ring around the border: each joins two grid neighbors on a side, and none is inside. */
	@Test
	fun theOutlineEdgesRingTheBorder() {
		for ((rows, columns) in listOf(1 to 1, 2 to 3, 5 to 5, 1 to 4)) {
			val layout = WarpLatticeLayout(rows, columns)
			val outline = layout.outlinePointIndices
			val edges = layout.outlineEdgeEndpoints.toList().chunked(2)
			val gridLines = layout.edgeEndpoints.toList().chunked(2).map { (low, high) -> setOf(low, high) }.toSet()

			assertEquals(outline.size, edges.size, "$rows x $columns")

			for ((slotA, slotB) in edges) {
				assertTrue(setOf(outline[slotA], outline[slotB]) in gridLines, "edge $slotA-$slotB is a grid line in $rows x $columns")
			}

			// Each slot is an endpoint of exactly two edges: one closed ring, no branch and no gap.
			val degrees = IntArray(outline.size)

			for ((slotA, slotB) in edges) {
				degrees[slotA]++
				degrees[slotB]++
			}

			assertTrue(degrees.all { degree -> degree == 2 }, "one closed ring in $rows x $columns")
		}
	}

	/** A 1x1 lattice's outline is its four corners and the four sides between them. */
	@Test
	fun aSingleCellOutlineIsItsFourSides() {
		val layout = WarpLatticeLayout(rows = 1, columns = 1)

		assertContentEquals(intArrayOf(0, 1, 3, 2), layout.outlinePointIndices)
		assertContentEquals(intArrayOf(0, 1, 1, 2, 2, 3, 3, 0), layout.outlineEdgeEndpoints)
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