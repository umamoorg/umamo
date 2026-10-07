package org.umamo.edit

import org.umamo.edit.SessionTestModels.angleX
import org.umamo.edit.SessionTestModels.drawable
import org.umamo.edit.SessionTestModels.meshModel
import org.umamo.edit.SessionTestModels.model
import org.umamo.edit.SessionTestModels.paramModel
import org.umamo.edit.SessionTestModels.partA
import org.umamo.edit.SessionTestModels.partB
import org.umamo.edit.SessionTestModels.twoMeshModel
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
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
	 * A save snapshots the model and writes it off-thread, so the baseline moves to THAT instance: an edit
	 * landed meanwhile keeps the document dirty, undoing back to the saved instance clears it, and the
	 * history panel's saved marker sits on the row that was written rather than on the live one.
	 */
	@Test
	fun markSavedOfASnapshotLeavesALaterEditDirty() {
		val session = EditorSession(model())
		session.mutate(PartChange.SetVisibility(PartId("a"), false)) { it.withPartVisibility(PartId("a"), false) }
		val snapshot = session.model.value
		session.mutate(PartChange.Rename(PartId("b"), "B2")) { it.withPartName(PartId("b"), "B2") }

		session.markSaved(snapshot)

		assertTrue(session.dirty.value, "the edit after the snapshot is not on disk")
		assertEquals(listOf(false, true, false), session.historyView.value.steps.map { step -> step.saved }, "the marker sits on the snapshot's row")
		session.undo()
		assertFalse(session.dirty.value, "undoing back to the saved instance is clean")
		session.redo()
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
			MeshChange.TransformVertices(mapOf(DrawableId("d") to listOf(0, 1)), MeshOperatorKind.Grab),
			mapOf(DrawableId("d") to MeshRestPositions.shared(floatArrayOf(9f, 9f, 9f, 0f, 0f, 2f))),
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
			DrawableMesh.withLocalEqualToCanvas(
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

	/**
	 * The idle gate a watched-file reload waits behind: every latch that means a hand is mid-gesture
	 * closes it, and releasing the latch opens it again.
	 */
	@Test
	fun quiescenceFollowsEveryLatch() {
		val session = EditorSession(model())
		assertTrue(session.isQuiescent, "a fresh session is idle")
		session.setViewportGestureActive(true)
		assertFalse(session.isQuiescent, "a viewport drag")
		session.setViewportGestureActive(false)
		assertTrue(session.isQuiescent)
		session.setPreviewSelection(emptySet())
		assertFalse(session.isQuiescent, "a circle stroke")
		session.setPreviewSelection(null)
		assertTrue(session.isQuiescent)
		session.setMeshPreviewSelection(MeshSelection.editing(listOf(DrawableId("d"))))
		assertFalse(session.isQuiescent, "an Edit-mode circle stroke")
		session.setMeshPreviewSelection(null)
		assertTrue(session.isQuiescent)
		session.openPieMenu(PieMenuKind.Snap)
		assertFalse(session.isQuiescent, "an open pie menu")
		session.closePieMenu()
		assertTrue(session.isQuiescent)
		session.emitNotice("notice.test", NoticePlacement.StatusBar, listOf("7"))
		assertEquals(listOf("7"), session.notice.value?.arguments, "a notice carries its arguments")
	}

	/** An undo ends any in-flight stroke, so it takes both stroke previews down, the Object one and the Edit one. */
	@Test
	fun anUndoTakesBothStrokePreviewsDown() {
		val session = EditorSession(model())
		session.mutate(PartChange.SetVisibility(PartId("a"), false)) { it.withPartVisibility(PartId("a"), false) }
		session.setPreviewSelection(setOf(DrawableId("d")))
		session.setMeshPreviewSelection(MeshSelection.editing(listOf(DrawableId("d"))))

		session.undo()

		assertNull(session.previewSelection.value, "the Object-mode stroke preview")
		assertNull(session.meshPreviewSelection.value, "the Edit-mode stroke preview")
	}
}