package org.umamo.edit.structure

import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SessionTestModels.deleteModel
import org.umamo.edit.SessionTestModels.model
import org.umamo.edit.subtreeTargets
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Session-level tests for the target toggles in SessionTargetEdits.kt: rename, visibility, selectable, and
 * the subtree variants - each one undo step, each scrubbing exactly the entities the outliner shows.
 */
class SessionTargetEditsTest {
	/**
	 * Renaming a target through the session is one undoable, dirtying step; undo restores the old name.
	 */
	@Test
	fun renameTargetIsOneUndoStep() {
		val session = EditorSession(model())

		session.rename(SelectionTarget.Part(PartId("a")), "Head")
		session.rename(SelectionTarget.Drawable(DrawableId("d")), "Eye")
		session.rename(SelectionTarget.Deformer(DeformerId("w")), "JawWarp")

		assertEquals("Head", session.model.value.parts.first { it.id == PartId("a") }.name)
		assertEquals("Eye", session.model.value.drawables.first { it.id == DrawableId("d") }.name)
		assertEquals("JawWarp", session.model.value.deformers.first { it.id == DeformerId("w") }.name)
		assertTrue(session.dirty.value)

		session.undo()
		assertEquals("Warp", session.model.value.deformers.first { it.id == DeformerId("w") }.name)
	}

	/**
	 * A blank rename is ignored (no step, no name change), and renaming to the current name is a no-op.
	 */
	@Test
	fun blankOrNoOpRenameRecordsNothing() {
		val session = EditorSession(model())

		session.rename(SelectionTarget.Part(PartId("a")), "   ")
		session.rename(SelectionTarget.Part(PartId("a")), "A")

		assertFalse(session.canUndo.value)
		assertEquals("A", session.model.value.parts.first { it.id == PartId("a") }.name)
	}

	/**
	 * toggleVisibility flips one target's eyeball as one step; a deformer target is a no-op.
	 */
	@Test
	fun toggleVisibilityTargetFlipsOneEntity() {
		val session = EditorSession(model())

		session.toggleVisibility(SelectionTarget.Drawable(DrawableId("d")))
		assertFalse(session.model.value.drawables.first { it.id == DrawableId("d") }.isVisible)

		session.toggleVisibility(SelectionTarget.Deformer(DeformerId("w")))
		// No visibility flag on a deformer, so the second toggle recorded nothing.
		assertTrue(session.canUndo.value)
		session.undo()
		assertTrue(session.model.value.drawables.first { it.id == DrawableId("d") }.isVisible)
		assertFalse(session.canUndo.value)
	}

	/**
	 * toggleSelectable flips a part/drawable/deformer selectability as one step (deformers have the flag
	 * too, unlike visibility); undo restores it, and the model exposes the flag through selectableOf.
	 */
	@Test
	fun toggleSelectableFlipsAnyTargetKind() {
		val session = EditorSession(model())

		session.toggleSelectable(SelectionTarget.Part(PartId("a")))
		session.toggleSelectable(SelectionTarget.Drawable(DrawableId("d")))
		session.toggleSelectable(SelectionTarget.Deformer(DeformerId("w")))

		assertFalse(session.model.value.parts.first { it.id == PartId("a") }.isSelectable)
		assertFalse(session.model.value.drawables.first { it.id == DrawableId("d") }.isSelectable)
		assertFalse(session.model.value.deformers.first { it.id == DeformerId("w") }.isSelectable)
		assertFalse(session.model.value.selectableOf(SelectionTarget.Deformer(DeformerId("w"))))

		session.undo()
		assertTrue(session.model.value.deformers.first { it.id == DeformerId("w") }.isSelectable)
	}

	/**
	 * subtreeTargets mirrors the outliner's tree: a part yields its descendant parts plus their drawables,
	 * a deformer yields nested deformers only (never the drawables bound via parentDeformerId), a drawable
	 * is a leaf, and a malformed parent cycle still terminates with each deformer yielded once.
	 */
	@Test
	fun subtreeTargetsEnumeratesWhatTheOutlinerShows() {
		val fixture = deleteModel()

		val partSubtree = fixture.subtreeTargets(SelectionTarget.Part(PartId("A")))
		assertEquals(SelectionTarget.Part(PartId("A")), partSubtree.first(), "the clicked target leads the list")
		assertEquals(
			setOf<SelectionTarget>(
				SelectionTarget.Part(PartId("A")),
				SelectionTarget.Part(PartId("B")),
				SelectionTarget.Drawable(DrawableId("d1")),
				SelectionTarget.Drawable(DrawableId("d2")),
			),
			partSubtree.toSet(),
			"a part subtree is its descendant parts plus their drawables",
		)

		assertEquals(
			setOf<SelectionTarget>(SelectionTarget.Deformer(DeformerId("w")), SelectionTarget.Deformer(DeformerId("w2"))),
			fixture.subtreeTargets(SelectionTarget.Deformer(DeformerId("w"))).toSet(),
			"a deformer subtree is nested deformers only, never the drawables bound to them",
		)

		assertEquals(
			listOf<SelectionTarget>(SelectionTarget.Drawable(DrawableId("d3"))),
			fixture.subtreeTargets(SelectionTarget.Drawable(DrawableId("d3"))),
			"a drawable is a leaf",
		)

		// A malformed w <-> w2 parent cycle must terminate and yield each deformer once.
		val cyclic =
			fixture.copy(
				deformers =
					fixture.deformers.map { deformer ->
						if (deformer is Deformer.Warp && deformer.id == DeformerId("w")) {
							deformer.copy(parent = DeformerId("w2"))
						} else {
							deformer
						}
					},
			)
		assertEquals(
			setOf<SelectionTarget>(SelectionTarget.Deformer(DeformerId("w")), SelectionTarget.Deformer(DeformerId("w2"))),
			cyclic.subtreeTargets(SelectionTarget.Deformer(DeformerId("w"))).toSet(),
			"a parent cycle terminates",
		)
	}

	/**
	 * A Shift+Click subtree selectable toggle covers the part, its descendant parts, and their drawables as
	 * ONE undo step - a single undo restores the whole cascade - while everything outside the subtree is
	 * untouched.
	 */
	@Test
	fun toggleSelectableSubtreeIsOneUndoStepOverThePartSubtree() {
		val session = EditorSession(deleteModel())

		session.toggleSelectableSubtree(SelectionTarget.Part(PartId("A")))

		val after = session.model.value
		assertFalse(after.parts.first { it.id == PartId("A") }.isSelectable)
		assertFalse(after.parts.first { it.id == PartId("B") }.isSelectable)
		assertFalse(after.drawables.first { it.id == DrawableId("d1") }.isSelectable)
		assertFalse(after.drawables.first { it.id == DrawableId("d2") }.isSelectable)
		assertTrue(after.parts.first { it.id == PartId("R") }.isSelectable, "the parent is outside the subtree")
		assertTrue(after.parts.first { it.id == PartId("T") }.isSelectable)
		assertTrue(after.drawables.first { it.id == DrawableId("d3") }.isSelectable)
		assertTrue(after.deformers.all { it.isSelectable }, "deformers are not part-subtree children")
		assertTrue(session.dirty.value)

		session.undo()
		val restored = session.model.value
		assertTrue(restored.parts.first { it.id == PartId("A") }.isSelectable)
		assertTrue(restored.parts.first { it.id == PartId("B") }.isSelectable)
		assertTrue(restored.drawables.first { it.id == DrawableId("d1") }.isSelectable)
		assertTrue(restored.drawables.first { it.id == DrawableId("d2") }.isSelectable)
		assertFalse(session.canUndo.value, "the whole cascade was one step")
	}

	/**
	 * The subtree toggle sets a uniform value (Blender parity), not a per-node flip: with d2 already
	 * unselectable, toggling on A drives ALL subtree entities to the clicked node's flipped state, and a
	 * second toggle brings them all back together.
	 */
	@Test
	fun toggleSelectableSubtreeUnifiesMixedInitialStates() {
		val session = EditorSession(deleteModel().withDrawableSelectable(DrawableId("d2"), false))

		session.toggleSelectableSubtree(SelectionTarget.Part(PartId("A")))
		val allOff = session.model.value
		assertFalse(allOff.parts.first { it.id == PartId("A") }.isSelectable)
		assertFalse(allOff.parts.first { it.id == PartId("B") }.isSelectable)
		assertFalse(allOff.drawables.first { it.id == DrawableId("d1") }.isSelectable)
		assertFalse(allOff.drawables.first { it.id == DrawableId("d2") }.isSelectable, "already-off d2 is not flipped back on")

		session.toggleSelectableSubtree(SelectionTarget.Part(PartId("A")))
		val allOn = session.model.value
		assertTrue(allOn.parts.first { it.id == PartId("A") }.isSelectable)
		assertTrue(allOn.parts.first { it.id == PartId("B") }.isSelectable)
		assertTrue(allOn.drawables.first { it.id == DrawableId("d1") }.isSelectable)
		assertTrue(allOn.drawables.first { it.id == DrawableId("d2") }.isSelectable)
	}

	/**
	 * A subtree toggle on a deformer covers its nested deformers only - the drawables bound to them via
	 * parentDeformerId live under their parts in the outliner and stay untouched.
	 */
	@Test
	fun toggleSelectableSubtreeOnDeformerCoversNestedDeformersOnly() {
		val session = EditorSession(deleteModel())

		session.toggleSelectableSubtree(SelectionTarget.Deformer(DeformerId("w")))

		val after = session.model.value
		assertFalse(after.deformers.first { it.id == DeformerId("w") }.isSelectable)
		assertFalse(after.deformers.first { it.id == DeformerId("w2") }.isSelectable)
		assertTrue(after.drawables.first { it.id == DrawableId("d1") }.isSelectable, "bound drawables are not tree children")
		assertTrue(after.drawables.first { it.id == DrawableId("d2") }.isSelectable)

		session.undo()
		assertTrue(session.model.value.deformers.all { it.isSelectable })
		assertFalse(session.canUndo.value)
	}

	/**
	 * The visibility subtree toggle hides the part subtree's parts and drawables as one step, and a
	 * deformer target records nothing at all (deformers have no visibility flag).
	 */
	@Test
	fun toggleVisibilitySubtreeHidesPartSubtreeAndSkipsDeformers() {
		val session = EditorSession(deleteModel())

		session.toggleVisibilitySubtree(SelectionTarget.Part(PartId("A")))

		val after = session.model.value
		assertFalse(after.parts.first { it.id == PartId("A") }.isVisible)
		assertFalse(after.parts.first { it.id == PartId("B") }.isVisible)
		assertFalse(after.drawables.first { it.id == DrawableId("d1") }.isVisible)
		assertFalse(after.drawables.first { it.id == DrawableId("d2") }.isVisible)
		assertTrue(after.parts.first { it.id == PartId("T") }.isVisible)
		assertTrue(after.drawables.first { it.id == DrawableId("d3") }.isVisible)

		session.undo()
		assertTrue(session.model.value.parts.first { it.id == PartId("A") }.isVisible)
		assertTrue(session.model.value.drawables.first { it.id == DrawableId("d2") }.isVisible)
		assertFalse(session.canUndo.value, "the whole cascade was one step")

		// A deformer target is a no-op: no visibility flag, so no history step is recorded.
		session.toggleVisibilitySubtree(SelectionTarget.Deformer(DeformerId("w")))
		assertFalse(session.canUndo.value)
	}

	/**
	 * On a drawable (a leaf) the subtree toggles degenerate to plain single-entity toggles.
	 */
	@Test
	fun toggleSubtreeOnDrawableIsAPlainSingleToggle() {
		val session = EditorSession(deleteModel())

		session.toggleVisibilitySubtree(SelectionTarget.Drawable(DrawableId("d3")))
		val afterVisibility = session.model.value
		assertFalse(afterVisibility.drawables.first { it.id == DrawableId("d3") }.isVisible)
		assertTrue(afterVisibility.drawables.first { it.id == DrawableId("d1") }.isVisible)
		assertTrue(afterVisibility.parts.first { it.id == PartId("T") }.isVisible, "the owning part is not the leaf's subtree")

		session.toggleSelectableSubtree(SelectionTarget.Drawable(DrawableId("d3")))
		assertFalse(session.model.value.drawables.first { it.id == DrawableId("d3") }.isSelectable)
		assertTrue(session.model.value.drawables.first { it.id == DrawableId("d1") }.isSelectable)

		session.undo()
		session.undo()
		assertTrue(session.model.value.drawables.first { it.id == DrawableId("d3") }.isVisible)
		assertTrue(session.model.value.drawables.first { it.id == DrawableId("d3") }.isSelectable)
		assertFalse(session.canUndo.value, "each leaf toggle was exactly one step")
	}

	/**
	 * Hide Unselected with one drawable selected hides each unselected branch at its topmost node, as one
	 * step: the sibling part B and the other root part T each get one flag, their contents keep theirs, and
	 * the selection's ancestors R and A stay shown.
	 */
	@Test
	fun hideUnselectedHidesTheTopmostUnselectedBranches() {
		val model = deleteModel()
		val selected = SelectionTarget.Drawable(DrawableId("d1"))
		assertEquals(listOf(SelectionTarget.Part(PartId("B")), SelectionTarget.Part(PartId("T"))), model.unselectedBranches(setOf(selected)))

		val session = EditorSession(model)
		session.setSelection(Selection(setOf(selected), selected))
		session.hideUnselected()

		assertEquals("change.visibility.hideUnselected", session.lastLabel())
		assertFalse(session.partVisible("B") || session.partVisible("T"))
		assertTrue(session.partVisible("R") && session.partVisible("A") && session.drawableVisible("d1"))
		assertTrue(session.drawableVisible("d2") && session.drawableVisible("d3"), "contents keep their own eyes")

		session.undo()
		assertTrue(session.partVisible("B") && session.partVisible("T"), "one undo step restores both branches")
	}

	/**
	 * A selected part keeps its whole subtree as it is, an eye already off inside it included.
	 */
	@Test
	fun hideUnselectedLeavesASelectedPartsContentsAlone() {
		val model = deleteModel().withDrawableVisibility(DrawableId("d2"), false)

		assertEquals(listOf(SelectionTarget.Part(PartId("T"))), model.unselectedBranches(setOf(SelectionTarget.Part(PartId("A")))))
	}

	/**
	 * A selected deformer keeps its part and the parts above it shown, so its lattice stays drawn, while the
	 * part's unselected art is hidden.
	 */
	@Test
	fun hideUnselectedKeepsASelectedDeformersPartShown() {
		val base = deleteModel()
		val model =
			base.copy(
				deformers =
					base.deformers.map { deformer ->
						if (deformer.id == DeformerId("w") && deformer is Deformer.Warp) deformer.copy(partId = PartId("A")) else deformer
					},
			)

		assertEquals(
			listOf(SelectionTarget.Part(PartId("B")), SelectionTarget.Drawable(DrawableId("d1")), SelectionTarget.Part(PartId("T"))),
			model.unselectedBranches(setOf(SelectionTarget.Deformer(DeformerId("w")))),
		)
	}

	/**
	 * A branch whose eye is already off is neither listed nor entered, so its contents are untouched.
	 */
	@Test
	fun hideUnselectedSkipsAnAlreadyHiddenBranch() {
		val model = deleteModel().withPartVisibility(PartId("T"), false)

		assertEquals(listOf(SelectionTarget.Part(PartId("B"))), model.unselectedBranches(setOf(SelectionTarget.Drawable(DrawableId("d1")))))
	}

	/**
	 * With nothing selected, or a selection naming only objects that are gone, there is nothing to keep, so
	 * nothing is hidden and no step is recorded.
	 */
	@Test
	fun hideUnselectedWithNothingToKeepRecordsNothing() {
		val session = EditorSession(deleteModel())

		session.hideUnselected()
		assertFalse(session.canUndo.value)

		val gone = SelectionTarget.Drawable(DrawableId("gone"))
		session.setSelection(Selection(setOf(gone), gone))
		val stepsBefore = session.historyView.value.steps.size
		session.hideUnselected()

		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertTrue(session.partVisible("R") && session.partVisible("T"))
	}

	/**
	 * Unhide All turns on every part's and drawable's eye as one step and leaves the selection alone; with
	 * nothing hidden it records nothing.
	 */
	@Test
	fun unhideAllShowsEverythingAsOneStep() {
		val hidden =
			deleteModel()
				.withPartVisibility(PartId("B"), false)
				.withDrawableVisibility(DrawableId("d2"), false)
				.withDrawableVisibility(DrawableId("d3"), false)
		assertEquals(
			listOf(SelectionTarget.Part(PartId("B")), SelectionTarget.Drawable(DrawableId("d2")), SelectionTarget.Drawable(DrawableId("d3"))),
			hidden.hiddenTargets(),
		)

		val session = EditorSession(hidden)
		val selected = SelectionTarget.Drawable(DrawableId("d1"))
		session.setSelection(Selection(setOf(selected), selected))
		session.unhideAll()

		assertEquals("change.visibility.unhideAll", session.lastLabel())
		assertTrue(session.partVisible("B") && session.drawableVisible("d2") && session.drawableVisible("d3"))
		assertEquals(Selection(setOf(selected), selected), session.selection.value)

		val stepsAfter = session.historyView.value.steps.size
		session.unhideAll()
		assertEquals(stepsAfter, session.historyView.value.steps.size, "nothing hidden records nothing")

		session.undo()
		assertFalse(session.partVisible("B") || session.drawableVisible("d2") || session.drawableVisible("d3"))
	}

	/** Whether part [rawId]'s own eye is on. */
	private fun EditorSession.partVisible(rawId: String): Boolean = model.value.parts.first { part -> part.id == PartId(rawId) }.isVisible

	/** Whether drawable [rawId]'s own eye is on. */
	private fun EditorSession.drawableVisible(rawId: String): Boolean =
		model.value.drawables.first { drawable -> drawable.id == DrawableId(rawId) }.isVisible

	/** The label key of the step the history cursor sits on. */
	private fun EditorSession.lastLabel(): String? = historyView.value.let { view -> view.steps[view.cursor].labelKey }
}