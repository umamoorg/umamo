package org.umamo.render.puppet

import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins [keyformCellCount] and [buildDeltaTexels] - the morph's Δ table layout.  This is the data the
 * vertex shader texel-fetches per active corner, so a layout slip mis-deforms every mesh; until now it
 * was reachable only through the corpus-gated GPU test, which does not run in CI.
 */
class DeltaTexelsTest {
	private val paramA = ParameterId("A")
	private val paramB = ParameterId("B")

	/** Reads texel (vertex, cell) out of the row-major RG buffer. */
	private fun texelAt(texels: FloatArray, cellCount: Int, vertexIndex: Int, cellIndex: Int): Pair<Float, Float> {
		val at = (vertexIndex * cellCount + cellIndex) * 2
		return texels[at] to texels[at + 1]
	}

	@Test
	fun keyformCellCountIsTheProductOfAxisKeyCounts() {
		val grid =
			KeyformGrid<MeshForm>(
				axes = listOf(KeyformAxis(paramA, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(paramB, floatArrayOf(0f, 1f))),
				cells = emptyList(),
			)
		assertEquals(6, keyformCellCount(grid), "3 keys x 2 keys = 6 cells")
	}

	@Test
	fun keyformCellCountFloorsAtOneForAnAxislessGrid() {
		val grid = KeyformGrid<MeshForm>(axes = emptyList(), cells = emptyList())
		assertEquals(1, keyformCellCount(grid), "an axis-less grid still has its single rest cell")
	}

	@Test
	fun buildDeltaTexelsPlacesEachCellInItsLinearColumn() {
		// Two vertices, two cells along one axis. Cell 0 shifts vertex 1 only; cell 1 shifts vertex 0 only,
		// so a row/column transpose or an off-by-one column would be caught.
		val grid =
			KeyformGrid(
				axes = listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
				cells =
					listOf(
						KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(0f, 0f, 5f, 6f))),
						KeyformCell(intArrayOf(1), MeshForm(floatArrayOf(7f, 8f, 0f, 0f))),
					),
			)
		val texels = buildDeltaTexels(grid, vertexCount = 2, cellCount = 2)
		assertEquals(2 * 2 * 2, texels.size, "vertexCount * cellCount * 2")
		assertEquals(0f to 0f, texelAt(texels, 2, vertexIndex = 0, cellIndex = 0))
		assertEquals(7f to 8f, texelAt(texels, 2, vertexIndex = 0, cellIndex = 1))
		assertEquals(5f to 6f, texelAt(texels, 2, vertexIndex = 1, cellIndex = 0))
		assertEquals(0f to 0f, texelAt(texels, 2, vertexIndex = 1, cellIndex = 1))
	}

	@Test
	fun buildDeltaTexelsUsesTheAxisMajorLinearIndex() {
		// Strides run axis 0 fastest (stride 1), so coordinate (0,1) on a 2x2 grid is linear index 2 -
		// the same indexing WeightedCell.linearIndex carries into the shader. A stride flip lands it at 1.
		val grid =
			KeyformGrid(
				axes = listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f)), KeyformAxis(paramB, floatArrayOf(0f, 1f))),
				cells = listOf(KeyformCell(intArrayOf(0, 1), MeshForm(floatArrayOf(9f, 9f)))),
			)
		val texels = buildDeltaTexels(grid, vertexCount = 1, cellCount = 4)
		assertEquals(9f to 9f, texelAt(texels, 4, vertexIndex = 0, cellIndex = 2), "coordinate (0,1) lands in column 2")
		assertEquals(0f to 0f, texelAt(texels, 4, vertexIndex = 0, cellIndex = 1), "column 1 is coordinate (1,0), unkeyed here")
	}

	@Test
	fun buildDeltaTexelsZeroFillsAMissingCell() {
		// A grid whose cells do not cover every combination: the absent cell means no offset from rest.
		val grid =
			KeyformGrid(
				axes = listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
				cells = listOf(KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(3f, 4f)))),
			)
		val texels = buildDeltaTexels(grid, vertexCount = 1, cellCount = 2)
		assertEquals(3f to 4f, texelAt(texels, 2, 0, 0))
		assertEquals(0f to 0f, texelAt(texels, 2, 0, 1), "the unkeyed cell contributes no delta")
	}

	@Test
	fun buildDeltaTexelsZeroFillsWhenTheDeltaArrayIsShorterThanTheMesh() {
		// Reachable in real models: the form carries fewer vertices than the mesh. The short tail reads as
		// rest rather than running off the end.
		val grid =
			KeyformGrid(
				axes = listOf(KeyformAxis(paramA, floatArrayOf(0f))),
				cells = listOf(KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(1f, 2f)))),
			)
		val texels = buildDeltaTexels(grid, vertexCount = 3, cellCount = 1)
		assertEquals(1f to 2f, texelAt(texels, 1, vertexIndex = 0, cellIndex = 0))
		assertEquals(0f to 0f, texelAt(texels, 1, vertexIndex = 1, cellIndex = 0), "vertex past the delta array reads rest")
		assertEquals(0f to 0f, texelAt(texels, 1, vertexIndex = 2, cellIndex = 0))
	}
}
