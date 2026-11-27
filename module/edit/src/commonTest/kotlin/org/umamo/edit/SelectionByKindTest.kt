package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Select All of Type and Invert Selection of Type: [SelectionOps.selectAllOfKind] replaces the selection with one
 * kind's selectable targets, [SelectionOps.invertKind] flips that kind alone, and the session methods record them in
 * Object mode.
 */
class SelectionByKindTest {
	private val part = SelectionTarget.Part(PartId("part"))
	private val drawable = SelectionTarget.Drawable(DrawableId("drawable"))
	private val lockedDrawable = SelectionTarget.Drawable(DrawableId("lockedDrawable"))
	private val warp = SelectionTarget.Deformer(DeformerId("warp"))
	private val lockedWarp = SelectionTarget.Deformer(DeformerId("lockedWarp"))
	private val rotation = SelectionTarget.Deformer(DeformerId("rotation"))
	private val goneDeformer = SelectionTarget.Deformer(DeformerId("gone"))

	/**
	 * One part, one drawable and one locked drawable, one warp and one locked warp, and one rotation.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(PartId("part"), "Part", children = emptyList())),
			deformers =
				listOf(
					Deformer.Warp(DeformerId("warp"), "Warp", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, geometryGrid = null),
					Deformer.Warp(
						DeformerId("lockedWarp"),
						"Locked Warp",
						parent = null,
						partId = null,
						rows = 2,
						columns = 2,
						isQuadTransform = true,
						geometryGrid = null,
						isSelectable = false,
					),
					Deformer.Rotation(DeformerId("rotation"), "Rotation", parent = null, partId = null, baseAngle = 0f, geometryGrid = null),
				),
			drawables =
				listOf(
					Drawable(DrawableId("drawable"), "Drawable", parentDeformerId = null, blendMode = BlendMode.Normal, maskedBy = emptyList(), mesh = null, geometryGrid = null),
					Drawable(
						DrawableId("lockedDrawable"),
						"Locked Drawable",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = null,
						geometryGrid = null,
						isSelectable = false,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	@Test
	fun selectAllOfKindReplacesTheSelectionWithThatKindAlone() {
		val model = model()
		val selection = Selection(setOf(part, drawable), drawable)

		assertEquals(setOf<SelectionTarget>(part), SelectionOps.selectAllOfKind(selection, model, SelectableKind.Part).targets)
		assertEquals(setOf<SelectionTarget>(drawable), SelectionOps.selectAllOfKind(selection, model, SelectableKind.Drawable).targets)
		assertEquals(setOf<SelectionTarget>(warp), SelectionOps.selectAllOfKind(selection, model, SelectableKind.WarpDeformer).targets)
		assertEquals(setOf<SelectionTarget>(rotation), SelectionOps.selectAllOfKind(selection, model, SelectableKind.RotationDeformer).targets)
	}

	@Test
	fun selectAllOfKindKeepsTheActiveTargetOnlyWhenItIsOfThatKind() {
		val model = model()
		val selection = Selection(setOf(part, drawable), drawable)

		assertEquals(drawable, SelectionOps.selectAllOfKind(selection, model, SelectableKind.Drawable).active)
		assertEquals(warp, SelectionOps.selectAllOfKind(selection, model, SelectableKind.WarpDeformer).active, "falls back to the kind's last target")
	}

	@Test
	fun selectAllOfKindWithNothingOfThatKindEmptiesTheSelection() {
		val model = model().let { full -> full.copy(deformers = full.deformers.filterNot { deformer -> deformer is Deformer.Rotation }) }
		val result = SelectionOps.selectAllOfKind(Selection(setOf(part), part), model, SelectableKind.RotationDeformer)

		assertTrue(result.isEmpty)
		assertNull(result.active)
	}

	@Test
	fun invertKindFlipsThatKindAndLeavesTheOthersSelected() {
		val result = SelectionOps.invertKind(Selection(setOf(part, drawable), part), model(), SelectableKind.WarpDeformer)

		assertEquals(setOf<SelectionTarget>(part, drawable, warp), result.targets)
		assertEquals(part, result.active, "the active part survives an inversion of warps")
	}

	@Test
	fun invertKindTellsWarpsFromRotations() {
		val result = SelectionOps.invertKind(Selection(setOf(warp), warp), model(), SelectableKind.RotationDeformer)

		assertEquals(setOf<SelectionTarget>(warp, rotation), result.targets)
	}

	@Test
	fun invertKindDropsTheActiveTargetItDeselects() {
		val result = SelectionOps.invertKind(Selection(setOf(part, drawable), drawable), model(), SelectableKind.Drawable)

		assertEquals(setOf<SelectionTarget>(part), result.targets)
		assertNull(result.active)
	}

	@Test
	fun invertKindDropsASelectedLockedTargetOfThatKind() {
		val result = SelectionOps.invertKind(Selection(setOf(lockedWarp, lockedDrawable), lockedWarp), model(), SelectableKind.WarpDeformer)

		assertEquals(setOf<SelectionTarget>(lockedDrawable, warp), result.targets, "the locked warp drops, the locked drawable is another kind's")
	}

	@Test
	fun invertKindLeavesATargetWithNoKindAlone() {
		val result = SelectionOps.invertKind(Selection(setOf(goneDeformer), goneDeformer), model(), SelectableKind.WarpDeformer)

		assertEquals(setOf<SelectionTarget>(goneDeformer, warp), result.targets)
	}

	@Test
	fun theSessionRecordsEachAsOneUndoStepInObjectMode() {
		val session = EditorSession(model())
		session.setSelection(Selection(setOf(part), part))
		val selectionBefore = session.selection.value

		session.selectAllObjectsOfKind(SelectableKind.WarpDeformer)
		assertEquals(setOf<SelectionTarget>(warp), session.selection.value.targets)
		session.undo()
		assertEquals(selectionBefore, session.selection.value)

		session.invertObjectSelectionOfKind(SelectableKind.RotationDeformer)
		assertEquals(setOf<SelectionTarget>(part, rotation), session.selection.value.targets)
		session.undo()
		assertEquals(selectionBefore, session.selection.value)
	}
}