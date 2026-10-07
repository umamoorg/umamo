package org.umamo.ui.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the rebinding seam: a drawable with no keyed geometry keeps its place on screen when it is bound to a
 * deformer or when its deformer is deleted, its base re-expressed in the new parent's space; a keyed drawable,
 * and one whose new chain cannot map it, gets the flat write, its base kept.
 */
class DrawableReparentTest {
	private val parameterId = ParameterId("P")
	private val warpId = DeformerId("warp")
	private val flatId = DeformerId("flat")

	/**
	 * A one-cell warp over [corners], keyed on P.
	 *
	 * @param DeformerId id      The id.
	 * @param FloatArray corners The four corners, row-major, u across.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, corners: FloatArray): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = null,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid = KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(corners)))),
		)

	/**
	 * A drawable over [canvas] with base [local] (the same array when [local] is omitted).
	 *
	 * @param String      id     The id.
	 * @param DeformerId? parent The parent deformer.
	 * @param FloatArray  canvas The canvas mesh.
	 * @param FloatArray  local  The base.
	 * @param Boolean     keyed  Whether it carries a keyform with a real delta.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, parent: DeformerId?, canvas: FloatArray, local: FloatArray = canvas, keyed: Boolean = false): Drawable =
		Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = parent,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(positions = canvas, localPositions = local, uvs = FloatArray(canvas.size), indices = intArrayOf(0, 1, 2)),
			geometryGrid =
				if (keyed) {
					KeyformGrid(listOf(KeyformAxis(parameterId, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(canvas.size))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(canvas.size) { 5f }))))
				} else {
					null
				},
		)

	/**
	 * A warp over canvas pixels 100..300, a warp with no area, and four drawables: one at the root, one under
	 * the warp, one keyed at the root, and one more at the root to bind to the flat warp.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(warp(warpId, floatArrayOf(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f)), warp(flatId, FloatArray(8) { 200f })),
			drawables =
				listOf(
					drawable("root", null, floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)),
					drawable("child", warpId, floatArrayOf(140f, 140f, 180f, 140f, 180f, 180f), floatArrayOf(0.2f, 0.2f, 0.4f, 0.2f, 0.4f, 0.4f)),
					drawable("keyed", null, floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f), keyed = true),
					drawable("other", null, floatArrayOf(120f, 120f, 160f, 120f, 160f, 160f)),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/**
	 * Where [id] rests on screen in [model]: its rest shape through its deformer chain.
	 *
	 * @param PuppetModel model The model.
	 * @param String      id    The drawable.
	 * @return FloatArray The world positions.
	 */
	private fun worldRest(model: PuppetModel, id: String): FloatArray = captureDrawableWorld(model, emptyMap(), DrawableId(id))!!.world

	/**
	 * Asserts two world shapes agree within a hundredth of a pixel.
	 *
	 * @param FloatArray expected The shape before.
	 * @param FloatArray actual   The shape after.
	 * @param String     label    The case.
	 */
	private fun assertSamePlace(expected: FloatArray, actual: FloatArray, label: String) {
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
		val before = worldRest(session.model.value, "root")
		session.setDrawableParentDeformerKeepingRest(DrawableId("root"), warpId)
		assertEquals(warpId, session.model.value.drawables.first().parentDeformerId)
		assertSamePlace(before, worldRest(session.model.value, "root"), "root -> warp")
		val mesh = meshOf(session, "root")
		assertEquals(0.1f, mesh.localPositions[0], 1e-5f, "the base is in the lattice")
		assertEquals(listOf(120f, 120f, 160f, 120f, 160f, 160f), mesh.positions.toList(), "the canvas mesh does not move")
		assertTrue(session.canUndo.value, "one undo step")
	}

	@Test
	fun deletingAWarpKeepsItsUnkeyedDrawablesInPlace() {
		val session = EditorSession(model())
		val before = worldRest(session.model.value, "child")
		session.deleteTargetKeepingRest(SelectionTarget.Deformer(warpId), cascade = false)
		assertEquals(null, session.model.value.drawables.first { drawable -> drawable.id == DrawableId("child") }.parentDeformerId)
		assertSamePlace(before, worldRest(session.model.value, "child"), "the warp deleted")
		assertEquals(140f, meshOf(session, "child").localPositions[0], 1e-3f, "the base is on the canvas now")
	}

	@Test
	fun aKeyedDrawableGetsTheFlatWrite() {
		val session = EditorSession(model())
		val local = meshOf(session, "keyed").localPositions
		session.setDrawableParentDeformerKeepingRest(DrawableId("keyed"), warpId)
		assertEquals(warpId, session.model.value.drawables.first { drawable -> drawable.id == DrawableId("keyed") }.parentDeformerId)
		assertSame(local, meshOf(session, "keyed").localPositions, "a keyed drawable keeps its base")
	}

	@Test
	fun aChainThatCannotMapTheDrawableGetsTheFlatWrite() {
		val session = EditorSession(model())
		val local = meshOf(session, "other").localPositions
		session.setDrawableParentDeformerKeepingRest(DrawableId("other"), flatId)
		assertEquals(flatId, session.model.value.drawables.first { drawable -> drawable.id == DrawableId("other") }.parentDeformerId, "the binding is still made")
		assertSame(local, meshOf(session, "other").localPositions, "the base is kept")
		assertEquals("notice.reparent.placeNotKept", session.notice.value?.messageKey, "and the rigger is told the art moved")
		assertEquals(listOf("other"), session.notice.value?.arguments, "by the drawable's name")
	}

	@Test
	fun aBindingTheChainCanMapRaisesNoNotice() {
		val session = EditorSession(model())
		session.setDrawableParentDeformerKeepingRest(DrawableId("root"), warpId)
		assertEquals(null, session.notice.value, "a kept place needs no notice")
	}

	/**
	 * A warp under a deformer the model does not carry has no chain to map its children through, so deleting it
	 * re-homes them on their numbers, and the rigger is told how many moved.
	 */
	@Test
	fun deletingAWarpWhoseChainCannotMapItsDrawablesSaysHowManyMoved() {
		val base = model()
		val orphanedWarp = (base.deformers.first { deformer -> deformer.id == warpId } as Deformer.Warp).copy(parent = DeformerId("ghost"))
		val session = EditorSession(base.copy(deformers = listOf(orphanedWarp, base.deformers.first { deformer -> deformer.id == flatId })))
		val local = meshOf(session, "child").localPositions
		session.deleteTargetKeepingRest(SelectionTarget.Deformer(warpId), cascade = false)
		assertEquals(DeformerId("ghost"), session.model.value.drawables.first { drawable -> drawable.id == DrawableId("child") }.parentDeformerId, "the warp is deleted, its drawables re-homed to its parent")
		assertSame(local, meshOf(session, "child").localPositions, "the base is kept")
		assertEquals("notice.reparent.placesNotKept", session.notice.value?.messageKey, "and the rigger is told the art moved")
		assertEquals(listOf("1"), session.notice.value?.arguments, "with the count")
	}
}