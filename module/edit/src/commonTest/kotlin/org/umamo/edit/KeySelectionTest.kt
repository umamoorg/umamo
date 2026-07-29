package org.umamo.edit

import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The keyform sheet's key selection as session state, and the two ways of publishing one.
 *
 * The distinction under test is the whole reason [EditorSession.stageKeySelection] exists: a click on a mark
 * both selects the key and scrubs the pose onto it, and recording those separately makes one click take two
 * presses of Ctrl+Z to reverse.  Staging folds the selection into the step the scrub is about to record.
 */
class KeySelectionTest {
	private val angleX = ParameterId("ParamAngleX")

	private fun session(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = listOf(Parameter(angleX, angleX.raw, min = -30f, max = 30f, default = 0f)),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)

	private fun keyAt(keyIndex: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:d/OPACITY", keyIndex)

	/** A fresh document has nothing selected. */
	@Test
	fun startsEmpty() {
		assertTrue(session().keySelection.value.isEmpty())
	}

	/** Selecting keys is its own undo step, and undo brings the previous selection back. */
	@Test
	fun selectingIsItsOwnUndoStep() {
		val editorSession = session()
		editorSession.setKeySelection(setOf(keyAt(0)))
		editorSession.setKeySelection(setOf(keyAt(0), keyAt(1)))

		assertEquals(setOf(keyAt(0), keyAt(1)), editorSession.keySelection.value)
		editorSession.undo()
		assertEquals(setOf(keyAt(0)), editorSession.keySelection.value, "back to the first selection")
		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty(), "and back to none")
	}

	/** Selecting what is already selected records nothing - a re-click is not an edit. */
	@Test
	fun reselectingTheSameKeysRecordsNothing() {
		val editorSession = session()
		editorSession.setKeySelection(setOf(keyAt(0)))
		editorSession.setKeySelection(setOf(keyAt(0)))

		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty(), "one undo, not two")
	}

	/** A selection is not a document change, so it must not mark the file unsaved. */
	@Test
	fun selectingDoesNotDirtyTheDocument() {
		val editorSession = session()
		editorSession.setKeySelection(setOf(keyAt(0)))

		assertFalse(editorSession.dirty.value)
	}

	/**
	 * A staged selection publishes immediately but records nothing on its own - the commit that follows
	 * carries it, so the pair is ONE undo step.
	 */
	@Test
	fun aStagedSelectionRidesTheFollowingCommit() {
		val editorSession = session()
		editorSession.stageKeySelection(setOf(keyAt(2)))
		assertEquals(setOf(keyAt(2)), editorSession.keySelection.value, "it publishes at once")
		assertFalse(editorSession.canUndo.value, "but records nothing by itself")

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 15f))
		editorSession.undo()

		assertTrue(editorSession.keySelection.value.isEmpty(), "one undo reverses the click's selection")
		assertEquals(0f, editorSession.pose.value[angleX], "and its scrub, together")
		assertFalse(editorSession.canUndo.value, "because they were one step")
	}

	/**
	 * Clicking a mark selects it AND lands the pose on it, as one step named for the selection.
	 *
	 * The label matters: the entry used to read "Adjust Parameters", which described the consequence rather
	 * than the act - the rigger clicked a keyframe.
	 */
	@Test
	fun selectingAtAPoseIsOneStepNamedForTheSelection() {
		val editorSession = session()
		editorSession.selectKeysAtPose(setOf(keyAt(1)), mapOf(angleX to 30f))

		assertEquals(setOf(keyAt(1)), editorSession.keySelection.value)
		assertEquals(30f, editorSession.pose.value[angleX])
		assertEquals("change.keyform.select", editorSession.liveStepLabelKey(), "named for the selection")

		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty(), "one undo reverses both")
		assertEquals(0f, editorSession.pose.value[angleX])
		assertFalse(editorSession.canUndo.value)
	}

	/**
	 * Clicking the key the pose ALREADY sits on still records the selection.
	 *
	 * The case that rules out staging the selection behind an ordinary pose commit: that commit
	 * short-circuits on an unchanged pose, so the selection would never reach history at all and the click
	 * would be silently unrecoverable.
	 */
	@Test
	fun selectingWithoutMovingThePoseStillRecords() {
		val editorSession = session()
		editorSession.selectKeysAtPose(setOf(keyAt(0)), editorSession.pose.value)

		assertTrue(editorSession.canUndo.value, "the selection is the whole step here")
		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty())
	}

	/** Re-clicking the same key at the same pose records nothing. */
	@Test
	fun reselectingAtTheSamePoseRecordsNothing() {
		val editorSession = session()
		editorSession.selectKeysAtPose(setOf(keyAt(0)), editorSession.pose.value)
		editorSession.selectKeysAtPose(setOf(keyAt(0)), editorSession.pose.value)

		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty(), "one undo, not two")
	}

	/**
	 * A staged selection the following commit DECLINED to record is recorded by the confirming
	 * [EditorSession.setKeySelection] instead.
	 *
	 * The gesture is an empty-track press at the value the playhead already holds: it drops the selection and
	 * then scrubs nowhere, so the pose commit short-circuits.  Staging alone left the selection changed with
	 * nothing in history to undo it - the one failure mode the stage / edit / confirm rule exists to close.
	 */
	@Test
	fun aStagedSelectionTheCommitDroppedIsRecordedByTheConfirm() {
		val editorSession = session()
		editorSession.setKeySelection(setOf(keyAt(0), keyAt(1)))

		editorSession.stageKeySelection(emptySet())
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), editorSession.pose.value)
		editorSession.setKeySelection(editorSession.keySelection.value)

		assertTrue(editorSession.keySelection.value.isEmpty(), "the press dropped the selection")
		editorSession.undo()
		assertEquals(setOf(keyAt(0), keyAt(1)), editorSession.keySelection.value, "and undo brings it back")
	}

	/**
	 * The confirm is a no-op when the edit it follows already carried the staged selection - one gesture
	 * stays one undo step.
	 */
	@Test
	fun confirmingASelectionTheCommitAlreadyCarriedRecordsNothing() {
		val editorSession = session()
		editorSession.stageKeySelection(setOf(keyAt(2)))
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 15f))
		editorSession.setKeySelection(setOf(keyAt(2)))

		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty(), "one undo reverses the whole gesture")
		assertEquals(0f, editorSession.pose.value[angleX], "selection and scrub together")
		assertFalse(editorSession.canUndo.value, "so the confirm added no second step")
	}

	/** The label key of the live history step - what the History panel would show for it. */
	private fun EditorSession.liveStepLabelKey(): String? = historyView.value.let { view -> view.steps[view.cursor].labelKey }
}
