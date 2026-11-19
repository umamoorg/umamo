package org.umamo.ui.transform

import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.relation.RelationSlot
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the relation write path: a binding the slot refuses or already holds records nothing, Copy to Selected gives
 * every selected peer the owner's binding in one step (keeping art in place for a parent deformer and offering Keep in
 * Place), passes over a peer that already holds it, and skips one the binding is refused for with a notice.
 */
class RelationBindingTest {
	private val parameterId = ParameterId("P")
	private val outer = DeformerId("outer")
	private val inner = DeformerId("inner")
	private val left = PartId("left")
	private val right = PartId("right")

	/**
	 * A one-cell warp over [corners], keyed on P at both ends with the same lattice.
	 *
	 * @param DeformerId id The id.
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
	 * A root drawable over a triangle at [x], 150.
	 *
	 * @param String id The id.
	 * @param Float x Its left edge in canvas pixels.
	 * @param List maskedBy Its masks.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: String, x: Float, maskedBy: List<DrawableId> = emptyList()): Drawable {
		val canvas = floatArrayOf(x, 150f, x + 20f, 150f, x + 20f, 170f)

		return Drawable(
			id = DrawableId(id),
			name = id,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = maskedBy,
			mesh = DrawableMesh(positions = canvas, localPositions = canvas, uvs = FloatArray(canvas.size), indices = intArrayOf(0, 1, 2)),
			geometryGrid = null,
		)
	}

	/**
	 * Two nested warps over canvas pixels 100..300; parts left (holding a, b) and right (holding c); drawable a masked
	 * by c.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameterId, "P", -1f, 1f, 0f)),
			parts =
				listOf(
					Part(left, "Left", listOf(OrgChild.Drawable(DrawableId("a")), OrgChild.Drawable(DrawableId("b")))),
					Part(right, "Right", listOf(OrgChild.Drawable(DrawableId("c")))),
				),
			deformers =
				listOf(
					warp(outer, null, floatArrayOf(100f, 100f, 300f, 100f, 100f, 300f, 300f, 300f)),
					warp(inner, outer, floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f)),
				),
			drawables = listOf(drawable("a", 120f, listOf(DrawableId("c"))), drawable("b", 160f), drawable("c", 200f)),
			rootChildren = listOf(OrgChild.Part(left), OrgChild.Part(right)),
			rootPartId = null,
		).withDerivedRenderRoot()

	/**
	 * A session over [model] with [targets] selected, the first active.
	 *
	 * @param PuppetModel model The model.
	 * @param List<SelectionTarget> targets The selection.
	 * @return EditorSession The session.
	 */
	private fun sessionSelecting(model: PuppetModel, targets: List<SelectionTarget>): EditorSession {
		val session = EditorSession(model)
		session.setSelection(Selection(targets.toSet(), targets.firstOrNull()))

		return session
	}

	/**
	 * Where drawable [id] sits on screen in [model] at rest.
	 *
	 * @param PuppetModel model The model.
	 * @param String id The drawable.
	 * @return FloatArray The world positions.
	 */
	private fun worldAt(model: PuppetModel, id: String): FloatArray = assertNotNull(captureDrawableWorld(model, emptyMap(), DrawableId(id))).world

	/**
	 * Asserts two world shapes agree within a hundredth of a pixel.
	 *
	 * @param FloatArray expected The shape before.
	 * @param FloatArray actual The shape after.
	 * @param String label The case.
	 */
	private fun assertSamePlace(expected: FloatArray, actual: FloatArray, label: String) {
		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= 0.01f, "$label: component $componentIndex moved")
		}
	}

	@Test
	fun aBindingTheSlotRefusesOrAlreadyHoldsRecordsNothing() {
		val session = EditorSession(model())

		session.bindRelation(RelationSlot.ParentDeformer(SelectionTarget.Deformer(outer)), SelectionTarget.Deformer(inner))
		session.bindRelation(RelationSlot.ParentPart(SelectionTarget.Drawable(DrawableId("a"))), SelectionTarget.Part(left))
		session.bindRelation(RelationSlot.Masks(SelectionTarget.Drawable(DrawableId("a"))), SelectionTarget.Drawable(DrawableId("a")))
		session.bindRelation(RelationSlot.ParentDeformer(SelectionTarget.Deformer(inner)), SelectionTarget.Deformer(outer))

		assertFalse(session.canUndo.value, "a cycle, the part it is already in, a self-mask, and the parent it has are each refused")
	}

	@Test
	fun aMaskListAddsAndARowRemoves() {
		val session = EditorSession(model())
		val owner = SelectionTarget.Drawable(DrawableId("b"))

		session.bindRelation(RelationSlot.Masks(owner), SelectionTarget.Drawable(DrawableId("c")))
		assertEquals(listOf(DrawableId("c")), session.model.value.drawables.first { drawable -> drawable.id == DrawableId("b") }.maskedBy)

		session.bindRelation(RelationSlot.Mask(owner, SelectionTarget.Drawable(DrawableId("c"))), null)
		assertTrue(session.model.value.drawables.first { drawable -> drawable.id == DrawableId("b") }.maskedBy.isEmpty())
	}

	@Test
	fun copyingAParentDeformerKeepsEveryPeerInPlaceAsOneStep() {
		val before = model().let { base -> base.copy(drawables = base.drawables.map { drawable -> if (drawable.id == DrawableId("a")) drawable.copy(parentDeformerId = null) else drawable }) }
		val bound = EditorSession(before).apply { setDrawableParentKeepingRest(DrawableId("a"), inner) }.model.value
		val owner = SelectionTarget.Drawable(DrawableId("a"))
		val session = sessionSelecting(bound, listOf(owner, SelectionTarget.Drawable(DrawableId("b")), SelectionTarget.Drawable(DrawableId("c"))))

		session.copyRelationToSelected(RelationSlot.ParentDeformer(owner), "area")

		for (id in listOf("b", "c")) {
			assertEquals(inner, session.model.value.drawables.first { drawable -> drawable.id == DrawableId(id) }.parentDeformerId, id)
			assertSamePlace(worldAt(bound, id), worldAt(session.model.value, id), id)
		}

		assertEquals("area", assertNotNull(session.adjustableOperation.value).areaId, "Keep in Place is offered")
		session.undo()
		assertEquals(bound, session.model.value, "one step undoes the whole copy")
	}

	@Test
	fun copyingSkipsAPeerTheBindingIsRefusedForAndSaysSo() {
		val owner = SelectionTarget.Deformer(inner)
		// inner sits under outer; copying that parent to outer itself would nest outer under itself.
		val session = sessionSelecting(model(), listOf(owner, SelectionTarget.Deformer(outer)))
		val before = session.model.value

		session.copyRelationToSelected(RelationSlot.ParentDeformer(owner))

		assertSame(before, session.model.value, "nothing else took it, so nothing is recorded")
		assertEquals("notice.relation.copySkipped", session.notice.value?.messageKey)
		assertEquals(listOf("1"), session.notice.value?.arguments)
	}

	@Test
	fun copyingAnParentPartMovesEveryPeerAndPassesOverOneAlreadyThere() {
		val owner = SelectionTarget.Drawable(DrawableId("c"))
		val session = sessionSelecting(model(), listOf(owner, SelectionTarget.Drawable(DrawableId("a")), SelectionTarget.Drawable(DrawableId("b"))))

		session.copyRelationToSelected(RelationSlot.ParentPart(owner))

		val partOf = session.model.value.partByDrawable()
		assertEquals(right, partOf[DrawableId("a")])
		assertEquals(right, partOf[DrawableId("b")])
		assertNull(session.notice.value, "moving is not refusing")
		session.undo()
		assertEquals(left, session.model.value.partByDrawable()[DrawableId("a")], "one step")
	}

	@Test
	fun copyingAMaskRowAddsItsEntryToEachPeerThatLacksIt() {
		val owner = SelectionTarget.Drawable(DrawableId("a"))
		val session = sessionSelecting(model(), listOf(owner, SelectionTarget.Drawable(DrawableId("b")), SelectionTarget.Drawable(DrawableId("c"))))

		session.copyRelationToSelected(RelationSlot.Mask(owner, SelectionTarget.Drawable(DrawableId("c"))))

		assertEquals(listOf(DrawableId("c")), session.model.value.drawables.first { drawable -> drawable.id == DrawableId("b") }.maskedBy)
		assertTrue(session.model.value.drawables.first { drawable -> drawable.id == DrawableId("c") }.maskedBy.isEmpty(), "c cannot mask itself")
		assertEquals(listOf("1"), session.notice.value?.arguments, "and is the one skipped")
	}
}