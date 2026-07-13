package org.umamo.render.eval

import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Unit tests for the multilinear keyform blend on synthetic grids (no corpus): the snap/interpolate/
 * N-D/out-of-range behaviour of [gridCorners] and the cell reproduction of [sampleMeshLocal].
 */
class KeyformGridEvalTest {
	private val paramA = ParameterId("A")
	private val paramB = ParameterId("B")

	private fun values(vararg pairs: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val map = pairs.toMap()
		return { map[it] ?: 0f }
	}

	@Test
	fun snapsToTheNearestKey() {
		val grid = KeyformGrid<MeshForm>(listOf(KeyformAxis(paramA, floatArrayOf(-1f, 0f, 1f))), emptyList())
		val corners = gridCorners(grid, values(paramA to 0f))!!
		assertEquals(1, corners.size)
		assertEquals(1, corners[0].linearIndex) // the middle key
		assertEquals(1f, corners[0].weight)
	}

	@Test
	fun interpolatesBetweenTwoKeys() {
		val grid = KeyformGrid<MeshForm>(listOf(KeyformAxis(paramA, floatArrayOf(-1f, 0f, 1f))), emptyList())
		val corners = gridCorners(grid, values(paramA to 0.5f))!!.sortedBy { it.linearIndex }
		assertEquals(2, corners.size)
		assertEquals(1, corners[0].linearIndex)
		assertEquals(2, corners[1].linearIndex)
		assertEquals(0.5f, corners[0].weight)
		assertEquals(0.5f, corners[1].weight)
	}

	@Test
	fun foldsTwoAxesIntoFourWeightedCorners() {
		val axes = listOf(KeyformAxis(paramA, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(paramB, floatArrayOf(-1f, 0f, 1f)))
		val grid = KeyformGrid<MeshForm>(axes, emptyList())
		val corners = gridCorners(grid, values(paramA to 0.5f, paramB to 0.5f))!!
		assertEquals(4, corners.size)
		corners.forEach { assertEquals(0.25f, it.weight) }
		// coord (a,b) folds to a + b*3: the four cells surrounding (0.5, 0.5) are (1,1)(2,1)(1,2)(2,2).
		assertEquals(setOf(4, 5, 7, 8), corners.map { it.linearIndex }.toSet())
		assertEquals(1f, corners.fold(0f) { acc, c -> acc + c.weight })
	}

	@Test
	fun hidesWhenAParameterIsOutOfRange() {
		val grid = KeyformGrid<MeshForm>(listOf(KeyformAxis(paramA, floatArrayOf(-1f, 0f, 1f))), emptyList())
		assertNull(gridCorners(grid, values(paramA to -2f)))
	}

	@Test
	fun reproducesAndInterpolatesCellForms() {
		val cell0 = KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(10f, 0f)))
		val cell1 = KeyformCell(intArrayOf(1), MeshForm(floatArrayOf(20f, 0f)))
		val cell2 = KeyformCell(intArrayOf(2), MeshForm(floatArrayOf(30f, 0f)))
		val grid = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(-1f, 0f, 1f))), listOf(cell0, cell1, cell2))
		val base = floatArrayOf(0f, 0f)
		// At an exact key the output equals that cell's absolute form (base + delta).
		assertEquals(listOf(10f, 0f), sampleMeshLocal(grid, base, values(paramA to -1f))!!.toList())
		assertEquals(listOf(30f, 0f), sampleMeshLocal(grid, base, values(paramA to 1f))!!.toList())
		// Midway, the linear blend of the two bracketing forms.
		assertEquals(listOf(25f, 0f), sampleMeshLocal(grid, base, values(paramA to 0.5f))!!.toList())
	}

	@Test
	fun directMeshBlendsThenNegatesY() {
		val cell0 = KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(10f, 5f)))
		val cell1 = KeyformCell(intArrayOf(1), MeshForm(floatArrayOf(20f, 7f)))
		val grid = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(-1f, 1f))), listOf(cell0, cell1))
		val base = floatArrayOf(0f, 0f)
		// At a key: world = (formX, -formY) - only Y flips.
		assertEquals(listOf(10f, -5f), evalDirectMeshWorld(grid, base, values(paramA to -1f))!!.toList())
		// Midway: blend (x=15, y=6) then negate Y -> (15, -6).
		assertEquals(listOf(15f, -6f), evalDirectMeshWorld(grid, base, values(paramA to 0f))!!.toList())
	}

	@Test
	fun directMeshHiddenWhenOutOfRange() {
		val cell = KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(10f, 5f)))
		val grid = KeyformGrid(listOf(KeyformAxis(paramA, floatArrayOf(-1f, 1f))), listOf(cell))
		assertNull(evalDirectMeshWorld(grid, floatArrayOf(0f, 0f), values(paramA to 5f)))
	}
}
