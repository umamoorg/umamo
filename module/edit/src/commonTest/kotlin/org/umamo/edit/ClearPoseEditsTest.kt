package org.umamo.edit

import org.umamo.edit.PoseLatticeTestRig.cage
import org.umamo.edit.PoseLatticeTestRig.parameterP
import org.umamo.edit.PoseLatticeTestRig.posing
import org.umamo.edit.PoseLatticeTestRig.shaped
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins Clear Pose Edits: every unkeyed value and lattice held at the pose goes in one undo step that leaves the model,
 * the pose, and the dirty flag alone, undo brings them back, and with nothing held, or in a mode that pins the pose,
 * it records nothing.
 */
class ClearPoseEditsTest {
	private val opacity = KeyableTarget(KeyformOwner.Deformer(cage), FormChannel.OPACITY)

	/**
	 * A session posed at P = 0.5 over the cage, holding a typed opacity and a shaped lattice on it.
	 *
	 * @return EditorSession The session.
	 */
	private fun holding(): EditorSession {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), mapOf(parameterP to 0.5f))
		session.commitPendingChannelEdit(opacity, ChannelValue.Scalar(0.25f), DeformerChange.SetOpacity(cage, 0.25f))
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(cage), MeshOperatorKind.Grab), mapOf(cage to shaped(1f, 2f)))

		return session
	}

	@Test
	fun everyHeldEditGoesInOneStep() {
		val session = holding()
		val modelBefore = session.model.value
		val poseBefore = session.pose.value
		val stepsBefore = session.historyView.value.steps.size

		session.clearPoseEdits()

		assertTrue(session.pendingChannelEdits.value.isEmpty())
		assertTrue(session.pendingLatticeEdits.value.isEmpty())
		assertSame(modelBefore, session.model.value)
		assertEquals(poseBefore, session.pose.value)
		assertFalse(session.dirty.value, "not a document edit")
		assertEquals(stepsBefore + 1, session.historyView.value.steps.size)
		assertEquals("change.pose.clearEdits", session.historyView.value.steps.last().labelKey)
	}

	@Test
	fun undoBringsThemBack() {
		val session = holding()

		session.clearPoseEdits()
		session.undo()

		assertEquals(setOf(opacity), session.pendingChannelEdits.value.keys)
		assertEquals(setOf(cage), session.pendingLatticeEdits.value.keys)
	}

	@Test
	fun nothingHeldRecordsNothing() {
		val session = posing(listOf(SelectionTarget.Deformer(cage)), mapOf(parameterP to 0.5f))
		val stepsBefore = session.historyView.value.steps.size

		session.clearPoseEdits()

		assertEquals(stepsBefore, session.historyView.value.steps.size)
	}

	@Test
	fun aModeThatPinsThePoseRecordsNothing() {
		val session = holding()
		session.setMode(EditorMode.Edit)
		val stepsBefore = session.historyView.value.steps.size

		session.clearPoseEdits()

		assertEquals(stepsBefore, session.historyView.value.steps.size)
		assertEquals(setOf(cage), session.pendingLatticeEdits.value.keys, "held for when the pose shows again")
	}
}