package org.umamo.render.puppet

import org.umamo.render.eval.MeshBlendContribution
import org.umamo.render.eval.MeshBlendState
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.WarpWorld
import org.umamo.render.eval.rotationXform
import org.umamo.render.eval.warpApply
import org.umamo.runtime.eval.WeightedCell
import org.umamo.runtime.eval.cellsByLinearIndex
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins [deformedWorldBounds] - the exact deformed-world AABB the composite scissor is built from.
 * The contract is that the bound equals the true deformed geometry (min/max of the exact deform), so
 * the scissor never clips: every case here rebuilds the deform independently and asserts equality,
 * including a warp vertex OUTSIDE the lattice (the extrapolation case a cheap analytic bound
 * undersized, clipping heads and arm tips).  Pure math, no GPU.
 */
class PosedBoundsTest {
	private val paramA = ParameterId("A")

	/** A one-cell grid over [base]'s vertex count, whose single cell holds [deltas]. */
	private fun oneCellCells(deltas: FloatArray): Map<Int, KeyformCell<MeshForm>> {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0f))),
				listOf(KeyformCell(intArrayOf(0), MeshForm(deltas))),
			)
		return cellsByLinearIndex(grid)
	}

	private val fullCorner = listOf(WeightedCell(0, 1f))

	@Test
	fun directMeshBlendsCornersAndNegatesY() {
		// base (1,2)(3,5) + cell delta (10,0)(0,0) -> local (11,2)(3,5) -> Y-negate (11,-2)(3,-5).
		val base = floatArrayOf(1f, 2f, 3f, 5f)
		val cells = oneCellCells(floatArrayOf(10f, 0f, 0f, 0f))
		val bounds = deformedWorldBounds(base, cells, fullCorner, parentWorld = null, blend = null)!!
		assertEquals(3f, bounds.minX)
		assertEquals(11f, bounds.maxX)
		assertEquals(-5f, bounds.minY)
		assertEquals(-2f, bounds.maxY)
	}

	@Test
	fun blendShapeDeltasWidenTheBound() {
		// One vertex, zero grid delta, a blend contribution of (+4, +6) at weight 0.5 relative to a
		// (+1, +2) reference -> local (0,0) + 0.5*((4,6)-(1,2)) = (1.5, 2) -> Y-negate (1.5, -2).
		val base = floatArrayOf(0f, 0f)
		val cells = oneCellCells(floatArrayOf(0f, 0f))
		val blend =
			MeshBlendState(
				contributions = listOf(MeshBlendContribution(bindingIndex = 0, keyIndex = 0, form = MeshForm(floatArrayOf(4f, 6f)), weight = 0.5f)),
				referenceDeltas = floatArrayOf(1f, 2f),
				referenceDrawOrder = 0f,
				referenceOpacity = 1f,
			)
		val bounds = deformedWorldBounds(base, cells, fullCorner, parentWorld = null, blend = blend)!!
		assertClose(1.5f, bounds.minX)
		assertClose(1.5f, bounds.maxX)
		assertClose(-2f, bounds.minY)
		assertClose(-2f, bounds.maxY)
	}

	@Test
	fun rotationParentTransformsExactly() {
		// A 90-degree rotation about the origin maps (x, y) -> (-y, x) before the Y-negate; a box
		// [1,3]x[0,2] -> corners approx (0,1)(0,3)(-2,1)(-2,3) -> x[-2,0], y[1,3] -> Y-negate y[-3,-1].
		val rotation = RotationWorld(rotationXform(90f, 1f, flipX = false, flipY = false, ox = 0f, oy = 0f), accY = 1f)
		val base = floatArrayOf(1f, 0f, 3f, 0f, 1f, 2f, 3f, 2f)
		val cells = oneCellCells(FloatArray(8))
		val bounds = deformedWorldBounds(base, cells, fullCorner, parentWorld = rotation, blend = null)!!
		assertClose(-2f, bounds.minX)
		assertClose(0f, bounds.maxX)
		assertClose(-3f, bounds.minY)
		assertClose(-1f, bounds.maxY)
	}

	@Test
	fun warpParentInGridMatchesTheLattice() {
		// A 1x1 bilinear lattice mapping the unit square to world [10,20]x[30,50]; the unit-square mesh
		// recovers that rect, Y-negated.
		val warp = WarpWorld(floatArrayOf(10f, 30f, 20f, 30f, 10f, 50f, 20f, 50f), cols = 1, rows = 1, bilinear = true, accY = 1f)
		val base = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)
		val cells = oneCellCells(FloatArray(8))
		val bounds = deformedWorldBounds(base, cells, fullCorner, parentWorld = warp, blend = null)!!
		assertClose(10f, bounds.minX)
		assertClose(20f, bounds.maxX)
		assertClose(-50f, bounds.minY)
		assertClose(-30f, bounds.maxY)
	}

	@Test
	fun warpParentExtrapolatedVertexMatchesWarpApply() {
		// The case a cheap analytic bound got wrong: a vertex OUTSIDE the lattice (u = 1.5).  The exact
		// bound must equal warpApply itself - not an undersized approximation that would clip the vertex.
		val cp = floatArrayOf(10f, 30f, 20f, 30f, 10f, 50f, 20f, 50f)
		val warp = WarpWorld(cp, cols = 1, rows = 1, bilinear = true, accY = 1f)
		val expected = FloatArray(2)
		warpApply(cp, 1, 1, true, 1.5f, 0.5f, expected, 0)
		val bounds = deformedWorldBounds(floatArrayOf(1.5f, 0.5f), oneCellCells(floatArrayOf(0f, 0f)), fullCorner, parentWorld = warp, blend = null)!!
		// One vertex, so min == max == the exact warp image, Y-negated.
		assertClose(expected[0], bounds.minX)
		assertClose(expected[0], bounds.maxX)
		assertClose(-expected[1], bounds.minY)
		assertClose(-expected[1], bounds.maxY)
	}

	@Test
	fun unionBoundsCoversBoth() {
		val union = unionBounds(PosedAabb(0f, 0f, 2f, 2f), PosedAabb(-1f, 3f, 1f, 5f))
		assertEquals(-1f, union.minX)
		assertEquals(0f, union.minY)
		assertEquals(2f, union.maxX)
		assertEquals(5f, union.maxY)
	}

	private fun assertClose(expected: Float, actual: Float) {
		assertTrue(abs(expected - actual) < 1e-3f, "expected ~$expected, got $actual")
	}
}
