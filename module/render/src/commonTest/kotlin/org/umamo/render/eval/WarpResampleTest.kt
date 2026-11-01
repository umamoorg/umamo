package org.umamo.render.eval

import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins lattice resampling: a whole-multiple refinement in quad mode reproduces the old map inside the lattice, the
 * corners and the far edges land where they were, a triangle-split resample stays close, every form a warp carries
 * is resampled, and an unchanged size is left alone.
 */
class WarpResampleTest {
	private val angle = ParameterId("ParamAngleX")

	/**
	 * A curved 2x2 lattice about 100 units across, bent by [bend].
	 *
	 * @param Float bend The curvature.
	 * @return FloatArray The nine control points.
	 */
	private fun curved(bend: Float): FloatArray =
		FloatArray(18) { componentIndex ->
			val pointIndex = componentIndex / 2
			val column = pointIndex % 3
			val row = pointIndex / 3
			if (componentIndex % 2 == 0) column * 50f + bend * sin(row * 1.3f) else row * 50f + bend * sin(column * 0.9f + 0.4f)
		}

	/**
	 * A 2x2 warp whose two-key grid holds a differently curved lattice in each cell, with a blend-shape form.
	 *
	 * @param Boolean quad Whether the lattice blends bilinearly.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(quad: Boolean): Deformer.Warp =
		Deformer.Warp(
			id = DeformerId("w"),
			name = "w",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = quad,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(angle, floatArrayOf(-1f, 1f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(curved(8f))), KeyformCell(intArrayOf(1), WarpLatticeForm(curved(-12f)))),
				),
			blendShapes = listOf(BlendShapeBinding(ParameterId("ParamSmile"), floatArrayOf(0f, 1f), 0, listOf(null, WarpForm(curved(20f))))),
		)

	/**
	 * The largest distance between two lattices' maps over a dense sample strictly inside them.
	 *
	 * @param FloatArray oldPoints The original control points.
	 * @param Int oldSide The original lattice's cells per side.
	 * @param FloatArray newPoints The resampled control points.
	 * @param Int newSide The resampled lattice's cells per side.
	 * @param Boolean quad Whether both blend bilinearly.
	 * @return Float The largest distance.
	 */
	private fun largestDeviation(oldPoints: FloatArray, oldSide: Int, newPoints: FloatArray, newSide: Int, quad: Boolean): Float {
		val before = FloatArray(2)
		val after = FloatArray(2)
		var largest = 0f

		for (rowSample in 0 until 37) {
			for (columnSample in 0 until 37) {
				val u = (columnSample + 0.5f) / 37f
				val v = (rowSample + 0.5f) / 37f
				warpApply(oldPoints, oldSide, oldSide, quad, u, v, before, 0)
				warpApply(newPoints, newSide, newSide, quad, u, v, after, 0)
				largest = max(largest, max(abs(before[0] - after[0]), abs(before[1] - after[1])))
			}
		}

		return largest
	}

	/** Refining a quad lattice by 2 and by 3 keeps its map inside the lattice, in every cell and the blend form. */
	@Test
	fun aWholeMultipleQuadRefinementKeepsTheMap() {
		val original = warp(quad = true)

		for (side in listOf(4, 6)) {
			val refined = original.resampledTo(side, side)

			assertTrue(original.isExactRefinementTo(side, side))
			assertEquals(side, refined.rows)
			assertEquals(side, refined.columns)

			for (cellIndex in 0 until 2) {
				val deviation =
					largestDeviation(original.geometryGrid!!.cells[cellIndex].form.controlPoints, 2, refined.geometryGrid!!.cells[cellIndex].form.controlPoints, side, quad = true)
				assertTrue(deviation < 1e-3f, "cell $cellIndex at $side x $side deviates by $deviation")
			}

			val blendDeviation = largestDeviation(original.blendShapes.single().forms[1]!!.controlPoints, 2, refined.blendShapes.single().forms[1]!!.controlPoints, side, quad = true)
			assertTrue(blendDeviation < 1e-3f, "the blend form at $side x $side deviates by $blendDeviation")
		}
	}

	/** The corners land on the old corners exactly, and the far edges' points on the old far edges. */
	@Test
	fun theCornersAndFarEdgesHold() {
		val original = warp(quad = true)
		val oldPoints = original.geometryGrid!!.cells[0].form.controlPoints
		val refined = original.resampledTo(3, 5).geometryGrid!!.cells[0].form.controlPoints
		val oldCorners = listOf(0, 2, 6, 8)
		val newCorners = listOf(0, 5, 18, 23)

		for ((oldCorner, newCorner) in oldCorners.zip(newCorners)) {
			assertEquals(oldPoints[oldCorner * 2], refined[newCorner * 2], 1e-4f, "corner x")
			assertEquals(oldPoints[oldCorner * 2 + 1], refined[newCorner * 2 + 1], 1e-4f, "corner y")
		}

		// The new right edge's middle point (row 1 of 3, column 5) lies on the old right edge at v = 1/3.
		val expected = FloatArray(2)
		latticePointAt(oldPoints, 2, 2, true, 1f, 1f / 3f, expected, 0)
		assertEquals(expected[0], refined[(1 * 6 + 5) * 2], 1e-4f)
		assertEquals(expected[1], refined[(1 * 6 + 5) * 2 + 1], 1e-4f)
	}

	/** A triangle-split lattice is never an exact refinement, but its resample stays within a small fraction of a cell. */
	@Test
	fun aTriangleSplitResampleStaysClose() {
		val original = warp(quad = false)
		val refined = original.resampledTo(4, 4)

		assertFalse(original.isExactRefinementTo(4, 4))
		val deviation = largestDeviation(original.geometryGrid!!.cells[0].form.controlPoints, 2, refined.geometryGrid!!.cells[0].form.controlPoints, 4, quad = false)
		assertTrue(deviation < 5f, "a 50-unit cell deviates by $deviation")
	}

	/** An unchanged or impossible size returns the warp itself. */
	@Test
	fun anUnchangedSizeIsLeftAlone() {
		val original = warp(quad = true)

		assertSame(original, original.resampledTo(2, 2))
		assertSame(original, original.resampledTo(0, 3))
		assertFalse(original.isExactRefinementTo(3, 4), "3 is not a multiple of 2")
		assertFalse(original.isExactRefinementTo(1, 2), "coarser is never exact")
	}
}