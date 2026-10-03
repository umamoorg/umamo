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
import kotlin.test.assertSame

/**
 * Unit tests for [restMeshesToCanvasSpace] on a tiny synthetic model.  The pass sets ONLY the canvas
 * editable mesh (DrawableMesh.positions) to the rest shape; the keyform-space base, every keyform, every
 * blend shape, and every channel track pass through as the same instances, so evaluation cannot change.
 * Pins the modelA hologram regression too, where a pass that rebuilt each MeshForm silently dropped its
 * multiply/screen colors, stripping the per-drawable tints off every MOC3-imported model.
 */
class Moc3RestMeshTest {
	private val paramA = ParameterId("A")
	private val blueMultiply = ColorRgb(0.1f, 0.7f, 1f)
	private val warmScreen = ColorRgb(0.2f, 0.1f, 0f)

	/**
	 * One root quad whose default cell displaces every component by [restDelta], so its rest shape is its
	 * base plus that much.
	 *
	 * @param Float restDelta The default cell's delta on every component.
	 * @return PuppetModel The model.
	 */
	private fun modelWithChannelledKeyforms(restDelta: Float = 0f): PuppetModel {
		// Built bundled and fanned out, so the fixture matches what an importer actually produces.
		val fanned =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), MeshForm(FloatArray(6) { restDelta }, drawOrder = 400f, opacity = 0.25f, multiplyColor = blueMultiply, screenColor = warmScreen)),
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
				mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
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

	/** The canvas mesh takes the rest shape; the base and everything keyed on it stay the same instances. */
	@Test
	fun theCanvasMeshTakesTheRestShapeAndTheBaseStays() {
		val original = modelWithChannelledKeyforms(restDelta = 0.5f)
		val originalDrawable = original.drawables.single()
		val drawable = restMeshesToCanvasSpace(original).drawables.single()
		val mesh = drawable.mesh!!
		assertEquals(listOf(0.5f, 0.5f, 1.5f, 0.5f, 0.5f, 1.5f), mesh.positions.toList(), "the canvas mesh is the base plus the default cell")
		assertSame(originalDrawable.mesh!!.localPositions, mesh.localPositions, "the base is untouched")
		assertSame(originalDrawable.geometryGrid, drawable.geometryGrid, "the keyforms are untouched")
		assertSame(originalDrawable.blendShapes, drawable.blendShapes, "the blend shapes are untouched")
		assertSame(originalDrawable.channelGrids, drawable.channelGrids, "the channel tracks are untouched")
	}

	/** A drawable whose rest shape is already its base keeps the one shared array, and the same instance. */
	@Test
	fun aDrawableWhoseRestShapeIsItsBaseIsLeftAlone() {
		val original = modelWithChannelledKeyforms()
		assertSame(original.drawables.single(), restMeshesToCanvasSpace(original).drawables.single())
	}

	/**
	 * Every channel the keyforms carry comes through: the pass never rebuilds a form, so a multiply or
	 * screen tint cannot be dropped on the way.
	 */
	@Test
	fun everyNonPositionalKeyformChannelSurvives() {
		val drawable = restMeshesToCanvasSpace(modelWithChannelledKeyforms(restDelta = 0.5f)).drawables.single()
		assertEquals(2, drawable.geometryGrid!!.cells.size)
		val channels = drawable.channelGrids

		fun scalars(channel: FormChannel): List<Float> =
			channels[channel]!!.cells.map { cell -> (cell.form as ChannelValue.Scalar).value }

		fun colors(channel: FormChannel): List<ColorRgb> =
			channels[channel]!!.cells.map { cell -> (cell.form as ChannelValue.Color).color }
		assertEquals(listOf(400f, 600f), scalars(FormChannel.DRAW_ORDER))
		assertEquals(listOf(0.25f, 0.75f), scalars(FormChannel.OPACITY))
		assertEquals(listOf(blueMultiply, blueMultiply), colors(FormChannel.MULTIPLY_COLOR), "keyform multiply color survives")
		assertEquals(listOf(warmScreen, warmScreen), colors(FormChannel.SCREEN_COLOR), "keyform screen color survives")
		val blendForm = drawable.blendShapes.single().forms[1]!!
		assertEquals(500f, blendForm.drawOrder)
		assertEquals(0.5f, blendForm.opacity)
		assertEquals(blueMultiply, blendForm.multiplyColor, "blend-shape multiply color survives")
		assertEquals(warmScreen, blendForm.screenColor, "blend-shape screen color survives")
	}
}