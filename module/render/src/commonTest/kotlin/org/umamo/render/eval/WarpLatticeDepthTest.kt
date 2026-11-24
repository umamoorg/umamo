package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.LatticeDepth
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Pins a warp lattice's depth through the evaluator: the lattice at a pose carries the grid's depth blended at that
 * pose, or a pending lattice's own, and a resample carries depth along the old map, the corners kept exactly.
 */
class WarpLatticeDepthTest {
	private val angle = ParameterId("ParamAngleX")
	private val warpId = DeformerId("w")

	/** A 1x1 lattice 10 units across. */
	private val square = floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f, 10f, 10f)

	/** Depths for the square's four corners. */
	private val cornerDepths = floatArrayOf(0f, 10f, 20f, 30f)

	/**
	 * A root 1x1 warp keyed on the angle at -1 (flat) and 1 (deep at [cornerDepths]).
	 *
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(): Deformer.Warp =
		Deformer.Warp(
			id = warpId,
			name = "w",
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(angle, floatArrayOf(-1f, 1f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(square)), KeyformCell(intArrayOf(1), WarpLatticeForm(square, LatticeDepth.PerPoint(cornerDepths)))),
				),
		)

	/**
	 * The rig: the one warp, the angle resting at 0.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(angle, "X", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp()),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** Between the keys the lattice's depth is the grid's blend; a pending lattice stands in with its own depth. */
	@Test
	fun theLatticeAtAPoseCarriesTheGridsDepth() {
		val model = model()
		val halfway = assertNotNull(warpLatticeAt(model, emptyMap(), warpId))
		val pending = WarpLatticeForm(square, LatticeDepth.PerPoint(floatArrayOf(7f, 7f, 7f, 7f)))
		val overridden = assertNotNull(warpLatticeAt(model, emptyMap(), warpId, mapOf(warpId to pending)))

		assertContentEquals(floatArrayOf(0f, 5f, 10f, 15f), assertIs<LatticeDepth.PerPoint>(halfway.gridDepth).values)
		assertSame(LatticeDepth.Flat, assertNotNull(warpLatticeAt(model, mapOf(angle to -1f), warpId)).gridDepth)
		assertSame(pending.depth, overridden.gridDepth)
	}

	/** Refining a quad lattice keeps each old corner's depth exactly and lays the new points along the bilinear map. */
	@Test
	fun aResampleCarriesDepthAlongTheMap() {
		val refined = warp().resampledTo(2, 2)
		val deep = assertIs<LatticeDepth.PerPoint>(refined.geometryGrid!!.cells[1].form.depth)

		assertEquals(9, deep.values.size)
		assertContentEquals(cornerDepths, floatArrayOf(deep.values[0], deep.values[2], deep.values[6], deep.values[8]))
		assertEquals(5f, deep.values[1], 1e-5f, "the top edge's midpoint")
		assertEquals(10f, deep.values[3], 1e-5f, "the left edge's midpoint")
		assertEquals(15f, deep.values[4], 1e-5f, "the middle")
		assertSame(LatticeDepth.Flat, refined.geometryGrid!!.cells[0].form.depth, "a flat cell stays flat")
	}
}