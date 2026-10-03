package org.umamo.render.eval

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
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertSame

/**
 * Pins the batch resolver against the per-drawable functions it must agree with, and the one thing
 * that makes it worth having: siblings under one deformer share a single baked world.
 */
class DrawableSpaceResolverTest {
	private val blendParameter = ParameterId("A")
	private val toggleParameter = ParameterId("T")
	private val warpId = DeformerId("W")
	private val rotationId = DeformerId("R")
	private val togglingRotationId = DeformerId("RT")
	private val direct = DrawableId("direct")
	private val warpChildA = DrawableId("warpA")
	private val warpChildB = DrawableId("warpB")
	private val rotationChild = DrawableId("rotationChild")
	private val rangeHiddenChild = DrawableId("rangeHidden")
	private val orphan = DrawableId("orphan")
	private val keyed = DrawableId("keyed")
	private val gridless = DrawableId("gridless")
	private val unknown = DrawableId("unknown")

	@Test
	fun siblingsUnderOneWarpShareOneBakedWorld() {
		val resolver = DrawableSpaceResolver(model(), emptyMap())
		val worldA = assertNotNull(resolver.mapping(warpChildA)).parentWorld
		val worldB = assertNotNull(resolver.mapping(warpChildB)).parentWorld
		assertNotNull(worldA)
		assertSame(worldA, worldB, "one resolver bakes the deformer worlds once, so siblings share the instance")
	}

	@Test
	fun aResolverAgreesWithThePerDrawableFunctions() {
		val rig = model()
		for (pose in listOf(emptyMap(), mapOf(blendParameter to 0.5f), mapOf(toggleParameter to 0.75f))) {
			val resolver = DrawableSpaceResolver(rig, pose)
			for (drawableId in listOf(direct, warpChildA, warpChildB, rotationChild, rangeHiddenChild, orphan, keyed, gridless, unknown)) {
				val single = drawableSpaceMapping(rig, pose, drawableId)
				val batched = resolver.mapping(drawableId)
				assertEquals(single == null, batched == null, "mapping null-ness for $drawableId at $pose")
				if (single != null && batched != null) {
					val local = floatArrayOf(0.25f, 0.25f, 0.75f, 0.5f, 0.5f, 0.75f)
					assertContentEquals(single.localToWorld(local), batched.localToWorld(local), "world projection for $drawableId at $pose")
				}
				assertContentEquals(drawableLocalPosed(rig, pose, drawableId), resolver.localPosed(drawableId), "posed local shape for $drawableId at $pose")
			}
		}
	}

	@Test
	fun rangeHiddenAndOrphanedChildrenHaveNoMapping() {
		val resolver = DrawableSpaceResolver(model(), emptyMap())
		assertNull(resolver.mapping(rangeHiddenChild), "a parent keyed outside the pose has no world, so the child has no mapping")
		assertNull(resolver.mapping(orphan), "a parent id the model does not carry has no world")
		assertNull(resolver.mapping(unknown))
		assertNull(resolver.localPosed(unknown))
		assertNull(resolver.drawable(unknown))
	}

	@Test
	fun aGridlessLocalShapeIsACopy() {
		val rig = model()
		val resolver = DrawableSpaceResolver(rig, emptyMap())
		val restPositions = rig.drawables.first { drawable -> drawable.id == gridless }.mesh!!.positions
		val posed = assertNotNull(resolver.localPosed(gridless))
		assertNotSame(restPositions, posed, "an unkeyed drawable's posed shape is a copy of its rest mesh, never the stored array")
		assertContentEquals(restPositions, posed)
	}

	/**
	 * The rig every case runs over: a direct drawable, two siblings under one warp, a rotation child, a
	 * child of a rotation keyed outside the default pose, a child of a missing deformer, a keyed direct
	 * drawable that displaces at the blend parameter's half, and an unkeyed one.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val blendAxis = listOf(KeyformAxis(blendParameter, floatArrayOf(0f, 1f)))
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
					blendAxis,
					listOf(
						KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f))),
						KeyformCell(intArrayOf(1), WarpLatticeForm(floatArrayOf(14f, 20f, 18f, 21f, 13f, 26f, 19f, 27f))),
					),
				),
			)
		val rotation =
			Deformer.Rotation(
				rotationId,
				"R",
				null,
				null,
				0f,
				KeyformGrid(blendAxis, listOf(KeyformCell(intArrayOf(0), RotationPivotForm(5f, 5f, 90f, 2f)), KeyformCell(intArrayOf(1), RotationPivotForm(5f, 5f, 45f, 3f)))),
			)
		val togglingRotation =
			Deformer.Rotation(
				togglingRotationId,
				"RT",
				null,
				null,
				0f,
				KeyformGrid(
					listOf(KeyformAxis(toggleParameter, floatArrayOf(0.5f, 1f))),
					listOf(KeyformCell(intArrayOf(0), RotationPivotForm(5f, 5f, 90f, 2f)), KeyformCell(intArrayOf(1), RotationPivotForm(5f, 5f, 90f, 4f))),
				),
			)
		val triangle = floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f)
		val zeroGrid = KeyformGrid(blendAxis, listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6)))))
		val displacingGrid =
			KeyformGrid(
				blendAxis,
				listOf(
					KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))),
					KeyformCell(intArrayOf(1), MeshDeltaForm(floatArrayOf(10f, 0f, 10f, 0f, 10f, 0f))),
				),
			)
		return PuppetModel(
			parameters = listOf(Parameter(blendParameter, "A", -1f, 1f, 0f), Parameter(toggleParameter, "T", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp, rotation, togglingRotation),
			drawables =
				listOf(
					drawable(direct, null, triangle, zeroGrid),
					drawable(warpChildA, warpId, triangle, zeroGrid),
					drawable(warpChildB, warpId, floatArrayOf(0.5f, 0.5f, 0.9f, 0.1f, 0.1f, 0.9f), zeroGrid),
					drawable(rotationChild, rotationId, triangle, zeroGrid),
					drawable(rangeHiddenChild, togglingRotationId, triangle, zeroGrid),
					drawable(orphan, DeformerId("missing"), triangle, zeroGrid),
					drawable(keyed, null, triangle, displacingGrid),
					drawable(gridless, null, triangle, null),
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
		Drawable(id, id.raw, parent, BlendMode.Normal, emptyList(), DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)), grid)
}