package org.umamo.render

import org.umamo.runtime.keyform.fanOutMesh
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Unit tests for [restMeshesToCanvasSpace] on a tiny synthetic model.  The rebase rewrites ONLY the
 * geometry (base positions and keyform/blend-shape deltas); every non-positional keyform channel must
 * ride along unchanged.  Pins the modelA hologram regression, where the rebase rebuilt each MeshForm
 * and silently dropped its multiply/screen colours, stripping the per-drawable tints off every
 * MOC3-imported model (the hologram overlay quad lost the blue that its HardLight blend needed).
 */
class Moc3RestMeshTest {
	private val paramA = ParameterId("A")
	private val blueMultiply = ColorRgb(0.1f, 0.7f, 1f)
	private val warmScreen = ColorRgb(0.2f, 0.1f, 0f)

	private fun modelWithChannelledKeyforms(): PuppetModel {
		// Built bundled and fanned out, so the fixture matches what an importer actually produces.
		val fanned =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshForm(FloatArray(6), drawOrder = 400f, opacity = 0.25f, multiplyColor = blueMultiply, screenColor = warmScreen)),
					KeyformCell(intArrayOf(1), MeshForm(FloatArray(6) { 2f }, drawOrder = 600f, opacity = 0.75f, multiplyColor = blueMultiply, screenColor = warmScreen)),
				),
			).fanOutMesh()
		val drawable =
			Drawable(
				id = DrawableId("quad"),
				name = "quad",
				parentDeformerId = null,
				blendMode = BlendMode.HardLight,
				maskedBy = emptyList(),
				mesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
				geometryGrid = fanned.geometry,
				channelGrids = fanned.channels,
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = paramA,
							keys = floatArrayOf(0f, 1f),
							neutralIndex = 0,
							forms = listOf(null, MeshForm(FloatArray(6) { 1f }, drawOrder = 500f, opacity = 0.5f, multiplyColor = blueMultiply, screenColor = warmScreen)),
						),
					),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
		)
	}

	/**
	 * The rebase moves geometry only; every channel track must come through untouched.
	 *
	 * Since the channel split this is structural - the tracks are a separate field the rebase never
	 * writes - but it is asserted anyway, because the property is what the rebase is allowed to do, not
	 * an artefact of how it happens to be written today.
	 */
	@Test
	fun rebasePreservesEveryNonPositionalKeyformChannel() {
		val rebased = restMeshesToCanvasSpace(modelWithChannelledKeyforms())
		val drawable = rebased.drawables.single()
		assertEquals(2, drawable.geometryGrid!!.cells.size)
		val channels = drawable.channelGrids

		fun scalars(channel: FormChannel): List<Float> =
			channels[channel]!!.cells.map { cell -> (cell.form as ChannelValue.Scalar).value }

		fun colors(channel: FormChannel): List<ColorRgb> =
			channels[channel]!!.cells.map { cell -> (cell.form as ChannelValue.Color).color }
		assertEquals(listOf(400f, 600f), scalars(FormChannel.DRAW_ORDER))
		assertEquals(listOf(0.25f, 0.75f), scalars(FormChannel.OPACITY))
		assertEquals(listOf(blueMultiply, blueMultiply), colors(FormChannel.MULTIPLY_COLOR), "keyform multiply colour must survive the rebase")
		assertEquals(listOf(warmScreen, warmScreen), colors(FormChannel.SCREEN_COLOR), "keyform screen colour must survive the rebase")
		val blendForm = drawable.blendShapes.single().forms[1]!!
		assertEquals(500f, blendForm.drawOrder)
		assertEquals(0.5f, blendForm.opacity)
		assertEquals(blueMultiply, blendForm.multiplyColor, "blend-shape multiply colour must survive the rebase")
		assertEquals(warmScreen, blendForm.screenColor, "blend-shape screen colour must survive the rebase")
	}

	@Test
	fun rebaseKeepsAbsoluteKeyformGeometryIntact() {
		val original = modelWithChannelledKeyforms()
		val rebased = restMeshesToCanvasSpace(original)
		val originalDrawable = original.drawables.single()
		val rebasedDrawable = rebased.drawables.single()
		// base + delta must reconstruct the same absolute positions per cell, whatever base the
		// rebase chose - that invariance is the whole contract of the rewrite.
		for (cellIndex in 0 until 2) {
			val originalForm = originalDrawable.geometryGrid!!.cells[cellIndex].form
			val rebasedForm = rebasedDrawable.geometryGrid!!.cells[cellIndex].form
			for (coordIndex in 0 until 6) {
				assertEquals(
					originalDrawable.mesh!!.positions[coordIndex] + originalForm.positionDeltas[coordIndex],
					rebasedDrawable.mesh!!.positions[coordIndex] + rebasedForm.positionDeltas[coordIndex],
					absoluteTolerance = 1e-4f,
				)
			}
		}
	}

	private fun assertEquals(expected: Float, actual: Float, absoluteTolerance: Float) {
		kotlin.test.assertTrue(
			kotlin.math.abs(expected - actual) <= absoluteTolerance,
			"expected $expected, got $actual",
		)
	}
}
