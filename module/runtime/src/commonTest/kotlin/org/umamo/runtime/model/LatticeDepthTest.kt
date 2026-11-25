package org.umamo.runtime.model

import org.umamo.runtime.eval.warpDepthAt
import org.umamo.runtime.keyform.CompactionResult
import org.umamo.runtime.keyform.WarpLatticeInterpolator
import org.umamo.runtime.keyform.compacted
import org.umamo.runtime.keyform.withLatticeShifted
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins a warp lattice's depth through the model and the grid algebra: an all-zero depth is flat, flat reads as 0
 * everywhere, a depth counts in exact form equality, the interpolator and the posed blend carry it beside the points,
 * compaction never merges keys that differ only in depth, and the whole-lattice walk keeps it or shifts it alike.
 */
class LatticeDepthTest {
	private val angle = ParameterId("ParamAngleX")

	/** The four control points of a 1x1 lattice. */
	private val square = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)

	/**
	 * A 1x1 warp keyed on [keys] of the angle, one form per key.
	 *
	 * @param FloatArray keys The axis's keys.
	 * @param List<WarpLatticeForm> forms One form per key.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(keys: FloatArray, forms: List<WarpLatticeForm>): Deformer.Warp =
		Deformer.Warp(
			id = DeformerId("w"),
			name = "w",
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid = KeyformGrid(listOf(KeyformAxis(angle, keys)), forms.mapIndexed { keyIndex, form -> KeyformCell(intArrayOf(keyIndex), form) }),
		)

	/** Values that are all 0 make a flat depth, and a flat depth or a short one reads 0 where it holds nothing. */
	@Test
	fun anAllZeroDepthIsFlatAndFlatReadsAsZero() {
		val depth = LatticeDepth.PerPoint(floatArrayOf(1f, 2f))

		assertSame(LatticeDepth.Flat, latticeDepthOf(floatArrayOf(0f, 0f, 0f)))
		assertIs<LatticeDepth.PerPoint>(latticeDepthOf(floatArrayOf(0f, 2f)))
		assertEquals(0f, LatticeDepth.Flat.depthAt(3))
		assertEquals(0f, depth.depthAt(5), "past the stored values")
		assertContentEquals(floatArrayOf(1f, 2f, 0f), depth.valuesFor(3))
	}

	/** Flat equals all 0, and two forms that differ only in depth are not exactly equal. */
	@Test
	fun depthCountsInExactEquality() {
		val flat = WarpLatticeForm(square)
		val deep = WarpLatticeForm(square, LatticeDepth.PerPoint(floatArrayOf(1f, 0f, 0f, 0f)))

		assertTrue(latticeDepthsEqual(LatticeDepth.Flat, LatticeDepth.PerPoint(FloatArray(4)), 4))
		assertFalse(WarpLatticeInterpolator.isExactlyEqual(flat, deep))
		assertTrue(WarpLatticeInterpolator.isExactlyEqual(deep, WarpLatticeForm(square.copyOf(), LatticeDepth.PerPoint(floatArrayOf(1f, 0f, 0f, 0f)))))
	}

	/** Two flat keys blend to flat, and a flat key against a deep one blends point by point. */
	@Test
	fun theInterpolatorBlendsDepthBesideThePoints() {
		val flat = WarpLatticeForm(square)
		val deep = WarpLatticeForm(square, LatticeDepth.PerPoint(floatArrayOf(4f, 8f, 0f, -4f)))

		assertSame(LatticeDepth.Flat, WarpLatticeInterpolator.interpolate(flat, flat, 0.5f).depth)
		assertContentEquals(floatArrayOf(1f, 2f, 0f, -1f), (WarpLatticeInterpolator.interpolate(flat, deep, 0.25f).depth as LatticeDepth.PerPoint).values)
		assertContentEquals(square, WarpLatticeInterpolator.interpolate(flat, deep, 0.25f).controlPoints)
	}

	/** At a pose the depth blends with the grid's own weights; a pose on a flat key is flat, and no grid has none. */
	@Test
	fun thePosedDepthBlendsTheGridLikeThePoints() {
		val grid = warp(floatArrayOf(-1f, 1f), listOf(WarpLatticeForm(square), WarpLatticeForm(square, LatticeDepth.PerPoint(floatArrayOf(10f, 20f, 30f, 40f))))).geometryGrid

		assertContentEquals(floatArrayOf(5f, 10f, 15f, 20f), (warpDepthAt(grid) { 0f } as LatticeDepth.PerPoint).values)
		assertSame(LatticeDepth.Flat, warpDepthAt(grid) { -1f })
		assertNull(warpDepthAt(grid) { 2f }, "out of the grid's range")
		assertNull(warpDepthAt(null) { 0f })
	}

	/** Three keys holding one lattice collapse to a constant when flat, and stay keyed when the middle one is deep. */
	@Test
	fun compactionNeverMergesKeysThatDifferOnlyInDepth() {
		val keys = floatArrayOf(-1f, 0f, 1f)
		val flat = warp(keys, listOf(WarpLatticeForm(square), WarpLatticeForm(square), WarpLatticeForm(square)))
		val deepMiddle = warp(keys, listOf(WarpLatticeForm(square), WarpLatticeForm(square, LatticeDepth.PerPoint(floatArrayOf(3f, 3f, 3f, 3f))), WarpLatticeForm(square)))

		assertIs<CompactionResult.Constant<WarpLatticeForm>>(flat.geometryGrid!!.compacted(WarpLatticeInterpolator))
		val reduced = assertIs<CompactionResult.Reduced<WarpLatticeForm>>(deepMiddle.geometryGrid!!.compacted(WarpLatticeInterpolator))
		assertEquals(3, reduced.grid.axes.single().keys.size)
	}

	/** Shifting the whole lattice keeps every grid cell's depth as it was. */
	@Test
	fun theWholeLatticeWalkCarriesDepth() {
		val depth = LatticeDepth.PerPoint(floatArrayOf(1f, 2f, 3f, 4f))
		val shifted = assertNotNull(warp(floatArrayOf(-1f, 1f), listOf(WarpLatticeForm(square, depth), WarpLatticeForm(square))).withLatticeShifted(FloatArray(8) { 5f }).geometryGrid)

		assertSame(depth, shifted.cells[0].form.depth)
		assertSame(LatticeDepth.Flat, shifted.cells[1].form.depth)
		assertEquals(5f, shifted.cells[0].form.controlPoints[0])
	}

	/** A depth shift moves every grid cell's depth alike, a flat cell included, and one that cancels out leaves flat. */
	@Test
	fun aDepthShiftMovesEveryCellAlike() {
		val depth = LatticeDepth.PerPoint(floatArrayOf(1f, 2f, 3f, 4f))
		val original = warp(floatArrayOf(-1f, 1f), listOf(WarpLatticeForm(square, depth), WarpLatticeForm(square)))

		val shifted = assertNotNull(original.withLatticeShifted(FloatArray(8), LatticeDepth.PerPoint(floatArrayOf(-1f, -2f, -3f, -4f))).geometryGrid)

		assertSame(LatticeDepth.Flat, shifted.cells[0].form.depth, "cancelled back onto the art plane")
		assertContentEquals(floatArrayOf(-1f, -2f, -3f, -4f), assertIs<LatticeDepth.PerPoint>(shifted.cells[1].form.depth).values)
	}
}