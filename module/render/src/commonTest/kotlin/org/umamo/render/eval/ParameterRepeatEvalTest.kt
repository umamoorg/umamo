package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins that a pose outside a parameter's range evaluates as the value it resolves to: a repeating axis as its wrapped
 * value, a normal one as the nearer end.  The GPU takes its corners and weights from the same [preparePose], so the
 * corner check covers both paths.
 */
class ParameterRepeatEvalTest {
	private val tolerance = 1e-4f
	private val parameterId = ParameterId("Loop")
	private val drawableId = DrawableId("M")

	/** One vertex at x = 10, keyed at 0 and at 1, where it has moved 4 to the right. */
	private fun model(repeat: Boolean): PuppetModel {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(floatArrayOf(0f, 0f))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(4f, 0f))),
				),
			)
		val mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(10f, 5f), FloatArray(0), IntArray(0))
		val drawable = Drawable(drawableId, "M", null, BlendMode.Normal, emptyList(), mesh, grid)

		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "Loop", 0f, 1f, 0f, repeat = repeat)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawableId)),
			rootPartId = null,
		)
	}

	private fun xAt(model: PuppetModel, value: Float): Float {
		val world = CpuDeformationEvaluator().evaluate(model, mapOf(parameterId to value)).worldPositions[drawableId]

		return assertNotNull(world, "the drawable is hidden at $value")[0]
	}

	@Test
	fun aRepeatingAxisEvaluatesPastEitherEndAsItsWrappedValue() {
		val model = model(repeat = true)
		assertEquals(xAt(model, 0.25f), xAt(model, 1.25f), tolerance)
		assertEquals(11f, xAt(model, 1.25f), tolerance)
		assertEquals(xAt(model, 0.75f), xAt(model, -0.25f), tolerance)
		assertEquals(xAt(model, 0.5f), xAt(model, 3.5f), tolerance)
	}

	/** Exactly max stays the max key on a repeating axis; the runtime evaluates it as min, Umamo does not. */
	@Test
	fun aRepeatingAxisKeepsExactlyMaxAsItsMaxKey() {
		assertEquals(14f, xAt(model(repeat = true), 1f), tolerance)
	}

	@Test
	fun aNormalAxisEvaluatesPastEitherEndAsTheNearerEnd() {
		val model = model(repeat = false)
		assertEquals(14f, xAt(model, 1.5f), tolerance)
		assertEquals(10f, xAt(model, -0.5f), tolerance)
	}

	@Test
	fun theGpuCornersAreTheWrappedPosesCorners() {
		val model = model(repeat = true)
		val wrapped = preparePose(model, mapOf(parameterId to 1.25f)).drawables.single().corners
		val inside = preparePose(model, mapOf(parameterId to 0.25f)).drawables.single().corners
		assertEquals(inside, wrapped)
	}
}