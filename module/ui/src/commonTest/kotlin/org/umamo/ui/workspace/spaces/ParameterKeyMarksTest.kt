package org.umamo.ui.workspace.spaces

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
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
import kotlin.test.assertNull

/**
 * Pins the per-parameter key-mark index feeding the sliders: keyform-grid axis keys become circle
 * marks and blend-shape binding keys become square marks; each parameter's list is the sorted,
 * deduplicated union across every object, and an unkeyed parameter has no entry.
 */
class ParameterKeyMarksTest {
	private val angleX = ParameterId("ParamAngleX")
	private val shrink = ParameterId("ParamShrink")
	private val unused = ParameterId("ParamUnused")

	/** A drawable keyed on [paramId]'s grid at [keys] (a single-axis grid, one cell per key). */
	private fun drawableGrid(id: String, paramId: ParameterId, keys: FloatArray): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms =
				KeyformGrid(
					listOf(KeyformAxis(paramId, keys)),
					keys.indices.map { keyIndex -> KeyformCell(intArrayOf(keyIndex), MeshForm(floatArrayOf(0f, 0f))) },
				),
		)

	/** A bare drawable carrying [bindings] as its blend shapes (no grid). */
	private fun drawableBlend(id: String, bindings: List<BlendShapeBinding<MeshForm>>): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms = null,
		).copy(blendShapes = bindings)

	/** A warp deformer carrying [bindings] as its blend shapes (no grid). */
	private fun warpBlend(id: String, bindings: List<BlendShapeBinding<WarpForm>>): Deformer.Warp =
		Deformer.Warp(
			id = DeformerId(id),
			name = id,
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			keyforms = null,
		).copy(blendShapes = bindings)

	private fun meshBinding(parameterId: ParameterId, keys: FloatArray, neutralIndex: Int): BlendShapeBinding<MeshForm> =
		BlendShapeBinding(
			parameterId = parameterId,
			keys = keys,
			neutralIndex = neutralIndex,
			forms = keys.indices.map { if (it == neutralIndex) null else MeshForm(floatArrayOf(0f, 0f)) },
		)

	private fun warpBinding(parameterId: ParameterId, keys: FloatArray, neutralIndex: Int): BlendShapeBinding<WarpForm> =
		BlendShapeBinding(
			parameterId = parameterId,
			keys = keys,
			neutralIndex = neutralIndex,
			forms = keys.indices.map { if (it == neutralIndex) null else WarpForm(floatArrayOf(0f, 0f)) },
		)

	private fun model(drawables: List<Drawable>, deformers: List<Deformer>): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = deformers,
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** Grid axes on the same parameter across objects union into one sorted, deduplicated circle list. */
	@Test
	fun gridKeysUnionSortedAndDeduped() {
		val puppet =
			model(
				drawables =
					listOf(
						drawableGrid("d1", angleX, floatArrayOf(0f, 1f)),
						drawableGrid("d2", angleX, floatArrayOf(-1f, 0f, 1f)),
					),
				deformers = emptyList(),
			)

		val marks = puppet.parameterKeyMarks()
		assertEquals(listOf(-1f, 0f, 1f), marks[angleX]?.gridKeys, "grid keys are the sorted union across objects")
		assertEquals(emptyList<Float>(), marks[angleX]?.blendKeys, "a key-form parameter has no square keys")
	}

	/** Blend bindings on the same parameter across a drawable and a deformer union into one square list. */
	@Test
	fun blendKeysGatherFromDrawablesAndDeformers() {
		val puppet =
			model(
				drawables = listOf(drawableBlend("d", listOf(meshBinding(shrink, floatArrayOf(-1f, 0f), neutralIndex = 1)))),
				deformers = listOf(warpBlend("w", listOf(warpBinding(shrink, floatArrayOf(0f, 1f), neutralIndex = 0)))),
			)

		val marks = puppet.parameterKeyMarks()
		assertEquals(listOf(-1f, 0f, 1f), marks[shrink]?.blendKeys, "blend keys union drawable + deformer bindings (incl. neutral)")
		assertEquals(emptyList<Float>(), marks[shrink]?.gridKeys, "a blend-shape parameter has no circle keys")
	}

	/** A parameter no object keys is absent from the index. */
	@Test
	fun unkeyedParameterHasNoEntry() {
		val puppet = model(listOf(drawableGrid("d", angleX, floatArrayOf(0f))), emptyList())
		assertNull(puppet.parameterKeyMarks()[unused], "an unkeyed parameter contributes no marks")
	}
}
