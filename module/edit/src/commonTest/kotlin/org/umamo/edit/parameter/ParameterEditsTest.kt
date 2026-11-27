package org.umamo.edit.parameter

import org.umamo.edit.EditorSession
import org.umamo.edit.ParameterChange
import org.umamo.edit.SessionTestModels.angleX
import org.umamo.edit.SessionTestModels.angleY
import org.umamo.edit.SessionTestModels.angleZ
import org.umamo.edit.SessionTestModels.linkModel
import org.umamo.edit.SessionTestModels.paramModel
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.ParameterLink
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Tests for ParameterEdits.kt: the pure range / repeat / note / link transforms, and the session steps that commit a
 * range with its pose brought back into range, a repeat flag, a typed note, and a link with its narrowed target.
 */
class ParameterEditsTest {
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

	/** A range narrowed under the pose of a repeating axis wraps the pose back in, where a normal axis clamps it. */
	@Test
	fun setRangeWrapsThePoseOnARepeatingAxis() {
		val repeating = EditorSession(paramModel(min = 0f, max = 360f, default = 0f, repeat = true))
		repeating.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 300f))

		repeating.setParameterRange(angleX, min = 0f, default = 0f, max = 180f)

		assertEquals(120f, repeating.pose.value[angleX], "300 wraps once around a 180 period")

		val normal = EditorSession(paramModel(min = 0f, max = 360f, default = 0f))
		normal.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 300f))

		normal.setParameterRange(angleX, min = 0f, default = 0f, max = 180f)

		assertEquals(180f, normal.pose.value[angleX], "a normal axis clamps to the new max")
	}

	/** Turning repeat on is one dirty step that leaves the pose alone, and undo turns it off again. */
	@Test
	fun setRepeatIsOneDirtyStepLeavingPoseUntouched() {
		val session = EditorSession(paramModel(min = -1f, max = 1f, default = 0f))
		session.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 0.6f))

		session.setParameterRepeat(angleX, repeat = true)

		assertTrue(session.model.value.parameters.first().repeat)
		assertEquals(0.6f, session.pose.value[angleX], "a repeat edit never moves the live pose")
		assertTrue(session.dirty.value, "a repeat edit changes the model and dirties the document")
		assertEquals("change.parameter.repeat", session.historyView.value.steps.last().labelKey)

		session.undo()
		assertFalse(session.model.value.parameters.first().repeat, "undo turns repeat off again")
		assertFalse(session.dirty.value)
	}

	/** Setting the flag a parameter already holds, or naming no parameter, records nothing. */
	@Test
	fun setRepeatThatChangesNothingRecordsNothing() {
		val session = EditorSession(paramModel(repeat = true))
		val stepCountBefore = session.historyView.value.steps.size

		session.setParameterRepeat(angleX, repeat = true)
		session.setParameterRepeat(ParameterId("ParamUnknown"), repeat = false)

		assertEquals(stepCountBefore, session.historyView.value.steps.size)
		assertFalse(session.dirty.value)
	}

	/** withParameterRepeat flips exactly the named parameter, and is the same instance when nothing changes. */
	@Test
	fun withParameterRepeatSetsTheFlag() {
		val base = linkModel()
		val edited = base.withParameterRepeat(angleY, repeat = true)

		assertEquals(listOf(false, true, false), edited.parameters.map { parameter -> parameter.repeat })
		assertSame(base, base.withParameterRepeat(angleY, repeat = false), "a flag that already holds is the same instance")
		assertSame(base, base.withParameterRepeat(ParameterId("ParamUnknown"), repeat = true), "an unknown id is the same instance")
	}

	/** Setting a note is one dirty step that leaves the pose alone, and undo gives the old note back. */
	@Test
	fun setNoteIsOneDirtyStepLeavingPoseUntouched() {
		val session = EditorSession(paramModel())
		session.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 0.6f))
		val stepCountBefore = session.historyView.value.steps.size

		session.setParameterNote(angleX, "Tilts the head toward the viewer's left")

		assertEquals("Tilts the head toward the viewer's left", session.model.value.parameters.first().note)
		assertEquals(stepCountBefore + 1, session.historyView.value.steps.size)
		assertEquals(0.6f, session.pose.value[angleX], "a note edit never moves the live pose")
		assertTrue(session.dirty.value, "a note edit changes the model and dirties the document")
		assertEquals("change.parameter.note", session.historyView.value.steps.last().labelKey)

		session.undo()
		assertEquals("", session.model.value.parameters.first().note, "undo gives the old note back")
		assertFalse(session.dirty.value)
	}

	/** An empty note clears one that was set, as its own step. */
	@Test
	fun setNoteToEmptyClearsIt() {
		val session = EditorSession(paramModel())
		session.setParameterNote(angleX, "Opens the jaw")
		val stepCountBefore = session.historyView.value.steps.size

		session.setParameterNote(angleX, "")

		assertEquals("", session.model.value.parameters.first().note)
		assertEquals(stepCountBefore + 1, session.historyView.value.steps.size)
	}

	/** A typed note is trimmed, so whitespace alone clears one. */
	@Test
	fun aTypedNoteIsTrimmed() {
		val session = EditorSession(paramModel())

		session.setParameterNote(angleX, "  Opens the jaw \t")
		assertEquals("Opens the jaw", session.model.value.parameters.first().note)

		session.setParameterNote(angleX, "   ")
		assertEquals("", session.model.value.parameters.first().note, "whitespace alone clears the note")
	}

	/** A note holds no line breaks: each one, in any platform's form, becomes one space. */
	@Test
	fun aTypedLineBreakBecomesOneSpace() {
		val session = EditorSession(paramModel())

		session.setParameterNote(angleX, "Opens\r\nthe\rjaw\nwide")

		assertEquals("Opens the jaw wide", session.model.value.parameters.first().note)
	}

	/** Typing the note a parameter already holds, padding included, or naming no parameter, records nothing. */
	@Test
	fun setNoteThatChangesNothingRecordsNothing() {
		val session = EditorSession(paramModel())
		session.setParameterNote(angleX, "Opens the jaw")
		val stepCountBefore = session.historyView.value.steps.size

		session.setParameterNote(angleX, "Opens the jaw")
		session.setParameterNote(angleX, " Opens the jaw ")
		session.setParameterNote(ParameterId("ParamUnknown"), "Opens the jaw")

		assertEquals(stepCountBefore, session.historyView.value.steps.size)
	}

	/** withParameterNote stores the note verbatim on exactly the named parameter, and is the same instance when nothing changes. */
	@Test
	fun withParameterNoteSetsTheNoteVerbatim() {
		val base = linkModel()
		val edited = base.withParameterNote(angleY, " Kept as given ")

		assertEquals(listOf("", " Kept as given ", ""), edited.parameters.map { parameter -> parameter.note })
		assertSame(edited, edited.withParameterNote(angleY, " Kept as given "), "a note that already holds is the same instance")
		assertSame(base, base.withParameterNote(ParameterId("ParamUnknown"), "Anything"), "an unknown id is the same instance")
	}

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

	/** A link is animatable only while both its members have a range to move through; a flat member's link stays in the model. */
	@Test
	fun animatableLinksAreTheLinksWhoseMembersBothMove() {
		val linked = linkModel().withParameterLink(angleX, angleY, linked = true)
		assertEquals(listOf(ParameterLink(angleX, angleY)), linked.animatableLinks())

		val flatLower = linked.withParameterRange(angleY, min = 0f, default = 0f, max = 0f)
		assertEquals(listOf(ParameterLink(angleX, angleY)), flatLower.parameterLinks, "the link itself stays")
		assertTrue(flatLower.animatableLinks().isEmpty(), "a flat lower member leaves the link out")
		assertFalse(angleY in flatLower.animatableParameterIds(), "and is itself not animatable")
		assertTrue(angleX in flatLower.animatableParameterIds(), "while its partner still is")

		val flatUpper = linked.withParameterRange(angleX, min = 1f, default = 1f, max = 1f)
		assertTrue(flatUpper.animatableLinks().isEmpty(), "and so does a flat upper member")
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
}