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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies the pure [SelectionOps] gesture transforms and the [Selection] invariant that the active
 * target is always a member of the selection, or null when empty.
 */
class SelectionOpsTest {
	private val partA = SelectionTarget.Part(PartId("a"))
	private val drawableB = SelectionTarget.Drawable(DrawableId("b"))
	private val drawableC = SelectionTarget.Drawable(DrawableId("c"))

	/**
	 * A fresh selection is empty with no active target.
	 */
	@Test
	fun emptySelectionHasNoActive() {
		val selection = Selection()
		assertTrue(selection.isEmpty)
		assertEquals(0, selection.size)
		assertNull(selection.active)
	}

	/**
	 * replace drops any prior selection and makes the new target the sole, active member.
	 */
	@Test
	fun replaceSelectsOnlyTheTarget() {
		val first = SelectionOps.replace(partA)
		val second = SelectionOps.replace(drawableB)

		assertEquals(setOf(drawableB), second.targets)
		assertEquals(drawableB, second.active)
		assertFalse(partA in second)
		// The original is untouched (immutability).
		assertEquals(setOf(partA), first.targets)
	}

	/**
	 * toggle adds an absent target (making it active) and removes a present one.
	 */
	@Test
	fun toggleAddsThenRemoves() {
		val added = SelectionOps.toggle(Selection(), partA)
		assertEquals(setOf(partA), added.targets)
		assertEquals(partA, added.active)

		val removed = SelectionOps.toggle(added, partA)
		assertTrue(removed.isEmpty)
		assertNull(removed.active)
	}

	/**
	 * Removing the active target by toggle falls the active back to a remaining member, never dangling.
	 */
	@Test
	fun toggleRemovingActiveFallsBackToRemaining() {
		val twoSelected = SelectionOps.add(SelectionOps.replace(partA), drawableB)
		assertEquals(drawableB, twoSelected.active)

		val afterRemove = SelectionOps.toggle(twoSelected, drawableB)

		assertEquals(setOf(partA), afterRemove.targets)
		assertEquals(partA, afterRemove.active, "active must remain a member of the selection")
	}

	/**
	 * add extends the selection without removing anything and promotes the added target to active.
	 */
	@Test
	fun addExtendsAndActivates() {
		val selection = SelectionOps.add(SelectionOps.add(SelectionOps.replace(partA), drawableB), drawableC)

		assertEquals(setOf(partA, drawableB, drawableC), selection.targets)
		assertEquals(drawableC, selection.active)
		assertEquals(3, selection.size)
	}

	/**
	 * Adding an already-present target keeps the set unchanged but re-promotes it to active.
	 */
	@Test
	fun addExistingPromotesToActive() {
		val start = SelectionOps.add(SelectionOps.replace(partA), drawableB)
		val reAdded = SelectionOps.add(start, partA)

		assertEquals(setOf(partA, drawableB), reAdded.targets)
		assertEquals(partA, reAdded.active)
	}

	/**
	 * clear returns an empty selection regardless of input.
	 */
	@Test
	fun clearEmptiesSelection() {
		assertTrue(SelectionOps.clear().isEmpty)
	}

	/**
	 * contains reflects membership for the operator-in form used by panels and the highlight bridge.
	 */
	@Test
	fun containsReflectsMembership() {
		val selection = SelectionOps.add(SelectionOps.replace(partA), drawableB)
		assertTrue(partA in selection)
		assertTrue(drawableB in selection)
		assertFalse(drawableC in selection)
	}

	// A small model for the object Select All / Invert domain: one selectable part, one selectable deformer,
	// one selectable drawable, and one LOCKED drawable that must be excluded.
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(PartId("a"), "A", children = emptyList())),
			deformers =
				listOf(
					Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, keyforms = null),
				),
			drawables =
				listOf(
					Drawable(DrawableId("b"), "B", parentDeformerId = null, blendMode = BlendMode.Normal, maskedBy = emptyList(), mesh = null, keyforms = null),
					Drawable(
						DrawableId("c"),
						"C",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = null,
						keyforms = null,
						isSelectable = false,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** selectAll gathers every selectable entity (a locked one is excluded), with the active a member. */
	@Test
	fun selectAllGathersSelectableEntities() {
		val all = SelectionOps.selectAll(Selection(), model())
		assertEquals(
			setOf<SelectionTarget>(
				SelectionTarget.Part(PartId("a")),
				SelectionTarget.Deformer(DeformerId("w")),
				SelectionTarget.Drawable(DrawableId("b")),
			),
			all.targets,
			"the locked drawable c is excluded",
		)
		assertTrue(all.active in all.targets, "active is a member of the selection")
	}

	/** selectAll keeps the current active target when it stays selected. */
	@Test
	fun selectAllKeepsExistingActive() {
		val all = SelectionOps.selectAll(SelectionOps.replace(SelectionTarget.Part(PartId("a"))), model())
		assertEquals(SelectionTarget.Part(PartId("a")), all.active)
	}

	/** invert complements within the selectable domain and drops the now-deselected active target. */
	@Test
	fun invertComplementsSelectableDomainAndDropsActive() {
		val inverted = SelectionOps.invert(SelectionOps.replace(SelectionTarget.Part(PartId("a"))), model())
		assertEquals(
			setOf<SelectionTarget>(SelectionTarget.Deformer(DeformerId("w")), SelectionTarget.Drawable(DrawableId("b"))),
			inverted.targets,
		)
		assertNull(inverted.active, "the previously active (now deselected) target drops")
	}
}
