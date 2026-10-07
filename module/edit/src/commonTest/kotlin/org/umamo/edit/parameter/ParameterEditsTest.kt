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
 * Tests for ParameterEdits.kt: the pure range / link transforms, and the session steps that commit a range
 * with its pose re-clamp and a link with its narrowed target.
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
}