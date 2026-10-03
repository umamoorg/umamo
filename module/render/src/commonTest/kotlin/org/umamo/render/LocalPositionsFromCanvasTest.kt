package org.umamo.render

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins [withLocalPositionsFromCanvas]: a drawable under a warp takes the base that puts its rest shape over its
 * canvas mesh, the canvas mesh itself untouched, and a drawable the chain cannot map is reported and left alone.
 */
class LocalPositionsFromCanvasTest {
	private val parameterId = ParameterId("P")
	private val warpId = DeformerId("warp")

	// Inside the lattice below, which spreads its unit square over canvas pixels 100..300 on both axes.
	private val canvas = floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)

	/**
	 * A drawable over the shared [canvas] array.
	 *
	 * @param String     id             The id.
	 * @param DeformerId parentDeformer The parent.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, parentDeformer: DeformerId): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parentDeformer,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(canvas.copyOf(), FloatArray(canvas.size), intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)

	/**
	 * One warp and two drawables: one under it, one under a deformer the model does not hold.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					Deformer.Warp(
						id = warpId,
						name = "warp",
						parent = null,
						partId = null,
						rows = 1,
						columns = 1,
						isQuadTransform = true,
						geometryGrid =
							KeyformGrid(
								listOf(KeyformAxis(parameterId, floatArrayOf(0f))),
								listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f)))),
							),
					),
				),
			drawables = listOf(drawable("child", warpId), drawable("orphan", DeformerId("missing"))),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	@Test
	fun aWarpChildTakesTheBaseThatPutsItOverItsCanvasMesh() {
		val before = model()
		val derived = withLocalPositionsFromCanvas(before, listOf(DrawableId("child")))
		val mesh = derived.model.drawables.first { drawable -> drawable.id == DrawableId("child") }.mesh!!
		val expected = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.3f)
		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - mesh.localPositions[componentIndex]) <= 1e-5f, "component $componentIndex is ${mesh.localPositions[componentIndex]}, not ${expected[componentIndex]}")
		}
		assertSame(before.drawables.first().mesh!!.positions, mesh.positions, "the canvas mesh is untouched")
		assertTrue(derived.unconverted.isEmpty())
	}

	@Test
	fun aDrawableTheChainCannotMapIsReportedAndLeftAlone() {
		val before = model()
		val derived = withLocalPositionsFromCanvas(before, listOf(DrawableId("orphan")))
		assertEquals(listOf(DrawableId("orphan")), derived.unconverted)
		assertSame(before.drawables.last(), derived.model.drawables.last())
	}

	@Test
	fun nothingAskedIsTheSameModel() {
		val before = model()
		assertSame(before, withLocalPositionsFromCanvas(before, emptyList()).model)
	}
}