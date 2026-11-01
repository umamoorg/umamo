package org.umamo.edit

import org.umamo.edit.PoseLatticeTestRig.cage
import org.umamo.edit.PoseLatticeTestRig.parameterP
import org.umamo.edit.PoseLatticeTestRig.posing
import org.umamo.edit.PoseLatticeTestRig.second
import org.umamo.edit.PoseLatticeTestRig.shaped
import org.umamo.runtime.keyform.withLatticeShifted
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.ElementOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pending Pose-mode lattices: a shaped lattice is one undo step that leaves the document alone, the pose
 * moving discards it with a counted notice that undo answers, a mode switch keeps it, a write that rewrites the
 * warp's forms drops that warp's entry alone, the strip amends the step in place, and none of it is saved.
 */
class PendingLatticeEditTest {
	private val pose = mapOf(parameterP to 0.5f)

	@Test
	fun aPendingLatticeIsOneStepThatLeavesTheModelAlone() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), pose)
		val modelBefore = session.model.value
		val stepsBefore = session.historyView.value.steps.size
		val form = shaped(1f, 2f)

		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to form))

		assertSame(form, session.pendingLatticeEdits.value[cage])
		assertSame(modelBefore, session.model.value, "the document is untouched")
		assertFalse(session.dirty.value)
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.pose.move", session.historyView.value.steps.last().labelKey)

		session.undo()
		assertTrue(session.pendingLatticeEdits.value.isEmpty())

		session.redo()
		assertSame(form, session.pendingLatticeEdits.value[cage])
	}

	@Test
	fun aPoseMoveDiscardsItWithACountedNoticeAndUndoBringsItBack() {
		val session = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(second)), pose)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage, second), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f), second to shaped(3f, 4f)))

		session.commitPose(ParameterChange.SetValue(listOf(parameterP)), session.pose.value + (parameterP to -0.25f))

		assertTrue(session.pendingLatticeEdits.value.isEmpty(), "shaped for the pose being left")
		val notice = assertNotNull(session.notice.value)
		assertEquals("notice.pose.latticeEditsDiscarded", notice.messageKey)
		assertEquals(listOf("2"), notice.arguments)
		assertEquals(NoticePlacement.NearCursor, notice.placement)

		session.undo()
		assertEquals(0.5f, session.pose.value[parameterP])
		assertEquals(setOf(cage, second), session.pendingLatticeEdits.value.keys, "back with the pose they were made for")
	}

	@Test
	fun aPreviewDiscardReportsTheCountToo() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), pose)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f)))

		session.discardPendingEditsForPose()

		assertTrue(session.pendingLatticeEdits.value.isEmpty())
		assertEquals(listOf("1"), assertNotNull(session.notice.value).arguments)
	}

	@Test
	fun aPoseMoveWithNothingPendingSaysNothing() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), pose)

		session.commitPose(ParameterChange.SetValue(listOf(parameterP)), session.pose.value + (parameterP to -0.25f))
		session.discardPendingEditsForPose()

		assertNull(session.notice.value)
	}

	@Test
	fun aModeSwitchKeepsIt() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), pose)
		val form = shaped(1f, 2f)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to form))

		session.setMode(EditorMode.Edit)
		assertSame(form, session.pendingLatticeEdits.value[cage], "Edit mode shows rest, but the pose is kept")

		session.setMode(EditorMode.Object)
		session.setMode(EditorMode.Pose)
		assertSame(form, session.pendingLatticeEdits.value[cage])
	}

	@Test
	fun aWriteThatRewritesAWarpsFormsDropsThatWarpAlone() {
		val session = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(second)), pose)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage, second), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f), second to shaped(3f, 4f)))

		session.mutate(DeformerChange.Rename(second, "renamed")) { model ->
			model.copy(deformers = model.deformers.map { deformer -> if (deformer.id == second) (deformer as Deformer.Warp).copy(name = "renamed") else deformer })
		}
		assertEquals(setOf(cage, second), session.pendingLatticeEdits.value.keys, "a rename leaves the lattices alone")

		session.mutate(DeformerChange.ReshapeLattice(listOf(cage), MeshOperatorKind.Grab)) { model ->
			model.copy(deformers = model.deformers.map { deformer -> if (deformer.id == cage) (deformer as Deformer.Warp).withLatticeShifted(FloatArray(18) { 1f }) else deformer })
		}
		assertEquals(setOf(second), session.pendingLatticeEdits.value.keys, "the reshaped warp's entry goes")

		session.mutate(DeformerChange.Move(second, cage, null)) { model ->
			model.copy(deformers = model.deformers.map { deformer -> if (deformer.id == second) (deformer as Deformer.Warp).copy(parent = cage) else deformer })
		}
		assertTrue(session.pendingLatticeEdits.value.isEmpty(), "a re-parented warp's entry goes")
	}

	@Test
	fun aDeletedWarpsEntryGoes() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), pose)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f)))

		session.mutate(DeformerChange.Rename(cage, "gone")) { model -> model.copy(deformers = model.deformers.filter { deformer -> deformer.id != cage }) }

		assertTrue(session.pendingLatticeEdits.value.isEmpty())
	}

	@Test
	fun theStripAmendsTheStepInPlace() {
		val session = posing(listOf(SelectionTarget.Deformer(cage), SelectionTarget.Deformer(second)), pose)
		val held = shaped(3f, 4f)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(second), MeshOperatorKind.Grab), mapOf(second to held))
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f)))
		val operation = assertNotNull(session.registerAdjustableOperation(session.model.value, "area", listOf(OperatorParameter.IntParameter("x", "op.x", 1, 0, 2))) {})
		val stepsBefore = session.historyView.value.steps.size
		val amended = shaped(5f, 6f)

		assertTrue(session.amendLastPendingLatticeEdits(operation, mapOf(cage to amended)))

		assertSame(amended, session.pendingLatticeEdits.value[cage])
		assertSame(held, session.pendingLatticeEdits.value[second], "the lattices held before the step stay")
		assertEquals(stepsBefore, session.historyView.value.steps.size, "rewritten in place")

		session.undo()
		assertNull(session.pendingLatticeEdits.value[cage])
		assertSame(held, session.pendingLatticeEdits.value[second])
	}

	@Test
	fun noneOfItIsSaved() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), pose)
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f)))

		val reopened = EditorSession(session.model.value, session.pose.value, initialViewState = session.viewState())

		assertEquals(EditorMode.Pose, reopened.mode.value)
		assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(cage)), reopened.meshSelection.value.owners, "the session reopens on the posed warp")
		assertTrue(reopened.pendingLatticeEdits.value.isEmpty())
	}
}