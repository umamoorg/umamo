package org.umamo.edit

import org.umamo.runtime.eval.meshGridDefaultDeltas
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.deltasFromBase
import org.umamo.runtime.model.positionsFromDeltas
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the committed base move ([withMeshPositionsCommitted]) on a drawable under a warp: its base is the
 * canvas-space mesh while its keyforms are in the warp's 0..1 space, so a gesture's movement is far finer than
 * the base's float step.  The commit moves every keyform by the movement the gesture meant, stores each at a
 * float, leaves the blend shapes' contributions where they were, and leaves a move that went nowhere alone.
 */
class MeshBaseMoveCommitTest {
	private val drawableId = DrawableId("drawable")
	private val parameter = ParameterId("P")
	private val blendParameter = ParameterId("B")

	// Canvas pixels: the editable mesh.
	private val base = floatArrayOf(4070.4497f, 1649.7695f, 4102.375f, 1649.7695f, 4134.3f, 1612.6583f)

	// Keyforms in the parent warp's space.
	private val firstForm = floatArrayOf(0.6472907f, 0.36849776f, 0.65970415f, 0.36849776f, 0.6721175f, 0.36828208f)
	private val secondForm = floatArrayOf(0.721398f, 0.36849776f, 0.72146004f, 0.36849776f, 0.72152215f, 0.36828208f)
	private val blendForm = floatArrayOf(0.6502907f, 0.37049776f, 0.66270415f, 0.37049776f, 0.6751175f, 0.37028208f)

	/** The gridded, blend-shaped drawable under a warp. */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameter, "P", min = 0f, max = 1f, default = 0f), Parameter(blendParameter, "B", min = 0f, max = 1f, default = 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "drawable",
						parentDeformerId = DeformerId("warp"),
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh(base, FloatArray(base.size), intArrayOf(0, 1, 2)),
						geometryGrid =
							KeyformGrid(
								listOf(KeyformAxis(parameter, floatArrayOf(0f, 1f))),
								listOf(
									KeyformCell(intArrayOf(0), MeshDeltaForm(deltasFromBase(firstForm, base))),
									KeyformCell(intArrayOf(1), MeshDeltaForm(deltasFromBase(secondForm, base))),
								),
							),
						blendShapes = listOf(BlendShapeBinding(blendParameter, floatArrayOf(0f, 1f), 0, listOf(null, MeshForm(deltasFromBase(blendForm, base))))),
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * The drawable's keyforms as absolute positions, cell by cell.
	 *
	 * @param PuppetModel puppet The model.
	 * @return List<FloatArray> Each cell's absolute positions.
	 */
	private fun cellAbsolutes(puppet: PuppetModel): List<FloatArray> {
		val drawable = puppet.drawables.single()
		return drawable.geometryGrid!!.cells.map { cell -> positionsFromDeltas(drawable.mesh!!.positions, cell.form.positionDeltas) }
	}

	/**
	 * The drawable's blend contribution at full weight: the form minus the grid-at-default reference.
	 *
	 * @param PuppetModel puppet The model.
	 * @return DoubleArray The contribution per component.
	 */
	private fun blendContribution(puppet: PuppetModel): DoubleArray {
		val drawable = puppet.drawables.single()
		val reference = meshGridDefaultDeltas(drawable) { 0f }!!
		val form = drawable.blendShapes.single().forms[1]!!
		return DoubleArray(form.positionDeltas.size) { componentIndex -> form.positionDeltas[componentIndex] - reference[componentIndex] }
	}

	/**
	 * Every keyform moves by exactly the movement the gesture meant, stored at the float it rounds to - while the
	 * float base can only take the movement to the canvas magnitude's step, which is why the movement rides beside it.
	 */
	@Test
	fun theKeyformsMoveByTheIntendedMovement() {
		val start = model()
		val movement = DoubleArray(base.size) { componentIndex -> if (componentIndex % 2 == 0) 0.00031 else -0.00017 }
		val newBase = FloatArray(base.size) { componentIndex -> (base[componentIndex].toDouble() + movement[componentIndex]).toFloat() }
		val moved = start.withMeshPositionsCommitted(drawableId, newBase, movement)
		val before = cellAbsolutes(start)
		val after = cellAbsolutes(moved)
		for (cellIndex in before.indices) {
			val expected = FloatArray(base.size) { componentIndex -> (before[cellIndex][componentIndex].toDouble() + movement[componentIndex]).toFloat() }
			assertContentEquals(expected, after[cellIndex], "cell $cellIndex moved by the intended movement, to the float")
		}
		// Each stored keyform is a float: the deltas rebuild it exactly.
		val drawable = moved.drawables.single()
		for ((cellIndex, cell) in drawable.geometryGrid!!.cells.withIndex()) {
			assertContentEquals(deltasFromBase(after[cellIndex], newBase), cell.form.positionDeltas, "cell $cellIndex holds float-exact deltas")
		}
		// What the rounded base alone would have moved them by is off by the canvas step.
		val baseOnly = start.withMeshPositionsCommitted(drawableId, newBase)
		val worstBaseOnly =
			cellAbsolutes(baseOnly).indices.maxOf { cellIndex ->
				val expected = FloatArray(base.size) { componentIndex -> (before[cellIndex][componentIndex].toDouble() + movement[componentIndex]).toFloat() }
				expected.indices.maxOf { componentIndex -> abs(expected[componentIndex] - cellAbsolutes(baseOnly)[cellIndex][componentIndex]) }
			}
		assertTrue(worstBaseOnly > 1e-5f, "the base's own movement should show the rounding the intended movement avoids, got $worstBaseOnly")
	}

	/**
	 * The blend form moves with the grid, so its contribution - form minus the grid-at-default reference - stays
	 * where it was to the keyforms' own float precision.
	 */
	@Test
	fun theBlendContributionStays() {
		val start = model()
		val movement = DoubleArray(base.size) { 0.0042 }
		val newBase = FloatArray(base.size) { componentIndex -> (base[componentIndex].toDouble() + movement[componentIndex]).toFloat() }
		val before = blendContribution(start)
		val after = blendContribution(start.withMeshPositionsCommitted(drawableId, newBase, movement))
		val worst = before.indices.maxOf { componentIndex -> abs(before[componentIndex] - after[componentIndex]) }
		assertTrue(worst <= 1e-7, "the blend contribution moved by $worst")
	}

	/**
	 * A commit that moved nothing keeps the grid itself, so nothing rebuilds and every delta keeps its bits.
	 */
	@Test
	fun aMoveThatWentNowhereKeepsTheGrid() {
		val start = model()
		val unmoved = start.withMeshPositionsCommitted(drawableId, base.copyOf(), DoubleArray(base.size))
		assertSame(start.drawables.single().geometryGrid, unmoved.drawables.single().geometryGrid)
	}
}