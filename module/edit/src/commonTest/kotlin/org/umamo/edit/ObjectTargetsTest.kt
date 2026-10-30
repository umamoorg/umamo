package org.umamo.edit

import org.umamo.edit.structure.duplicateSelectedObjects
import org.umamo.edit.transform.beginObjectOperator
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
import org.umamo.runtime.model.RotationPivotForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what an Object-mode transform and Shift+D act on once warps are objects: a meshed drawable and a warp with a
 * rest lattice transform, everything else is skipped, an item inside a moving warp follows it rather than moving
 * twice, and a duplicate copies warps too, its copies keeping the hierarchy among themselves.
 */
class ObjectTargetsTest {
	private val parameter = ParameterId("P")
	private val part = PartId("part")
	private val art = DrawableId("art")
	private val inner = DrawableId("inner")
	private val cage = DeformerId("cage")
	private val child = DeformerId("child")
	private val bare = DeformerId("bare")
	private val turn = DeformerId("turn")

	/**
	 * A 1 x 1 warp keyed at -1 and 1 on one lattice, or with no grid when [keyed] is false.
	 *
	 * @param DeformerId id The warp.
	 * @param DeformerId? parent Its parent.
	 * @param Boolean keyed Whether it carries a lattice.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, parent: DeformerId?, keyed: Boolean = true): Deformer.Warp {
		val points = floatArrayOf(0f, 0f, 40f, 0f, 0f, 40f, 40f, 40f)

		return SessionTestModels.keyedWarp(id, 1, 1, parameter, if (keyed) floatArrayOf(-1f, 1f) else null, parent, part) { points }
	}

	/**
	 * A quad of art under [parent].
	 *
	 * @param DrawableId id The drawable.
	 * @param DeformerId? parent Its parent deformer.
	 * @return Drawable The drawable.
	 */
	private fun quad(id: DrawableId, parent: DeformerId?): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = parent,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 1f, 0f, 1f, 1f, 0f, 1f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
			geometryGrid = null,
		)

	/**
	 * The rig: art at the root, a cage with art and a child warp inside it, a warp with no lattice, and a rotation.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(parameter, "P", -1f, 1f, 0f)),
			parts = listOf(Part(part, "Part", listOf(OrgChild.Drawable(art), OrgChild.Drawable(inner)))),
			deformers =
				listOf(
					warp(cage, null),
					warp(child, cage),
					warp(bare, null, keyed = false),
					Deformer.Rotation(
						id = turn,
						name = "turn",
						parent = null,
						partId = part,
						baseAngle = 0f,
						geometryGrid =
							KeyformGrid(
								listOf(KeyformAxis(parameter, floatArrayOf(-1f, 1f))),
								listOf(KeyformCell(intArrayOf(0), RotationPivotForm(0f, 0f, 0f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(0f, 0f, 0f, 1f))),
							),
					),
				),
			drawables = listOf(quad(art, null), quad(inner, cage)),
			rootChildren = listOf(OrgChild.Part(part)),
			rootPartId = null,
		)

	/**
	 * A selection of [targets], the last active.
	 *
	 * @param List<SelectionTarget> targets The targets.
	 * @return Selection The selection.
	 */
	private fun selecting(vararg targets: SelectionTarget): Selection = Selection(targets.toSet(), targets.lastOrNull())

	@Test
	fun drawablesAndWarpsTransformAndTheRestIsSkipped() {
		val targets = assertNotNull(eligibleTransformTargets(selecting(SelectionTarget.Drawable(art), SelectionTarget.Deformer(cage), SelectionTarget.Deformer(bare), SelectionTarget.Deformer(turn), SelectionTarget.Part(part)), model()))

		assertEquals(listOf(art), targets.drawableIds)
		assertEquals(listOf(cage), targets.warpIds, "a warp with no lattice, a rotation, and a part are skipped")
		assertNull(eligibleTransformTargets(selecting(SelectionTarget.Deformer(bare), SelectionTarget.Deformer(turn)), model()), "nothing transformable blocks the gesture")
	}

	@Test
	fun anItemInsideAMovingWarpMovesOnceWithIt() {
		val withCage = assertNotNull(eligibleTransformTargets(selecting(SelectionTarget.Drawable(inner), SelectionTarget.Deformer(child), SelectionTarget.Deformer(cage)), model()))
		assertTrue(withCage.drawableIds.isEmpty(), "the art inside follows the cage")
		assertEquals(listOf(cage), withCage.warpIds, "and so does the warp inside")

		val alone = assertNotNull(eligibleTransformTargets(selecting(SelectionTarget.Drawable(inner), SelectionTarget.Deformer(child)), model()))
		assertEquals(listOf(inner), alone.drawableIds, "without the cage, they move on their own")
		assertEquals(listOf(child), alone.warpIds)
	}

	@Test
	fun aWarpAloneLatchesTheObjectTransform() {
		val session = EditorSession(model())
		session.setSelection(selecting(SelectionTarget.Deformer(cage)))
		session.beginObjectOperator(MeshOperatorKind.Grab, "area")
		assertEquals(MeshOperatorKind.Grab, session.activeObjectOperator.value?.kind, "a warp is something to move")

		session.clearObjectOperator()
		session.setSelection(selecting(SelectionTarget.Deformer(turn)))
		session.beginObjectOperator(MeshOperatorKind.Grab, "area")
		assertNull(session.activeObjectOperator.value)
		assertEquals("notice.transform.onlyDrawables", session.notice.value?.messageKey, "a rotation alone says what can move")
	}

	@Test
	fun theTransformChangeListsItsWarps() {
		val change = MeshChange.TransformObjects(listOf(art), MeshOperatorKind.Rotate, listOf(cage))

		assertEquals("change.object.rotate", change.labelKey)
		assertEquals(listOf(cage), change.deformerIds)
		assertEquals("change.drawable.duplicate", DrawableChange.Duplicate(listOf(art)).labelKey)
		assertEquals("change.object.duplicate", DrawableChange.Duplicate(listOf(art), listOf(cage)).labelKey)
	}

	@Test
	fun aDuplicateCopiesWarpsAndKeepsTheHierarchyAmongTheCopies() {
		val session = EditorSession(model())
		session.setSelection(selecting(SelectionTarget.Drawable(inner), SelectionTarget.Deformer(cage)))
		val before = session.model.value
		val stepsBefore = session.historyView.value.steps.size

		val copies = session.duplicateSelectedObjects()

		val model = session.model.value
		val cageCopy = DeformerId("cage.001")
		val innerCopy = DrawableId("inner.001")
		assertEquals(listOf<SelectionTarget>(SelectionTarget.Drawable(innerCopy), SelectionTarget.Deformer(cageCopy)), copies)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
		assertEquals("change.object.duplicate", session.historyView.value.steps.last().labelKey)
		val copiedWarp = model.deformers.single { deformer -> deformer.id == cageCopy } as Deformer.Warp
		val sourceWarp = model.deformers.single { deformer -> deformer.id == cage } as Deformer.Warp
		assertNull(copiedWarp.parent, "the cage copy keeps its source's parent")
		assertSame(sourceWarp.geometryGrid!!.cells.first().form, copiedWarp.geometryGrid!!.cells.first().form, "with the same forms")
		assertEquals(cageCopy, model.drawables.single { drawable -> drawable.id == innerCopy }.parentDeformerId, "the art copy goes inside the cage copy")
		assertEquals(cage, model.drawables.single { drawable -> drawable.id == inner }.parentDeformerId, "the original stays inside the original")
		assertEquals(cage, model.deformers.single { deformer -> deformer.id == child }.parent, "an unselected child stays with the original, uncopied")
		assertTrue(model.deformers.none { deformer -> deformer.id == DeformerId("child.001") })
		assertEquals(SelectionTarget.Deformer(cageCopy), session.selection.value.active, "the active original's copy is active")

		session.undo()
		assertSame(before, session.model.value, "undo removes every copy")
	}

	@Test
	fun aCopiedChildWarpGoesUnderItsParentsCopy() {
		val session = EditorSession(model())
		session.setSelection(selecting(SelectionTarget.Deformer(child), SelectionTarget.Deformer(cage)))

		session.duplicateSelectedObjects()

		val model = session.model.value
		assertEquals(DeformerId("cage.001"), model.deformers.single { deformer -> deformer.id == DeformerId("child.001") }.parent)
	}
}