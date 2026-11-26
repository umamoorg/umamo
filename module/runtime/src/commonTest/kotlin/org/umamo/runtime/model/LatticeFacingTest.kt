package org.umamo.runtime.model

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins which parts of a lattice face away from the viewer: a cell drawn against the grid's own order (columns along
 * +X, rows down -Z) faces away, but only in a lattice that carries depth; a point or grid line faces away when every
 * cell touching it does, and a cell seen edge-on never does.
 */
class LatticeFacingTest {
	/** Some depth, so the lattice is not flat. */
	private fun deep(pointCount: Int): LatticeDepth = LatticeDepth.PerPoint(FloatArray(pointCount) { pointIndex -> pointIndex + 1f })

	/**
	 * A lattice of [rows] x [columns] cells laid out the way a created warp is: columns along +X and rows down -Z, ten
	 * units apart, with column x positions [columnX] when given.
	 *
	 * @param WarpLatticeLayout layout The layout.
	 * @param FloatArray? columnX Each point column's world x, or null for ten units apart.
	 * @return FloatArray The world points, interleaved x, z.
	 */
	private fun gridWorld(layout: WarpLatticeLayout, columnX: FloatArray? = null): FloatArray {
		val world = FloatArray(layout.pointCount * 2)

		for (row in 0..layout.rows) {
			for (column in 0..layout.columns) {
				val pointIndex = layout.pointIndex(row, column)
				world[pointIndex * 2] = columnX?.get(column) ?: (column * 10f)
				world[pointIndex * 2 + 1] = -row * 10f
			}
		}

		return world
	}

	@Test
	fun aFlatLatticeHasNoBackEvenMirrored() {
		val layout = WarpLatticeLayout(2, 2)
		val mirrored = gridWorld(layout, floatArrayOf(0f, -10f, -20f))

		assertNull(layout.backFacingOf(mirrored, LatticeDepth.Flat), "a flat mirror is a mirror on purpose")
		assertNull(layout.backFacingOf(gridWorld(layout), deep(9)), "a deep lattice in the grid's own order faces the viewer")
	}

	@Test
	fun aDeepLatticeDrawnMirroredFacesAwayWhole() {
		val layout = WarpLatticeLayout(2, 3)
		val mirrored = gridWorld(layout, floatArrayOf(0f, -10f, -20f, -30f))

		val back = assertNotNull(layout.backFacingOf(mirrored, deep(layout.pointCount)))

		assertTrue(back.cells.all { cellBack -> cellBack })
		assertTrue(back.points.all { pointBack -> pointBack })
		assertTrue(back.edges.all { edgeBack -> edgeBack })
		assertContentEquals(BooleanArray(layout.edgeEndpoints.size / 2) { true }, back.edges, "one per grid line")
	}

	/**
	 * A sheet wrapped once round a cylinder about Z: column k at angle -pi + k pi/4 sits at x = R sin(phi), depth
	 * R(1 - cos(phi)).  The cells past a quarter turn either way (0, 1, 6, 7) face away; the rim columns (2 and 6), each
	 * between a front and a back cell, stay front; and each grid line follows the cells beside it.
	 */
	@Test
	fun aWholeTurnCylinderFacesAwayPastTheRim() {
		val radius = 50f
		val layout = WarpLatticeLayout(1, 8)
		val angles = FloatArray(9) { column -> (-PI + column * PI / 4).toFloat() }
		val world = gridWorld(layout, FloatArray(9) { column -> radius * sin(angles[column]) })
		val depth = LatticeDepth.PerPoint(FloatArray(layout.pointCount) { pointIndex -> radius * (1f - cos(angles[pointIndex % 9])) })

		val back = assertNotNull(layout.backFacingOf(world, depth))

		assertContentEquals(booleanArrayOf(true, true, false, false, false, false, true, true), back.cells)
		val backColumns = booleanArrayOf(true, true, false, false, false, false, false, true, true)
		assertContentEquals(BooleanArray(layout.pointCount) { pointIndex -> backColumns[pointIndex % 9] }, back.points, "a rim point stays front")

		// Each row's segments follow their one cell; each vertical line follows its point column.
		val expectedEdges = BooleanArray(8) { column -> back.cells[column] } + BooleanArray(8) { column -> back.cells[column] } + backColumns
		assertContentEquals(expectedEdges, back.edges)
	}

	@Test
	fun aCellSeenEdgeOnIsNotBack() {
		val layout = WarpLatticeLayout(1, 1)
		// Every corner on one line: the cell is seen exactly edge-on.
		val edgeOn = floatArrayOf(0f, 0f, 10f, 0f, 0f, 0f, 10f, 0f)

		assertNull(layout.backFacingOf(edgeOn, deep(4)))
	}

	@Test
	fun everyGridLineFindsItsOrdinalFromItsEndpoints() {
		val layout = WarpLatticeLayout(2, 3)
		val endpoints = layout.edgeEndpoints

		for (edgeIndex in 0 until endpoints.size / 2) {
			val first = endpoints[edgeIndex * 2]
			val second = endpoints[edgeIndex * 2 + 1]
			assertEquals(edgeIndex, layout.edgeOrdinal(first, second), "edge $first-$second")
			assertEquals(edgeIndex, layout.edgeOrdinal(second, first), "either order")
		}

		assertNull(layout.edgeOrdinal(3, 4), "the end of one row and the start of the next are not neighbors")
		assertNull(layout.edgeOrdinal(0, 5), "nor are diagonal points")
	}
}