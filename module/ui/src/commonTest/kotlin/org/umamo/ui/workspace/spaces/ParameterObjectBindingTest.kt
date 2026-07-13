package org.umamo.ui.workspace.spaces

import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the effective object -> parameters relation behind the filter: a drawable's set unions its own
 * keyform axes with its whole parent deformer chain's, a deformer's unions its ancestors', and an empty
 * selection yields nothing.
 */
class ParameterObjectBindingTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val angleZ = ParameterId("ParamAngleZ")

	/** A warp deformer keyed on [paramId] (a 1x1 grid), nesting under [parent]. */
	private fun warp(id: String, parent: DeformerId?, paramId: ParameterId): Deformer.Warp =
		Deformer.Warp(
			id = DeformerId(id),
			name = id,
			parent = parent,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			keyforms = KeyformGrid(listOf(KeyformAxis(paramId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), WarpForm(floatArrayOf(0f, 0f))))),
		)

	/** A drawable under [parentDeformerId], keyed on [ownParamId] when non-null. */
	private fun drawable(id: String, parentDeformerId: DeformerId?, ownParamId: ParameterId?): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parentDeformerId,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms =
				ownParamId?.let {
					KeyformGrid(listOf(KeyformAxis(it, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), MeshForm(floatArrayOf(0f, 0f)))))
				},
		)

	/** A model holding [drawables] and [deformers]. */
	private fun model(drawables: List<Drawable>, deformers: List<Deformer>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = deformers,
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** A selected drawable's set unions its own axis with every parent deformer's up the chain. */
	@Test
	fun drawableInheritsParentDeformerChainAxes() {
		// Chain: warp1 (angleX) <- warp2 (angleY); drawable keyed on angleZ, deformed by warp2.
		val warp1 = warp("w1", parent = null, paramId = angleX)
		val warp2 = warp("w2", parent = warp1.id, paramId = angleY)
		val art = drawable("d", parentDeformerId = warp2.id, ownParamId = angleZ)
		val puppet = model(listOf(art), listOf(warp1, warp2))

		val result = effectiveParameterIds(puppet, Selection(setOf(SelectionTarget.Drawable(art.id))))
		assertEquals(setOf(angleX, angleY, angleZ), result)
	}

	/** A selected deformer's set unions its own axis with its ancestors'. */
	@Test
	fun deformerUnionsAncestors() {
		val warp1 = warp("w1", parent = null, paramId = angleX)
		val warp2 = warp("w2", parent = warp1.id, paramId = angleY)
		val puppet = model(emptyList(), listOf(warp1, warp2))

		val result = effectiveParameterIds(puppet, Selection(setOf(SelectionTarget.Deformer(warp2.id))))
		assertEquals(setOf(angleX, angleY), result, "the deformer contributes its own axis plus its parent's")
	}

	/** An empty selection affects no parameters. */
	@Test
	fun emptySelectionIsEmpty() {
		val puppet = model(listOf(drawable("d", parentDeformerId = null, ownParamId = angleX)), emptyList())
		assertTrue(effectiveParameterIds(puppet, Selection()).isEmpty())
	}
}
