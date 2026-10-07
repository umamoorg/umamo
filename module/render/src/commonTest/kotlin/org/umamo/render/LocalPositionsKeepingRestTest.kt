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
import kotlin.test.assertTrue

/**
 * Pins [localPositionsKeepingRest]: a root drawable bound to a warp takes the base that keeps its rest shape where
 * it was, and a drawable whose new chain cannot map it is left out for the caller to keep its old base.
 */
class LocalPositionsKeepingRestTest {
	private val parameterId = ParameterId("P")
	private val warpId = DeformerId("warp")

	// Inside the lattice below, which spreads its unit square over canvas pixels 100..300 on both axes.
	private val canvas = floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)

	/**
	 * A deformer-less drawable over a copy of [canvas].
	 *
	 * @param String id The id.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(canvas.copyOf(), FloatArray(canvas.size), intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)

	/**
	 * One warp and two drawables at the root, "child" and "orphan".
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
			drawables = listOf(drawable("child"), drawable("orphan")),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * [model] with drawable [id] bound to [parent], its base unchanged.
	 *
	 * @param PuppetModel model  The model.
	 * @param String      id     The drawable.
	 * @param DeformerId  parent The new parent deformer.
	 * @return PuppetModel The rebound model.
	 */
	private fun rebound(model: PuppetModel, id: String, parent: DeformerId): PuppetModel =
		model.copy(drawables = model.drawables.map { drawable -> if (drawable.id == DrawableId(id)) drawable.copy(parentDeformerId = parent) else drawable })

	@Test
	fun aRootDrawableBoundToAWarpTakesTheBaseThatKeepsItInPlace() {
		val before = model()
		val local = localPositionsKeepingRest(before, rebound(before, "child", warpId), listOf(DrawableId("child")))[DrawableId("child")]!!
		val expected = floatArrayOf(0.1f, 0.1f, 0.3f, 0.1f, 0.3f, 0.3f)
		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - local[componentIndex]) <= 1e-5f, "component $componentIndex is ${local[componentIndex]}, not ${expected[componentIndex]}")
		}
	}

	@Test
	fun aDrawableTheNewChainCannotMapIsLeftOut() {
		val before = model()
		val after = rebound(before, "orphan", DeformerId("missing"))
		assertTrue(localPositionsKeepingRest(before, after, listOf(DrawableId("orphan"))).isEmpty())
	}

	@Test
	fun nothingAskedConvertsNothing() {
		val before = model()
		assertEquals(emptyMap(), localPositionsKeepingRest(before, rebound(before, "child", warpId), emptyList()))
	}
}