package org.umamo.ui.transform

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame

/**
 * Pins the batched capture against the per-drawable capture it replaces in every loop: the same
 * answer in every space per drawable, the same drops for what cannot be captured, and the same aliasing
 * of base when the pose leaves a keyed drawable's grid.
 */
class CaptureDrawableWorldsTest {
	private val parameterId = ParameterId("P")
	private val warpId = DeformerId("W")
	private val keyed = DrawableId("keyed")
	private val warpChildA = DrawableId("warpA")
	private val warpChildB = DrawableId("warpB")
	private val offKey = DrawableId("offKey")
	private val hidden = DrawableId("hidden")
	private val meshless = DrawableId("meshless")
	private val requested = listOf(keyed, warpChildA, hidden, warpChildB, meshless, offKey)

	@Test
	fun theBatchMatchesThePerDrawableCapture() {
		val rig = model()
		val batched = captureDrawableWorlds(rig, emptyMap(), requested)
		val drawableById = rig.drawables.associateBy { drawable -> drawable.id }
		for (geometry in batched) {
			val single = assertNotNull(captureDrawableWorld(rig, emptyMap(), geometry.drawableId), "the single capture answers for ${geometry.drawableId}")
			assertContentEquals(single.local, geometry.local, "base of ${geometry.drawableId}")
			assertContentEquals(single.canvas, geometry.canvas, "canvas mesh of ${geometry.drawableId}")
			assertContentEquals(single.displayed, geometry.displayed, "displayed shape of ${geometry.drawableId}")
			assertContentEquals(single.world, geometry.world, "world shape of ${geometry.drawableId}")
			assertSame(drawableById.getValue(geometry.drawableId).mesh!!.localPositions, geometry.local, "the base is the stored array, not a copy")
			assertSame(drawableById.getValue(geometry.drawableId).mesh!!.positions, geometry.canvas, "and so is the canvas mesh")
		}
	}

	@Test
	fun hiddenAndMeshlessDrawablesAreDroppedInRequestOrder() {
		val batched = captureDrawableWorlds(model(), emptyMap(), requested)
		assertEquals(listOf(keyed, warpChildA, warpChildB, offKey), batched.map { geometry -> geometry.drawableId })
	}

	@Test
	fun anOffKeyDrawableDisplaysItsBase() {
		val batched = captureDrawableWorlds(model(), emptyMap(), listOf(offKey))
		val geometry = batched.single()
		assertSame(geometry.local, geometry.displayed, "a keyed drawable the pose leaves off every key shows its base, as the single capture does")
	}

	@Test
	fun aKeyedDrawableDisplaysItsNeutralBlendNotItsBase() {
		val geometry = captureDrawableWorlds(model(), emptyMap(), listOf(keyed)).single()
		assertContentEquals(floatArrayOf(500f, 500f, 600f, 500f, 500f, 600f), geometry.displayed)
		assertContentEquals(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), geometry.local)
	}

	/**
	 * The rig: a keyed direct drawable whose neutral cell displaces it, two unkeyed siblings under one
	 * warp, a direct drawable keyed only outside the default pose, a child of a deformer the model does
	 * not carry, and a drawable with no mesh.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val neutralAxis = listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 0f, 1f)))
		val neutralDeltas = floatArrayOf(500f, 500f, 590f, 500f, 500f, 590f)
		val keyedGrid =
			KeyformGrid(
				neutralAxis,
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(neutralDeltas.copyOf())),
					KeyformCell(intArrayOf(1), MeshDeltaForm(neutralDeltas.copyOf())),
					KeyformCell(intArrayOf(2), MeshDeltaForm(neutralDeltas.copyOf())),
				),
			)
		val offKeyGrid =
			KeyformGrid(
				listOf(KeyformAxis(parameterId, floatArrayOf(0.5f, 1f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6)))),
			)
		val warp =
			Deformer.Warp(
				warpId,
				"W",
				null,
				null,
				1,
				1,
				true,
				KeyformGrid(
					listOf(KeyformAxis(parameterId, floatArrayOf(0f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f)))),
				),
			)
		val latticeTriangle = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f)
		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp),
			drawables =
				listOf(
					drawable(keyed, null, floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), keyedGrid),
					drawable(warpChildA, warpId, latticeTriangle, null),
					drawable(warpChildB, warpId, floatArrayOf(0.5f, 0.5f, 0.9f, 0.1f, 0.1f, 0.9f), null),
					drawable(offKey, null, floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), offKeyGrid),
					drawable(hidden, DeformerId("missing"), latticeTriangle, null),
					Drawable(meshless, "meshless", null, BlendMode.Normal, emptyList(), null, null),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * One triangle drawable.
	 *
	 * @param DrawableId id The drawable's id.
	 * @param DeformerId? parent Its parent deformer, or null for a direct drawable.
	 * @param FloatArray positions Its rest positions.
	 * @param KeyformGrid? grid Its keyform grid, or null for an unkeyed drawable.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, parent: DeformerId?, positions: FloatArray, grid: KeyformGrid<MeshDeltaForm>?): Drawable =
		Drawable(id, id.raw, parent, BlendMode.Normal, emptyList(), DrawableMesh.withLocalEqualToCanvas(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)), grid)
}