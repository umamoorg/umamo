package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RenderDrawable
import org.umamo.runtime.model.partByDrawable
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Verifies the snapshot-history core: that a document mutation is one undoable step that restores by
 * snapshot, that snapshots structurally share unchanged entities, that dirty-tracking is correct across
 * undo/redo, and that a selection gesture is its own step without dirtying the document.
 */
class EditorSessionTest {
	private val partA = Part(PartId("a"), "A", children = emptyList())
	private val partB = Part(PartId("b"), "B", children = emptyList())
	private val drawable =
		Drawable(
			id = DrawableId("d"),
			name = "d",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			keyforms = null,
		)

	private val warp =
		Deformer.Warp(
			id = DeformerId("w"),
			name = "Warp",
			parent = null,
			partId = null,
			rows = 2,
			columns = 2,
			isQuadTransform = true,
			keyforms = null,
		)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			// Drawable d lives under part a; the top level holds both parts.
			parts = listOf(partA.copy(children = listOf(OrgChild.Drawable(DrawableId("d")))), partB),
			deformers = listOf(warp),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Part(PartId("a")), OrgChild.Part(PartId("b"))),
			rootPartId = null,
		).withDerivedRenderRoot()

	/**
	 * A richer model for the delete tests: a part tree R > A > B plus a sibling T; three drawables (d1 under
	 * A bound to w2, d2 under B bound to w, d3 under T masked by d1); a w > w2 deformer chain; one glue
	 * pairing d1 and d2; and a flat render tree of d1, d2, d3 - so deletes can be checked against every kind
	 * of reference (part tree, render order, masks, glue, deformer binding).
	 *
	 * @return PuppetModel The fixture model.
	 */
	private fun deleteModel(): PuppetModel {
		// Org tree: R > A > (B, d1); B > d2; T > d3. d1 deformed by w2, d2 by w; d3 masked by d1; glue(d1,d2).
		val partB = Part(PartId("B"), "B", children = listOf(OrgChild.Drawable(DrawableId("d2"))))
		val partA = Part(PartId("A"), "A", children = listOf(OrgChild.Part(PartId("B")), OrgChild.Drawable(DrawableId("d1"))))
		val partR = Part(PartId("R"), "R", children = listOf(OrgChild.Part(PartId("A"))))
		val partT = Part(PartId("T"), "T", children = listOf(OrgChild.Drawable(DrawableId("d3"))))
		val warpW = Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, keyforms = null)
		val warpW2 =
			Deformer.Warp(DeformerId("w2"), "W2", parent = DeformerId("w"), partId = null, rows = 2, columns = 2, isQuadTransform = true, keyforms = null)

		fun mesh(rawId: String, deformer: DeformerId?, masks: List<DrawableId>): Drawable =
			Drawable(
				id = DrawableId(rawId),
				name = rawId,
				parentDeformerId = deformer,
				blendMode = BlendMode.Normal,
				maskedBy = masks,
				mesh = null,
				keyforms = null,
			)
		return PuppetModel(
			parameters = emptyList(),
			parts = listOf(partR, partA, partB, partT),
			deformers = listOf(warpW, warpW2),
			drawables =
				listOf(
					mesh("d1", DeformerId("w2"), emptyList()),
					mesh("d2", DeformerId("w"), emptyList()),
					mesh("d3", null, listOf(DrawableId("d1"))),
				),
			rootChildren = listOf(OrgChild.Part(PartId("R")), OrgChild.Part(PartId("T"))),
			rootPartId = PartId("R"),
			glues = listOf(Glue(DrawableId("d1"), DrawableId("d2"), emptyList(), null)),
		).withDerivedRenderRoot()
	}

	/**
	 * withPartVisibility replaces only the touched part and structurally shares the rest of the model.
	 */
	@Test
	fun mutationStructurallySharesUnchangedEntities() {
		val before = model()
		val after = before.withPartVisibility(PartId("a"), visible = false)

		assertFalse(after.parts.first { it.id == PartId("a") }.isVisible)
		// The untouched part and drawable are the very same instances (structural sharing).
		assertSame(before.parts.first { it.id == PartId("b") }, after.parts.first { it.id == PartId("b") })
		assertSame(before.drawables[0], after.drawables[0])
	}

	/**
	 * A no-op mutation returns the same model instance (so the session records nothing).
	 */
	@Test
	fun noOpMutationReturnsSameInstance() {
		val before = model()
		assertSame(before, before.withPartVisibility(PartId("a"), visible = true))
		assertSame(before, before.withPartVisibility(PartId("missing"), visible = false))
	}

	/**
	 * A document edit is one undo step; undo restores the prior model by snapshot and clears dirty,
	 * redo re-applies it. Dirty is reference-equality against the saved instance.
	 */
	@Test
	fun mutateThenUndoRedoTracksModelAndDirty() {
		val initial = model()
		val session = EditorSession(initial)

		assertFalse(session.dirty.value)
		assertFalse(session.canUndo.value)

		session.mutate(PartChange.SetVisibility(PartId("a"), false)) { it.withPartVisibility(PartId("a"), false) }

		assertTrue(session.dirty.value)
		assertTrue(session.canUndo.value)
		assertFalse(session.canRedo.value)
		assertFalse(session.model.value.parts.first { it.id == PartId("a") }.isVisible)

		session.undo()

		// Undo restored the exact prior instance, so dirty is false again.
		assertSame(initial, session.model.value)
		assertFalse(session.dirty.value)
		assertTrue(session.canRedo.value)

		session.redo()
		assertFalse(session.model.value.parts.first { it.id == PartId("a") }.isVisible)
		assertTrue(session.dirty.value)
	}

	/**
	 * A selection gesture is its own undo step, but it reuses the current model instance — so it never
	 * dirties the document, and undoing it restores the prior selection while leaving the model untouched.
	 */
	@Test
	fun selectionGestureIsUndoableButNotDirtying() {
		val session = EditorSession(model())
		val modelInstance = session.model.value

		session.setSelection(SelectionOps.replace(SelectionTarget.Part(PartId("a"))))

		assertTrue(session.canUndo.value)
		assertFalse(session.dirty.value, "a selection change must not dirty the document")
		assertSame(modelInstance, session.model.value)
		assertEquals(setOf(SelectionTarget.Part(PartId("a"))), session.selection.value.targets)

		session.undo()
		assertTrue(session.selection.value.isEmpty)
		assertSame(modelInstance, session.model.value)
	}

	/**
	 * A new edit after an undo discards the redo branch (linear history, the v1 choice).
	 */
	@Test
	fun editAfterUndoDiscardsRedo() {
		val session = EditorSession(model())
		session.mutate(PartChange.SetVisibility(PartId("a"), false)) { it.withPartVisibility(PartId("a"), false) }
		session.undo()
		assertTrue(session.canRedo.value)

		session.mutate(PartChange.SetVisibility(PartId("b"), false)) { it.withPartVisibility(PartId("b"), false) }
		assertFalse(session.canRedo.value)
	}

	/**
	 * markSaved moves the dirty baseline to the current model, so an edit re-dirties and undoing back to
	 * the saved instance clears it again.
	 */
	@Test
	fun markSavedMovesDirtyBaseline() {
		val session = EditorSession(model())
		session.mutate(PartChange.SetVisibility(PartId("a"), false)) { it.withPartVisibility(PartId("a"), false) }
		assertTrue(session.dirty.value)

		session.markSaved()
		assertFalse(session.dirty.value)

		session.undo()
		// Undid past the saved point, so it is dirty again (the saved instance is no longer current).
		assertTrue(session.dirty.value)
	}

	/**
	 * The history view projects the stack (seed plus each step's label key) with the live cursor, and
	 * jumpTo leaps directly to any step — restoring its model and selection — without walking one level at
	 * a time. The saved flag marks exactly the saved row.
	 */
	@Test
	fun historyViewProjectsStackAndJumpToLeapsAcrossSteps() {
		val session = EditorSession(model())
		session.mutate(PartChange.SetVisibility(PartId("a"), false)) { it.withPartVisibility(PartId("a"), false) }
		session.mutate(PartChange.Rename(PartId("b"), "B2")) { it.withPartName(PartId("b"), "B2") }
		session.markSaved()
		session.mutate(PartChange.SetVisibility(PartId("b"), false)) { it.withPartVisibility(PartId("b"), false) }

		val view = session.historyView.value
		// Seed (null label) plus three edits, cursor on the newest.
		assertEquals(4, view.steps.size)
		assertEquals(3, view.cursor)
		assertNull(view.steps[0].labelKey)
		assertEquals("change.part.visibility", view.steps[1].labelKey)
		assertEquals("change.part.rename", view.steps[2].labelKey)
		// Exactly one row is the saved baseline — the rename step that was current at markSaved.
		assertEquals(listOf(2), view.steps.indices.filter { view.steps[it].saved })

		// Jump back to the seed in one move: model and selection both restore, redo branch stays available.
		session.jumpTo(0)
		assertEquals(0, session.historyView.value.cursor)
		assertTrue(session.model.value.parts.first { it.id == PartId("a") }.isVisible)
		assertTrue(session.canRedo.value)

		// Jump forward past the saved point; the saved row no longer matches the live model, so it is dirty.
		session.jumpTo(3)
		assertEquals(3, session.historyView.value.cursor)
		assertTrue(session.dirty.value)
		assertNull(EditorSnapshotProbe.jumpResult(session, 3), "jumping to the current step is a no-op")
	}

	/**
	 * undo / redo are safe no-ops at the ends of the stack.
	 */
	@Test
	fun undoRedoAtBoundsAreNoOps() {
		val session = EditorSession(model())
		assertNull(EditorSnapshotProbe.undoResult(session))
		session.redo()
		assertFalse(session.canUndo.value)
		assertFalse(session.canRedo.value)
	}

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
	 * Moving a drawable re-homes it under another part; undo restores its old owner.
	 */
	@Test
	fun moveOrgChildRehomesDrawableToAnotherPart() {
		val session = EditorSession(model())

		session.moveOrgChild(OrgChild.Drawable(DrawableId("d")), newParentId = PartId("b"), before = null)
		assertEquals(PartId("b"), session.model.value.partByDrawable()[DrawableId("d")])

		session.undo()
		assertEquals(PartId("a"), session.model.value.partByDrawable()[DrawableId("d")])
	}

	/**
	 * Moving a drawable among its siblings reorders the org tree (the outliner) and the derived render
	 * order (the viewport, panel reversed back-to-front) consistently; undo restores both.
	 */
	@Test
	fun moveOrgChildReordersTreeAndDerivesRenderOrder() {
		val drawableE =
			Drawable(
				id = DrawableId("e"),
				name = "e",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				keyforms = null,
			)
		val layered =
			PuppetModel(
				parameters = emptyList(),
				parts = listOf(partA.copy(children = listOf(OrgChild.Drawable(DrawableId("d")), OrgChild.Drawable(DrawableId("e"))))),
				deformers = emptyList(),
				drawables = listOf(drawable, drawableE),
				rootChildren = listOf(OrgChild.Part(PartId("a"))),
				rootPartId = null,
			).withDerivedRenderRoot()
		val session = EditorSession(layered)

		// d is above e in the panel; move d to after e (append). The panel order flips, and the derived
		// render order (panel reversed) follows.
		session.moveOrgChild(OrgChild.Drawable(DrawableId("d")), newParentId = PartId("a"), before = null)
		assertEquals(
			listOf<OrgChild>(OrgChild.Drawable(DrawableId("e")), OrgChild.Drawable(DrawableId("d"))),
			session.model.value.parts.first { it.id == PartId("a") }.children,
		)
		assertEquals(
			listOf(DrawableId("d"), DrawableId("e")),
			session.model.value.renderRoot.children.filterIsInstance<RenderDrawable>().map { it.id },
		)

		session.undo()
		assertEquals(
			listOf<OrgChild>(OrgChild.Drawable(DrawableId("d")), OrgChild.Drawable(DrawableId("e"))),
			session.model.value.parts.first { it.id == PartId("a") }.children,
		)
	}

	/**
	 * A drawable can be interleaved between two parts at the root (Cubism's cross-kind reorder), so a loose
	 * mesh sits between folders rather than only being parented into one.
	 */
	@Test
	fun moveOrgChildInterleavesADrawableBetweenParts() {
		val session = EditorSession(model())

		// model()'s top level is [a, b] with d under a; move d to the root, between a and b.
		session.moveOrgChild(OrgChild.Drawable(DrawableId("d")), newParentId = null, before = OrgChild.Part(PartId("b")))
		assertEquals(
			listOf<OrgChild>(OrgChild.Part(PartId("a")), OrgChild.Drawable(DrawableId("d")), OrgChild.Part(PartId("b"))),
			session.model.value.rootChildren,
		)
		assertNull(session.model.value.partByDrawable()[DrawableId("d")], "now a root-level drawable, owned by no part")
	}

	/**
	 * Moving a part nests it under the new parent; a move that would form a cycle is refused.
	 */
	@Test
	fun moveOrgChildReparentsPartAndRefusesCycles() {
		val session = EditorSession(model())

		session.moveOrgChild(OrgChild.Part(PartId("b")), newParentId = PartId("a"), before = null)
		assertTrue(OrgChild.Part(PartId("b")) in session.model.value.parts.first { it.id == PartId("a") }.children)

		// a is now an ancestor of b, so moving a under b is a cycle and must be refused (no new step).
		val before = session.model.value
		session.moveOrgChild(OrgChild.Part(PartId("a")), newParentId = PartId("b"), before = null)
		assertSame(before, session.model.value)

		session.undo()
		assertFalse(OrgChild.Part(PartId("b")) in session.model.value.parts.first { it.id == PartId("a") }.children)
	}

	/**
	 * Moving a deformer re-nests it under the new parent; a move that would form a cycle is refused.
	 */
	@Test
	fun moveDeformerReparentsAndRefusesCycles() {
		val warp2 =
			Deformer.Warp(
				id = DeformerId("w2"),
				name = "Warp2",
				parent = null,
				partId = null,
				rows = 2,
				columns = 2,
				isQuadTransform = true,
				keyforms = null,
			)
		val nested =
			PuppetModel(
				parameters = emptyList(),
				parts = listOf(partA, partB),
				deformers = listOf(warp, warp2),
				drawables = listOf(drawable),
				rootChildren = listOf(OrgChild.Part(PartId("a")), OrgChild.Part(PartId("b")), OrgChild.Drawable(DrawableId("d"))),
				rootPartId = null,
			)
		val session = EditorSession(nested)

		session.moveDeformer(DeformerId("w2"), newParentId = DeformerId("w"), beforeId = null)
		assertEquals(DeformerId("w"), session.model.value.deformers.first { it.id == DeformerId("w2") }.parent)

		// w is now an ancestor of w2, so nesting w under w2 is a cycle and must be refused.
		val before = session.model.value
		session.moveDeformer(DeformerId("w"), newParentId = DeformerId("w2"), beforeId = null)
		assertSame(before, session.model.value)

		session.undo()
		assertNull(session.model.value.deformers.first { it.id == DeformerId("w2") }.parent)
	}

	/**
	 * Deleting a drawable scrubs every reference to it: the drawables list, the render-order tree, another
	 * drawable's clip mask, and any glue it was half of. Undo restores them all.
	 */
	@Test
	fun deleteDrawableScrubsAllReferences() {
		val session = EditorSession(deleteModel())

		session.deleteDrawable(DrawableId("d1"))
		val after = session.model.value
		assertFalse(after.drawables.any { it.id == DrawableId("d1") })
		assertFalse(after.renderRoot.children.filterIsInstance<RenderDrawable>().any { it.id == DrawableId("d1") })
		assertTrue(after.drawables.first { it.id == DrawableId("d3") }.maskedBy.isEmpty(), "the mask reference to d1 is dropped")
		assertTrue(after.glues.isEmpty(), "the glue that paired d1 with d2 is dropped")

		session.undo()
		assertTrue(session.model.value.drawables.any { it.id == DrawableId("d1") })
		assertEquals(1, session.model.value.glues.size)
		assertEquals(listOf(DrawableId("d1")), session.model.value.drawables.first { it.id == DrawableId("d3") }.maskedBy)
	}

	/**
	 * Deleting a deformer unwraps it: its child deformers and the drawables it deformed re-home to its
	 * parent, so removing a transform wrapper never deletes art.
	 */
	@Test
	fun deleteDeformerUnwrapsToParent() {
		val session = EditorSession(deleteModel())

		// w is a root; w2's parent is w, and d2 is deformed by w. Deleting w re-homes both to w's parent (null).
		session.deleteDeformer(DeformerId("w"))
		val after = session.model.value
		assertFalse(after.deformers.any { it.id == DeformerId("w") })
		assertNull(after.deformers.first { it.id == DeformerId("w2") }.parent)
		assertNull(after.drawables.first { it.id == DrawableId("d2") }.parentDeformerId)
		// d1 was bound to w2 (not w), so it is untouched.
		assertEquals(DeformerId("w2"), after.drawables.first { it.id == DrawableId("d1") }.parentDeformerId)

		session.undo()
		assertEquals(DeformerId("w"), session.model.value.deformers.first { it.id == DeformerId("w2") }.parent)
	}

	/**
	 * A cascade part delete removes the whole subtree - the part, its descendant parts, and every drawable
	 * under them (with references scrubbed) - while leaving unrelated parts and drawables intact.
	 */
	@Test
	fun deletePartCascadeRemovesSubtree() {
		val session = EditorSession(deleteModel())

		// A holds B; d1 is under A and d2 under B. A cascade delete of A removes A, B, d1, and d2.
		session.deletePart(PartId("A"), cascade = true)
		val after = session.model.value
		assertEquals(setOf(PartId("R"), PartId("T")), after.parts.map { it.id }.toSet())
		assertEquals(listOf(DrawableId("d3")), after.drawables.map { it.id })
		assertTrue(after.parts.first { it.id == PartId("R") }.children.isEmpty(), "A is detached from its parent R")
		assertEquals(listOf(DrawableId("d3")), after.renderRoot.children.filterIsInstance<RenderDrawable>().map { it.id })
		assertTrue(after.glues.isEmpty())

		session.undo()
		assertEquals(4, session.model.value.parts.size)
		assertEquals(3, session.model.value.drawables.size)
	}

	/**
	 * An ungroup part delete dissolves the folder only: its child parts and drawables rise one level to the
	 * deleted part's parent, and nothing is destroyed.
	 */
	@Test
	fun deletePartUngroupKeepsContents() {
		val session = EditorSession(deleteModel())

		// Ungroup A (whose parent is R): B rises into R's children, and d1 (under A) re-homes to R.
		session.deletePart(PartId("A"), cascade = false)
		val after = session.model.value
		assertFalse(after.parts.any { it.id == PartId("A") })
		assertEquals(3, after.drawables.size, "no drawable is deleted on an ungroup")
		// A's own children (sub-part B and mesh d1) splice into A's parent R, in place.
		assertEquals(
			listOf<OrgChild>(OrgChild.Part(PartId("B")), OrgChild.Drawable(DrawableId("d1"))),
			after.parts.first { it.id == PartId("R") }.children,
		)
		assertEquals(PartId("R"), after.partByDrawable()[DrawableId("d1")])

		session.undo()
		assertTrue(session.model.value.parts.any { it.id == PartId("A") })
		assertEquals(PartId("A"), session.model.value.partByDrawable()[DrawableId("d1")])
	}

	private val angleX = ParameterId("ParamAngleX")

	/**
	 * A one-parameter model for the pose / range tests: a single ParamAngleX over the given range.
	 *
	 * @param Float min     The parameter minimum.
	 * @param Float max     The parameter maximum.
	 * @param Float default The parameter default (the initial pose value).
	 * @return PuppetModel The fixture model.
	 */
	private fun paramModel(min: Float = -1f, max: Float = 1f, default: Float = 0f): PuppetModel =
		model().copy(parameters = listOf(Parameter(angleX, "Angle X", min, max, default)))

	/**
	 * A scrub commit is one undo step over the pose: it moves the live value, does not dirty the document
	 * (the model is unchanged), and undo / redo restore the prior / next pose. The session pose starts at
	 * the parameter's default.
	 */
	@Test
	fun scrubCommitIsUndoablePoseStepWithoutDirty() {
		val session = EditorSession(paramModel())
		assertEquals(0f, session.pose.value[angleX])

		session.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 0.6f))

		assertEquals(0.6f, session.pose.value[angleX])
		assertFalse(session.dirty.value, "a pose-only scrub must not dirty the document")
		assertTrue(session.canUndo.value)

		session.undo()
		assertEquals(0f, session.pose.value[angleX])

		session.redo()
		assertEquals(0.6f, session.pose.value[angleX])
	}

	/** A scrub that commits the already-current pose records nothing. */
	@Test
	fun scrubCommitOfUnchangedPoseIsNoOp() {
		val session = EditorSession(paramModel())
		session.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 0f))
		assertFalse(session.canUndo.value)
	}

	/**
	 * Editing a parameter's range is one undo step that edits the model (so it dirties the document),
	 * re-clamps the live pose into the shrunk range, and on undo restores both the range and the pose
	 * together.
	 */
	@Test
	fun setRangeEditsModelReclampsPoseAndUndoesBoth() {
		val session = EditorSession(paramModel(min = -1f, max = 1f, default = 0f))
		session.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 0.8f))

		session.setParameterRange(angleX, min = -0.5f, default = 0f, max = 0.5f)

		val parameter = session.model.value.parameters.first()
		assertEquals(-0.5f, parameter.min)
		assertEquals(0.5f, parameter.max)
		assertEquals(0.5f, session.pose.value[angleX], "the live value re-clamps into the shrunk range")
		assertTrue(session.dirty.value, "a range edit changes the model and dirties the document")

		session.undo()
		assertEquals(1f, session.model.value.parameters.first().max)
		assertEquals(0.8f, session.pose.value[angleX], "undo restores the pre-clamp pose too")
		assertFalse(session.dirty.value)
	}

	/** withParameterRange normalizes inverted bounds and clamps the default into the resulting range. */
	@Test
	fun withParameterRangeNormalizesBoundsAndClampsDefault() {
		val base = paramModel(min = -1f, max = 1f, default = 0f)
		val edited = base.withParameterRange(angleX, min = 5f, default = 10f, max = 1f)

		val parameter = edited.parameters.first()
		assertEquals(1f, parameter.min, "min/max are normalized so min <= max")
		assertEquals(5f, parameter.max)
		assertEquals(5f, parameter.default, "the default is clamped into the normalized range")
		assertSame(base, base.withParameterRange(angleX, min = -1f, default = 0f, max = 1f), "a no-op range is the same instance")
	}

	private val angleY = ParameterId("ParamAngleY")
	private val angleZ = ParameterId("ParamAngleZ")

	/**
	 * A three-parameter model for the link tests: ParamAngleX / Y / Z, all animatable, no links.
	 *
	 * @return PuppetModel The fixture model.
	 */
	private fun linkModel(): PuppetModel =
		model().copy(
			parameters =
				listOf(
					Parameter(angleX, "Angle X", -1f, 1f, 0f),
					Parameter(angleY, "Angle Y", -1f, 1f, 0f),
					Parameter(angleZ, "Angle Z", -1f, 1f, 0f),
				),
		)

	/** withParameterLink appends the pair, removes exactly that pair, and no-ops an absent unlink. */
	@Test
	fun withParameterLinkAddsAndRemovesPairs() {
		val base = linkModel()
		val linked = base.withParameterLink(angleX, angleY, linked = true)
		assertEquals(listOf(ParameterLink(angleX, angleY)), linked.parameterLinks)

		val unlinked = linked.withParameterLink(angleX, angleY, linked = false)
		assertTrue(unlinked.parameterLinks.isEmpty())

		assertSame(base, base.withParameterLink(angleX, angleY, linked = false), "unlinking an absent pair is the same instance")
	}

	/** withParameterLink refuses equal ids, unknown ids, and members of an existing link - same instance. */
	@Test
	fun withParameterLinkRefusesInvalidRequests() {
		val base = linkModel()
		assertSame(base, base.withParameterLink(angleX, angleX, linked = true), "equal ids are refused")
		assertSame(base, base.withParameterLink(angleX, ParameterId("ParamUnknown"), linked = true), "an unknown id is refused")

		val linked = base.withParameterLink(angleX, angleY, linked = true)
		assertSame(linked, linked.withParameterLink(angleY, angleZ, linked = true), "a vertical member cannot join a second link")
		assertSame(linked, linked.withParameterLink(angleZ, angleX, linked = true), "a horizontal member cannot join a second link")
	}

	/**
	 * Linking parameters is one undo step that edits the model (so it dirties the document), leaves the
	 * live pose untouched, and undoes / redoes the link list; a refused request records nothing.
	 */
	@Test
	fun setParameterLinkIsOneDirtyStepLeavingPoseUntouched() {
		val session = EditorSession(linkModel())
		session.commitPose(ParameterChange.SetValue(listOf(angleX)), session.pose.value + (angleX to 0.4f))

		session.setParameterLink(angleX, angleY, linked = true)

		assertEquals(listOf(ParameterLink(angleX, angleY)), session.model.value.parameterLinks)
		assertEquals(0.4f, session.pose.value[angleX], "a link edit never moves the live pose")
		assertTrue(session.dirty.value, "a link edit changes the model and dirties the document")

		session.undo()
		assertTrue(session.model.value.parameterLinks.isEmpty(), "undo restores the previous links")
		assertEquals(0.4f, session.pose.value[angleX])

		session.redo()
		assertEquals(listOf(ParameterLink(angleX, angleY)), session.model.value.parameterLinks)

		// A refused request (angleY is already a link member) must not record an undo step.
		val stepCountBefore = session.historyView.value.steps.size
		session.setParameterLink(angleY, angleZ, linked = true)
		assertEquals(stepCountBefore, session.historyView.value.steps.size, "a refused link records nothing")
	}

	/**
	 * A model whose drawable d carries a base art-mesh (a single triangle), for the mesh-edit tests.
	 *
	 * @return PuppetModel The fixture model with an editable mesh on drawable d.
	 */
	private fun meshModel(): PuppetModel {
		val mesh =
			DrawableMesh(
				positions = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f),
				uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
				indices = intArrayOf(0, 1, 2),
			)
		return model().copy(drawables = listOf(drawable.copy(mesh = mesh)))
	}

	/** A model with two meshed drawables, for the Alt+Q switch and the per-mesh selection memory. */
	private fun twoMeshModel(): PuppetModel {
		val meshA = DrawableMesh(floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f), FloatArray(6), intArrayOf(0, 1, 2))
		val meshB = DrawableMesh(floatArrayOf(10f, 0f, 12f, 0f, 10f, 2f), FloatArray(6), intArrayOf(0, 1, 2))
		return model().copy(
			parts = emptyList(),
			drawables =
				listOf(
					drawable.copy(id = DrawableId("a"), name = "a", mesh = meshA),
					drawable.copy(id = DrawableId("b"), name = "b", mesh = meshB),
				),
			rootChildren = listOf(OrgChild.Drawable(DrawableId("a")), OrgChild.Drawable(DrawableId("b"))),
		).withDerivedRenderRoot()
	}

	/**
	 * Alt+Q's switch moves the OBJECT selection with the edit session in ONE undo step, and the
	 * per-mesh element memory restores each mesh's selection when the session returns to it.
	 */
	@Test
	fun switchEditDrawableSyncsObjectSelectionAndRemembersElementsPerMesh() {
		val session = EditorSession(twoMeshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("a"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, DrawableId("a"), MeshElement.Vertex(1)))

		val stepsBefore = session.historyView.value.steps.size
		session.switchEditDrawable(DrawableId("b"))
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the switch is one undo step")
		assertEquals(listOf(DrawableId("b")), session.meshSelection.value.drawableIds, "the edit session moved to b")
		assertEquals(
			SelectionTarget.Drawable(DrawableId("b")),
			session.selection.value.active,
			"the object selection follows the switch (tabbing out keeps b)",
		)

		// Switching back restores a's remembered element selection (the per-mesh memory).
		session.switchEditDrawable(DrawableId("a"))
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("a")), "a's elements return")

		// One undo rewinds the whole switch - BOTH selections together, never torn across two steps.
		session.undo()
		assertEquals(listOf(DrawableId("b")), session.meshSelection.value.drawableIds, "undo returns the session to b")
		assertEquals(SelectionTarget.Drawable(DrawableId("b")), session.selection.value.active, "undo returns the object selection with it")
	}

	/** Leaving Edit mode stashes every session mesh's elements; re-entry restores them per mesh. */
	@Test
	fun meshElementMemorySurvivesModeSwitchesPerMesh() {
		val session = EditorSession(twoMeshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("a"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.add(session.meshSelection.value, DrawableId("a"), MeshElement.Vertex(0)))
		session.setMode(EditorMode.Object)

		// Edit a DIFFERENT mesh in between: switching back to it later must not have forgotten a's memory.
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("b"))))
		session.setMode(EditorMode.Edit)
		assertEquals(listOf(DrawableId("b")), session.meshSelection.value.drawableIds)
		session.setMode(EditorMode.Object)

		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("a"))))
		session.setMode(EditorMode.Edit)
		assertEquals(
			setOf<MeshElement>(MeshElement.Vertex(0)),
			session.meshSelection.value.elementsOf(DrawableId("a")),
			"a's element selection survives editing b in between",
		)
	}

	/** withMeshPositions copy-on-writes the mesh: new positions, shared uvs/indices, input array untouched. */
	@Test
	fun withMeshPositionsCowsAndSharesUnchangedArrays() {
		val before = meshModel()
		val beforeMesh = before.drawables.first().mesh!!
		val newPositions = floatArrayOf(5f, 5f, 2f, 0f, 0f, 2f)

		val after = before.withMeshPositions(DrawableId("d"), newPositions)
		val afterMesh = after.drawables.first().mesh!!

		assertSame(beforeMesh.uvs, afterMesh.uvs, "uvs shared by reference")
		assertSame(beforeMesh.indices, afterMesh.indices, "indices shared by reference")
		assertEquals(5f, afterMesh.positions[0], "new positions applied")
		assertEquals(0f, beforeMesh.positions[0], "the prior mesh's array is unmutated (COW)")
		assertSame(before, before.withMeshPositions(DrawableId("d"), beforeMesh.positions), "same array is a no-op")
		assertSame(before, before.withMeshPositions(DrawableId("missing"), newPositions), "missing id is a no-op")
	}

	/** A mesh edit is one undo step that marks dirty; undo restores the original array instance and clears dirty. */
	@Test
	fun commitMeshPositionsIsOneUndoStepAndRestores() {
		val initial = meshModel()
		val session = EditorSession(initial)
		val originalArray = initial.drawables.first().mesh!!.positions
		val moved = floatArrayOf(9f, 9f, 2f, 0f, 0f, 2f)

		session.commitMeshPositions(MeshChange.MoveVertices(mapOf(DrawableId("d") to listOf(0))), mapOf(DrawableId("d") to moved))
		assertTrue(session.dirty.value, "a mesh edit dirties the document")
		assertTrue(session.canUndo.value)
		assertEquals(9f, session.model.value.drawables.first().mesh!!.positions[0])

		session.undo()
		assertSame(originalArray, session.model.value.drawables.first().mesh!!.positions, "undo restores the array instance")
		assertFalse(session.dirty.value)

		session.redo()
		assertEquals(9f, session.model.value.drawables.first().mesh!!.positions[0])
	}

	/**
	 * Vertex selection is undoable, and undoing a mesh move restores the vertex selection captured at that
	 * step (the snapshot carries it); a selection-only step does not dirty the document.
	 */
	@Test
	fun vertexSelectionIsUndoableAndRidesTheMoveSnapshot() {
		val session = EditorSession(meshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("d"), session.meshSelection.value.activeDrawableId)

		session.setMeshSelection(
			MeshSelectionOps.add(MeshSelectionOps.add(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)), DrawableId("d"), MeshElement.Vertex(1)),
		)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("d")))
		assertFalse(session.dirty.value, "a vertex-selection gesture does not dirty the document")

		session.commitMeshPositions(
			MeshChange.MoveVertices(mapOf(DrawableId("d") to listOf(0, 1))),
			mapOf(DrawableId("d") to floatArrayOf(9f, 9f, 9f, 0f, 0f, 2f)),
		)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(2)))
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(2)), session.meshSelection.value.elementsOf(DrawableId("d")))

		session.undo() // undo the {2} selection -> back to {0,1}
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("d")))

		session.undo() // undo the move -> geometry restored AND the selection it carried ({0,1})
		assertEquals(0f, session.model.value.drawables.first().mesh!!.positions[0])
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("d")))
	}

	/**
	 * Mode changes never write the pose (the contract that keeps Edit mode stash-free): entering and
	 * leaving Edit mode leave the scrubbed Object-mode pose untouched — the rest view Edit mode shows is
	 * a display-only override in the render host, not session state.
	 */
	@Test
	fun modeChangesNeverTouchThePose() {
		// A param plus an editable mesh on drawable d, so entering Edit actually engages (rather than being
		// refused for want of something to edit).
		val session = EditorSession(paramModel().copy(drawables = meshModel().drawables))
		session.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 0.6f))

		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, session.mode.value, "Edit engages when there is an editable drawable")
		assertEquals(0.6f, session.pose.value[angleX], "entering Edit leaves the pose untouched")

		session.setMode(EditorMode.Object)
		assertEquals(0.6f, session.pose.value[angleX], "leaving Edit leaves the pose untouched")
	}

	/** Entering Edit auto-seeds the topmost editable drawable; a modal operator latches only with a non-empty selection. */
	@Test
	fun editModeSeedAndOperatorGuards() {
		val session = EditorSession(meshModel())

		// No active drawable and nothing remembered: Edit auto-selects the topmost editable drawable (d)
		// rather than opening an inert session, but the operator still no-ops with no elements selected.
		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("d"), session.meshSelection.value.activeDrawableId)
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertNull(session.activeMeshOperator.value, "operator no-ops with an empty element selection")

		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)))
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeMeshOperator.value?.kind)
		session.clearMeshOperator()
		assertNull(session.activeMeshOperator.value)

		// An edge selection latches too - G / S / R moves the vertices the selected elements cover.
		session.setMeshSelectMode(MeshSelectMode.Edge)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Edge.of(0, 1)))
		session.beginMeshOperator(MeshOperatorKind.Grab, "area-test")
		assertEquals(MeshOperatorKind.Grab, session.activeMeshOperator.value?.kind)
		session.clearMeshOperator()
	}

	/**
	 * Entering Edit mode after the selection was cleared falls back to the last drawable that was active
	 * (Blender's remembered selection), instead of landing in an inert Edit mode.
	 */
	@Test
	fun editModeFallsBackToRememberedDrawableAfterDeselect() {
		val session = EditorSession(meshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setSelection(SelectionOps.clear())

		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("d"), session.meshSelection.value.activeDrawableId, "remembered drawable seeds Edit mode")
	}

	/** A currently-active drawable always wins over the remembered one when Edit mode seeds. */
	@Test
	fun activeSelectionWinsOverRememberedDrawable() {
		// Both drawables carry meshes: the seed filters to mesh-carrying drawables, so a mesh-less
		// active target could never win (Edit refuses inert sessions).
		val modelWithTwoDrawables =
			meshModel().let { base ->
				base.copy(drawables = base.drawables + base.drawables.first().copy(id = DrawableId("e"), name = "e"))
			}
		val session = EditorSession(modelWithTwoDrawables)
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("e"))))

		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("e"), session.meshSelection.value.activeDrawableId, "the live active drawable wins")
	}

	/**
	 * With the remembered drawable deleted and no other editable drawable left, entering Edit is refused -
	 * the mode stays Object rather than opening an inert session.
	 */
	@Test
	fun editModeRefusedWhenNothingEditable() {
		val session = EditorSession(meshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setSelection(SelectionOps.clear())
		session.mutate(DrawableChange.Delete(DrawableId("d"))) { current ->
			current.copy(drawables = current.drawables.filter { candidate -> candidate.id != DrawableId("d") })
		}

		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Object, session.mode.value, "Edit is refused when the model has nothing editable")
		assertNull(session.meshSelection.value.activeDrawableId)
	}

	/**
	 * A fresh document with nothing ever selected enters Edit on the topmost editable drawable (Parts-panel
	 * order, top = front), skipping mesh-less drawables ahead of it.
	 */
	@Test
	fun editModeAutoSelectsTopmostDrawableOnFreshLoad() {
		val mesh =
			DrawableMesh(
				positions = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f),
				uvs = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f),
				indices = intArrayOf(0, 1, 2),
			)
		val noMesh = drawable.copy(id = DrawableId("noMesh"), name = "noMesh", mesh = null)
		val top = drawable.copy(id = DrawableId("top"), name = "top", mesh = mesh)
		val bottom = drawable.copy(id = DrawableId("bottom"), name = "bottom", mesh = mesh)
		val model =
			model().copy(
				parts = listOf(partA.copy(children = emptyList()), partB),
				drawables = listOf(noMesh, top, bottom),
				rootChildren =
					listOf(
						OrgChild.Drawable(DrawableId("noMesh")),
						OrgChild.Drawable(DrawableId("top")),
						OrgChild.Drawable(DrawableId("bottom")),
					),
			)
		val session = EditorSession(model)

		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("top"), session.meshSelection.value.activeDrawableId, "front-most editable drawable seeds Edit")
	}

	/** A model whose only drawables carry no mesh has nothing to edit, so Edit is refused. */
	@Test
	fun editModeRefusedWhenModelHasNoEditableDrawable() {
		val session = EditorSession(model()) // drawable d has mesh = null

		session.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Object, session.mode.value)
		assertNull(session.meshSelection.value.activeDrawableId)
	}

	/**
	 * A mode change is its own undo step, so undoing back across it restores the prior mode (and the vertex
	 * selection it seeded) - the editor never lands in Edit mode showing an Object-mode state.
	 */
	@Test
	fun modeChangeIsUndoableAndRestoresPriorMode() {
		val session = EditorSession(meshModel())
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)))
		assertEquals(EditorMode.Edit, session.mode.value)

		session.undo() // undo the vertex selection -> still in Edit (that step was taken in Edit)
		assertEquals(EditorMode.Edit, session.mode.value)

		session.undo() // undo the mode change -> back to Object, vertex selection gone
		assertEquals(EditorMode.Object, session.mode.value)
		assertNull(session.meshSelection.value.activeDrawableId)

		session.redo() // redo -> back into Edit with the drawable seeded
		assertEquals(EditorMode.Edit, session.mode.value)
		assertEquals(DrawableId("d"), session.meshSelection.value.activeDrawableId)

		assertFalse(session.dirty.value, "mode changes never dirty the document")
	}

	/**
	 * A select-mode switch converts the selection (Blender's derive-up / flush-down), is one undo step
	 * labeled "change.mesh.selectMode", and no-ops outside Edit mode or when already in the requested mode.
	 */
	@Test
	fun selectModeSwitchConvertsAndIsUndoable() {
		val session = EditorSession(meshModel())
		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(MeshSelectMode.Vertex, session.meshSelection.value.selectMode, "no-op outside Edit mode")

		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(
			MeshSelectionOps.add(MeshSelectionOps.add(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(0)), DrawableId("d"), MeshElement.Vertex(1)),
		)

		val stepsBefore = session.historyView.value.steps.size
		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(MeshSelectMode.Edge, session.meshSelection.value.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(DrawableId("d")), "both-endpoint edge derives")
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "the switch is exactly one undo step")
		assertEquals("change.mesh.selectMode", session.historyView.value.steps.last().labelKey)
		assertFalse(session.dirty.value, "a select-mode switch never dirties the document")

		session.setMeshSelectMode(MeshSelectMode.Edge)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "a same-mode switch records nothing")

		session.undo()
		assertEquals(MeshSelectMode.Vertex, session.meshSelection.value.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("d")))

		session.redo()
		assertEquals(MeshSelectMode.Edge, session.meshSelection.value.selectMode)
		assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(DrawableId("d")))
	}

	/**
	 * Leaving Edit mode stashes the element selection and re-entering on the same drawable restores it
	 * (Blender's remembered mesh selection); landing on a different drawable starts empty instead.
	 */
	@Test
	fun meshSelectionIsRememberedAcrossModeSwitches() {
		// Two meshed drawables so the second entry can land somewhere else.
		val model =
			meshModel().let { base ->
				base.copy(drawables = base.drawables + base.drawables.first().copy(id = DrawableId("d2"), name = "d2"))
			}
		val session = EditorSession(model)
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		session.setMeshSelection(MeshSelectionOps.replace(session.meshSelection.value, DrawableId("d"), MeshElement.Vertex(1)))

		session.setMode(EditorMode.Object)
		assertNull(session.meshSelection.value.activeDrawableId, "leaving Edit clears the live selection")

		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("d"), session.meshSelection.value.activeDrawableId)
		assertEquals(setOf<MeshElement>(MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(DrawableId("d")), "the stash restores on the same drawable")
		assertEquals(ActiveMeshElement(DrawableId("d"), MeshElement.Vertex(1)), session.meshSelection.value.activeElement, "the active element restores too")

		// Leave again and land on the other drawable: the stash does not apply there.
		session.setMode(EditorMode.Object)
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d2"))))
		session.setMode(EditorMode.Edit)
		assertEquals(DrawableId("d2"), session.meshSelection.value.activeDrawableId)
		assertTrue(session.meshSelection.value.isEmpty, "a different drawable starts empty")
	}

	/**
	 * Entering Edit with several meshed drawables selected seeds them ALL into the session (multi-mesh
	 * edit), with the object selection's active drawable as the active mesh; a commit moving vertices
	 * on two meshes is ONE undo step that restores both.
	 */
	@Test
	fun multiMeshEditSessionSeedsAndCommitsAcrossMeshes() {
		val model =
			meshModel().let { base ->
				base.copy(drawables = base.drawables + base.drawables.first().copy(id = DrawableId("d2"), name = "d2"))
			}
		val session = EditorSession(model)
		val targetD = SelectionTarget.Drawable(DrawableId("d"))
		val targetD2 = SelectionTarget.Drawable(DrawableId("d2"))
		session.setSelection(Selection(setOf(targetD, targetD2), targetD2))

		session.setMode(EditorMode.Edit)
		assertEquals(listOf(DrawableId("d"), DrawableId("d2")), session.meshSelection.value.drawableIds, "both meshes join the session")
		assertEquals(DrawableId("d2"), session.meshSelection.value.activeDrawableId, "the object selection's active drawable is the active mesh")

		// One commit moving vertices on both meshes is a single undo step restoring both.
		val stepsBefore = session.historyView.value.steps.size
		session.commitMeshPositions(
			MeshChange.MoveVertices(mapOf(DrawableId("d") to listOf(0), DrawableId("d2") to listOf(1))),
			mapOf(
				DrawableId("d") to floatArrayOf(9f, 9f, 2f, 0f, 0f, 2f),
				DrawableId("d2") to floatArrayOf(0f, 0f, 7f, 7f, 0f, 2f),
			),
		)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "two meshes, one undo step")
		assertEquals(9f, session.model.value.drawables.first { it.id == DrawableId("d") }.mesh!!.positions[0])
		assertEquals(7f, session.model.value.drawables.first { it.id == DrawableId("d2") }.mesh!!.positions[2])

		session.undo()
		assertEquals(0f, session.model.value.drawables.first { it.id == DrawableId("d") }.mesh!!.positions[0])
		assertEquals(2f, session.model.value.drawables.first { it.id == DrawableId("d2") }.mesh!!.positions[2])
	}

	/**
	 * selectHierarchy expands a part to its outliner subtree (descendant parts + their drawables) and
	 * leaves a drawable-only selection unchanged (a leaf's subtree is itself).
	 */
	@Test
	fun selectHierarchyExpandsPartSubtrees() {
		val fixture = model() // part a holds drawable d; part b is empty.
		val partTarget = SelectionTarget.Part(PartId("a"))
		val expanded = SelectionOps.selectHierarchy(Selection(setOf(partTarget), partTarget), fixture)
		assertEquals(
			setOf<SelectionTarget>(partTarget, SelectionTarget.Drawable(DrawableId("d"))),
			expanded.targets,
			"the part's subtree (itself + its drawable) is selected",
		)
		assertEquals(partTarget, expanded.active, "the original active target is kept")

		val leaf = SelectionTarget.Drawable(DrawableId("d"))
		val unchanged = SelectionOps.selectHierarchy(Selection(setOf(leaf), leaf), fixture)
		assertEquals(setOf<SelectionTarget>(leaf), unchanged.targets, "a drawable expands to just itself")

		assertTrue(SelectionOps.selectHierarchy(Selection(), fixture).isEmpty, "an empty selection stays empty")
	}

	/** switchEditDrawable re-seeds the Edit session onto one mesh as an undoable selection step. */
	@Test
	fun switchEditDrawableReseedsTheSession() {
		val model =
			meshModel().let { base ->
				base.copy(drawables = base.drawables + base.drawables.first().copy(id = DrawableId("d2"), name = "d2"))
			}
		val session = EditorSession(model)
		session.setSelection(SelectionOps.replace(SelectionTarget.Drawable(DrawableId("d"))))
		session.setMode(EditorMode.Edit)
		assertEquals(listOf(DrawableId("d")), session.meshSelection.value.drawableIds)

		session.switchEditDrawable(DrawableId("d2"))
		assertEquals(listOf(DrawableId("d2")), session.meshSelection.value.drawableIds, "the session re-seeds onto the target")
		assertEquals(DrawableId("d2"), session.meshSelection.value.activeDrawableId)

		session.undo()
		assertEquals(listOf(DrawableId("d")), session.meshSelection.value.drawableIds, "the switch is one undoable step")

		// A mesh-less or unknown drawable refuses the switch.
		session.switchEditDrawable(DrawableId("missing"))
		assertEquals(listOf(DrawableId("d")), session.meshSelection.value.drawableIds)
	}

	/** Helper so the bounds test can assert the session's undo is a no-op without exposing internals. */
	private object EditorSnapshotProbe {
		fun undoResult(session: EditorSession): Unit? {
			val before = session.model.value
			session.undo()
			return if (session.model.value === before) null else Unit
		}

		fun jumpResult(session: EditorSession, index: Int): Unit? {
			val before = session.historyView.value.cursor
			session.jumpTo(index)
			return if (session.historyView.value.cursor == before) null else Unit
		}
	}
}
