package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the unkeyed-edit buffer: a value typed into a keyed property but not yet keyed.
 *
 * The behaviour it encodes is Blender's, and the discarding is the point rather than a shortcoming - a
 * pending value was chosen FOR a pose, so carrying it to a different one would apply an edit somewhere it
 * was never meant.  What must never happen is a pending edit surviving into the undo history, which would
 * bury the real edits under a step per keystroke.
 */
class PendingChannelEditTest {
	private val angleX = ParameterId("ParamAngleX")
	private val drawableId = DrawableId("d")
	private val target = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.OPACITY)

	private fun session(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = listOf(Parameter(angleX, angleX.raw, min = -30f, max = 30f, default = 0f)),
				parts = emptyList(),
				deformers = emptyList(),
				drawables =
					listOf(
						Drawable(
							id = drawableId,
							name = "d",
							parentDeformerId = null,
							blendMode = BlendMode.Normal,
							maskedBy = emptyList(),
							mesh = null,
							geometryGrid = null,
							opacity = 0.25f,
						),
					),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)

	/** A pending edit is held for reading but records no history step and no model change. */
	@Test
	fun aPendingEditIsTransient() {
		val editorSession = session()
		val stepsBefore = editorSession.historyView.value.steps.size
		val modelBefore = editorSession.model.value

		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))

		assertEquals(ChannelValue.Scalar(0.75f), editorSession.pendingChannelEdits.value[target])
		assertEquals(stepsBefore, editorSession.historyView.value.steps.size, "no undo step is recorded")
		assertTrue(modelBefore === editorSession.model.value, "the document is untouched")
		assertTrue(!editorSession.dirty.value, "an unkeyed edit does not dirty the document")
	}

	/** Moving the pose discards it - the value was chosen for the pose being left. */
	@Test
	fun aPoseMoveDiscardsIt() {
		val editorSession = session()
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 15f))

		assertTrue(editorSession.pendingChannelEdits.value.isEmpty(), "the pending edit did not survive the scrub")
	}

	/** A history jump lands on a pose the pending value was never chosen for, so it goes too. */
	@Test
	fun anUndoDiscardsIt() {
		val editorSession = session()
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 15f))
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))

		editorSession.undo()

		assertTrue(editorSession.pendingChannelEdits.value.isEmpty())
	}

	/** Capturing consumes it: the captured key holds the typed value, and nothing stays pending. */
	@Test
	fun capturingConsumesIt() {
		val editorSession = session()
		val parameter = editorSession.model.value.parameters.single()
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))

		val pending = editorSession.pendingChannelEdits.value.getValue(target)
		editorSession.captureChannelKey(target, parameter, pending)
		editorSession.clearPendingChannelEdits()

		assertTrue(editorSession.pendingChannelEdits.value.isEmpty())
		assertTrue(editorSession.model.value.isChannelKeyedOn(target, angleX), "the channel is now keyed")
		assertEquals(
			ChannelValue.Scalar(0.75f),
			editorSession.model.value.channelValueAt(target, editorSession.pose.value),
			"the key holds the value that was typed, not the value that was stored",
		)
	}

	/** Several properties can be pending at once, each keyed independently. */
	@Test
	fun severalTargetsCanBePendingAtOnce() {
		val editorSession = session()
		val multiplyTarget = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.MULTIPLY_COLOR)
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))
		editorSession.setPendingChannelEdit(multiplyTarget, ChannelValue.Color(org.umamo.runtime.model.ColorRgb(1f, 0f, 0f)))

		assertEquals(2, editorSession.pendingChannelEdits.value.size)
	}

	/**
	 * Capturing one target consumes only ITS pending edit: the pose did not move, so every other target's
	 * typed value is still the value its user chose and must survive for its own insert.
	 */
	@Test
	fun capturingOneTargetKeepsOthersPending() {
		val editorSession = session()
		val parameter = editorSession.model.value.parameters.single()
		val multiplyTarget = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.MULTIPLY_COLOR)
		val multiplyValue = ChannelValue.Color(org.umamo.runtime.model.ColorRgb(1f, 0f, 0f))
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))
		editorSession.setPendingChannelEdit(multiplyTarget, multiplyValue)

		val pending = editorSession.pendingChannelEdits.value.getValue(target)
		editorSession.captureChannelKey(target, parameter, pending)
		editorSession.clearPendingChannelEdit(target)

		assertEquals(
			mapOf<KeyableTarget, ChannelValue>(multiplyTarget to multiplyValue),
			editorSession.pendingChannelEdits.value,
			"the uncaptured target's typed value survives",
		)
	}
}