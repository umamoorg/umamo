package org.umamo.ui.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.OperatorParameter
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.RehomeParameterKeys
import org.umamo.edit.structure.withDeformerMoved
import org.umamo.edit.withParameter
import org.umamo.render.eval.parentSpaceAt
import org.umamo.render.eval.warpLatticeAt
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins re-parenting and deleting without moving art: a drawable bound to a deformer - keyed or not - keeps its place
 * and its keyed motion, a re-nested warp keeps its lattice and its art, deleting a warp keeps every child it held (its
 * drawables, warps, and rotations), a re-parent registers Keep in Place on the strip and unchecking it lets the item
 * follow its new parent as before, a reorder among siblings registers nothing, and a child the new chain cannot map
 * still moves with a notice.
 */
class RehomeTest {
	private val parameterId = ParameterId("P")
	private val warpId = DeformerId("warp")
	private val flatId = DeformerId("flat")
	private val nestedId = DeformerId("nested")
	private val innerId = DeformerId("inner")
	private val spinId = DeformerId("spin")
	private val swung = mapOf(parameterId to 1f)

	/**
	 * A one-cell warp over [corners], keyed on P at both ends with the same lattice, so it shows at every value of P.
	 *
	 * @param DeformerId id      The id.
	 * @param DeformerId? parent The parent deformer.
	 * @param FloatArray corners The four corners, row-major, u across.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, parent: DeformerId?, corners: FloatArray): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = parent,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid = KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(-1f, 1f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(corners)), KeyformCell(intArrayOf(1), WarpLatticeForm(corners)))),
		)

	/**
	 * A drawable over [canvas] with base [local] (the same array when [local] is omitted).
	 *
	 * @param String      id     The id.
	 * @param DeformerId? parent The parent deformer.
	 * @param FloatArray  canvas The canvas mesh.
	 * @param FloatArray  local  The base.
	 * @param Float       sweep  The delta its keyform at P = 1 moves every coordinate by; 0 for an unkeyed drawable.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, parent: DeformerId?, canvas: FloatArray, local: FloatArray = canvas, sweep: Float = 0f): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parent,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions = canvas, localPositions = local, uvs = FloatArray(canvas.size), indices = intArrayOf(0, 1, 2)),
			geometryGrid =
				if (sweep != 0f) {
					KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(canvas.size))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(canvas.size) { sweep }))))
				} else {
					null
				},
		)

	/**
	 * A warp over canvas pixels 100..300 holding a drawable, a keyed drawable, a warp, and a rotation; a warp with no
	 * area; a root warp over 150..250 holding art, to re-nest; and three root drawables, one keyed.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val spin = Deformer.Rotation(spinId, "spin", warpId, null, 0f, KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0.5f, 0.5f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0.6f, 0.5f, 20f, 1f)))))

		return PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					warp(warpId, null, floatArrayOf(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f)),
					warp(flatId, null, FloatArray(8) { 200f }),
					warp(nestedId, null, floatArrayOf(150f, 150f, 250f, 150f, 150f, 250f, 250f, 250f)),
					warp(innerId, warpId, floatArrayOf(0.3f, 0.3f, 0.6f, 0.3f, 0.3f, 0.6f, 0.6f, 0.6f)),
					spin,
				),
			drawables =
				listOf(
					drawable("root", null, floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)),
					drawable("child", warpId, floatArrayOf(140f, 140f, 180f, 140f, 180f, 180f), floatArrayOf(0.2f, 0.2f, 0.4f, 0.2f, 0.4f, 0.4f)),
					drawable("keyedChild", warpId, floatArrayOf(140f, 140f, 180f, 140f, 180f, 180f), floatArrayOf(0.2f, 0.2f, 0.4f, 0.2f, 0.4f, 0.4f), sweep = 0.05f),
					drawable("keyed", null, floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f), sweep = 5f),
					drawable("other", null, floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)),
					drawable("nestedArt", nestedId, floatArrayOf(170f, 170f, 200f, 170f, 200f, 200f), floatArrayOf(0.2f, 0.2f, 0.5f, 0.2f, 0.5f, 0.5f)),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * Where [id] sits on screen in [model] at [pose]: its posed shape through its deformer chain.
	 *
	 * @param PuppetModel model The model.
	 * @param String      id    The drawable.
	 * @param Map         pose  The pose.
	 * @return FloatArray The world positions.
	 */
	private fun worldAt(model: PuppetModel, id: String, pose: Map<ParameterId, Float> = emptyMap()): FloatArray = assertNotNull(captureDrawableWorld(model, pose, DrawableId(id))).world

	/**
	 * The point (10, 0) of the spin rotation's frame, on screen in [model] at rest.
	 *
	 * @param PuppetModel model The model.
	 * @return FloatArray The world point.
	 */
	private fun throughSpin(model: PuppetModel): FloatArray = assertNotNull(parentSpaceAt(model, spinId)).localToWorld(floatArrayOf(10f, 0f))

	/**
	 * Asserts two world shapes agree within a hundredth of a pixel.
	 *
	 * @param FloatArray expected The shape before.
	 * @param FloatArray actual   The shape after.
	 * @param String     label    The case.
	 */
	private fun assertSamePlace(expected: FloatArray, actual: FloatArray, label: String) {
		assertEquals(expected.size, actual.size, label)

		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= 0.01f, "$label: component $componentIndex moved from ${expected[componentIndex]} to ${actual[componentIndex]}")
		}
	}

	/**
	 * The mesh of drawable [id] in [session]'s current model.
	 *
	 * @param EditorSession session The session.
	 * @param String        id      The drawable.
	 * @return DrawableMesh The mesh.
	 */
	private fun meshOf(session: EditorSession, id: String): DrawableMesh = session.model.value.drawables.first { drawable -> drawable.id == DrawableId(id) }.mesh!!

	@Test
	fun bindingAnUnkeyedDrawableToAWarpKeepsItsPlace() {
		val session = EditorSession(model())
		val before = worldAt(session.model.value, "root")
		session.setDrawableParentKeepingRest(DrawableId("root"), warpId)
		assertEquals(warpId, session.model.value.drawables.first().parentDeformerId)
		assertSamePlace(before, worldAt(session.model.value, "root"), "root -> warp")
		val mesh = meshOf(session, "root")
		assertEquals(0.1f, mesh.localPositions[0], 1e-5f, "the base is in the lattice")
		assertEquals(listOf(120f, 120f, 160f, 120f, 160f, 160f), mesh.positions.toList(), "the canvas mesh does not move")
		assertTrue(session.canUndo.value, "one undo step")
		assertNull(session.notice.value, "a kept place needs no notice")
	}

	@Test
	fun aKeyedDrawableKeepsItsPlaceAndItsMotion() {
		val before = model()
		val session = EditorSession(before)
		session.setDrawableParentKeepingRest(DrawableId("keyed"), warpId)
		assertEquals(warpId, session.model.value.drawables.first { drawable -> drawable.id == DrawableId("keyed") }.parentDeformerId)
		assertSamePlace(worldAt(before, "keyed"), worldAt(session.model.value, "keyed"), "at rest")
		assertSamePlace(worldAt(before, "keyed", swung), worldAt(session.model.value, "keyed", swung), "swung")
	}

	@Test
	fun aReNestedWarpKeepsItsLatticeAndItsArtUnlessKeepInPlaceIsOff() {
		val before = model()
		val session = EditorSession(before)
		session.moveDeformerKeepingRest(nestedId, warpId, null, "area")

		assertEquals(warpId, session.model.value.deformerById[nestedId]?.parent)
		assertSamePlace(assertNotNull(warpLatticeAt(before, emptyMap(), nestedId)).world, assertNotNull(warpLatticeAt(session.model.value, emptyMap(), nestedId)).world, "the lattice")
		assertSamePlace(worldAt(before, "nestedArt"), worldAt(session.model.value, "nestedArt"), "the art under it")
		val record = assertNotNull(session.adjustableOperation.value)
		assertEquals("area", record.areaId)
		val keep = record.parameters.single() as OperatorParameter.BooleanParameter
		assertEquals(RehomeParameterKeys.KEEP_IN_PLACE, keep.key)

		session.adjustLastOperation(record.parameters.withParameter(keep.key, keep.copy(value = false)), adjustedKey = keep.key)

		assertEquals(before.withDeformerMoved(nestedId, warpId, null).deformerById[nestedId], session.model.value.deformerById[nestedId], "unchecked, it keeps its numbers and follows")
	}

	@Test
	fun aReorderAmongSiblingsRegistersNothing() {
		val session = EditorSession(model())
		session.moveDeformerKeepingRest(nestedId, null, warpId, "area")

		assertNull(session.adjustableOperation.value, "a reorder changes no space")
	}

	@Test
	fun deletingAWarpKeepsEveryChildItHeldInPlace() {
		val before = model()
		val session = EditorSession(before)
		session.deleteTargetKeepingRest(SelectionTarget.Deformer(warpId), cascade = false)

		assertNull(session.model.value.deformerById[warpId])
		assertSamePlace(worldAt(before, "child"), worldAt(session.model.value, "child"), "the drawable")
		assertEquals(140f, meshOf(session, "child").localPositions[0], 1e-3f, "its base is on the canvas now")
		assertSamePlace(worldAt(before, "keyedChild", swung), worldAt(session.model.value, "keyedChild", swung), "the keyed drawable, swung")
		assertSamePlace(assertNotNull(warpLatticeAt(before, emptyMap(), innerId)).world, assertNotNull(warpLatticeAt(session.model.value, emptyMap(), innerId)).world, "the child warp")
		assertSamePlace(throughSpin(before), throughSpin(session.model.value), "the child rotation")
		assertNull(session.notice.value)
	}

	@Test
	fun aChainThatCannotMapTheDrawableStillBindsItWithANotice() {
		val session = EditorSession(model())
		val local = meshOf(session, "other").localPositions
		session.setDrawableParentKeepingRest(DrawableId("other"), flatId)
		assertEquals(flatId, session.model.value.drawables.first { drawable -> drawable.id == DrawableId("other") }.parentDeformerId, "the binding is still made")
		assertSame(local, meshOf(session, "other").localPositions, "the base is kept")
		assertEquals("notice.reparent.placeNotKept", session.notice.value?.messageKey, "and the rigger is told the art moved")
		assertEquals(listOf("other"), session.notice.value?.arguments, "by the drawable's name")
	}

	/**
	 * A warp under a deformer the model does not carry has no chain to map its children through, so deleting it
	 * re-homes them on their numbers, and the rigger is told how many moved.
	 */
	@Test
	fun deletingAWarpWhoseChainCannotMapItsChildrenSaysHowManyMoved() {
		val base = model()
		val orphanedWarp = (base.deformers.first { deformer -> deformer.id == warpId } as Deformer.Warp).copy(parent = DeformerId("ghost"))
		val session = EditorSession(base.copy(deformers = listOf(orphanedWarp, base.deformerById.getValue(flatId)), drawables = base.drawables.filter { drawable -> drawable.parentDeformerId != nestedId }))
		val local = meshOf(session, "child").localPositions
		session.deleteTargetKeepingRest(SelectionTarget.Deformer(warpId), cascade = false)
		assertEquals(DeformerId("ghost"), session.model.value.drawables.first { drawable -> drawable.id == DrawableId("child") }.parentDeformerId, "the warp is deleted, its drawables re-homed to its parent")
		assertSame(local, meshOf(session, "child").localPositions, "the base is kept")
		assertEquals("notice.reparent.placesNotKept", session.notice.value?.messageKey, "and the rigger is told the art moved")
		assertEquals(listOf("2"), session.notice.value?.arguments, "with the count")
	}
}