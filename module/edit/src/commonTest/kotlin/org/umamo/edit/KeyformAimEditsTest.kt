package org.umamo.edit

import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the keyform AIM semantics - which parameter an edit writes on, and whether it acts where the user
 * pointed or where the pose stands.
 *
 * These rules used to live in the UI module taking a Compose hover type, where nothing could exercise them
 * without a composition; both of the bugs they encode (an aimed edit landing on the targeted axis instead of
 * the pointed-at one, and an aim at empty track space falling back to the pose) shipped because of that.
 */
class KeyformAimEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val drawableId = DrawableId("d")
	private val target = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.OPACITY)
	private val track = KeyformTrackRef.Channel(target)

	/** The sheet row the opacity track is drawn on - opaque to this module, and identity for a selected key. */
	private val row = "drawable:d/OPACITY"

	/**
	 * A session whose drawable has an opacity track keyed at the ends of [keyedOn]'s range, with both
	 * parameters present and [ParameterId] angleX targeted.
	 */
	private fun session(keyedOn: ParameterId = angleX): EditorSession {
		val opacityTrack =
			KeyformGrid(
				listOf(KeyformAxis(keyedOn, floatArrayOf(-1f, 1f))),
				listOf<KeyformCell<ChannelValue>>(
					KeyformCell(intArrayOf(0), ChannelValue.Scalar(0.25f)),
					KeyformCell(intArrayOf(1), ChannelValue.Scalar(1f)),
				),
			)
		val drawable =
			Drawable(
				id = drawableId,
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = null,
				geometryGrid = null,
				channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack)),
			)
		val editorSession =
			EditorSession(
				PuppetModel(
					parameters =
						listOf(
							Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f),
							Parameter(angleY, angleY.raw, min = -1f, max = 1f, default = 0f),
						),
					parts = emptyList(),
					deformers = emptyList(),
					drawables = listOf(drawable),
					rootChildren = emptyList(),
					rootPartId = null,
				),
			)
		editorSession.setParameterSelection(ParameterSelection.of(angleX))
		return editorSession
	}

	/** The opacity track's key positions on [parameterId]'s axis, or null when it is not keyed on it. */
	private fun EditorSession.keysOn(parameterId: ParameterId): List<Float>? {
		val grid = model.value.drawables.single().channelGrids[FormChannel.OPACITY] ?: return null
		val axisIndex = grid.axisIndexOf(parameterId)
		return if (axisIndex < 0) null else grid.axes[axisIndex].keys.toList()
	}

	/** An aimed capture keys WHERE it was aimed, not at the pose the rig happens to stand in. */
	@Test
	fun anAimedCaptureKeysWhereItWasAimed() {
		val editorSession = session()

		editorSession.captureKeyOnTrack(track, angleX, KeyformAim.Position(0.5f, keyIndex = null))

		assertEquals(listOf(-1f, 0.5f, 1f), editorSession.keysOn(angleX))
	}

	/** A capture at the pose stores the PENDING typed value and consumes it. */
	@Test
	fun aPoseCaptureStoresThePendingEdit() {
		val editorSession = session()
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))

		editorSession.captureKeyOnTrack(track, angleX, KeyformAim.Pose)

		assertEquals(listOf(-1f, 0f, 1f), editorSession.keysOn(angleX), "the pose's key was added")
		assertEquals(
			ChannelValue.Scalar(0.75f),
			editorSession.model.value.channelValueAt(target, editorSession.pose.value),
			"the key holds the value that was typed, not the value that was stored",
		)
		assertTrue(editorSession.pendingChannelEdits.value.isEmpty(), "the captured pending edit was consumed")
	}

	/**
	 * The capture's own history step records the pending edit as CONSUMED, so redo does not resurrect it.
	 *
	 * A snapshot defaults every field to live state, so clearing the pending edit after the capture pushed
	 * left the consumed value inside the step that consumed it: redoing the capture re-showed the
	 * uncommitted-edit warning over the very key that now stores the value, and left the session's live map
	 * disagreeing with its own history about what was still pending.
	 */
	@Test
	fun redoingAPoseCaptureDoesNotResurrectTheConsumedEdit() {
		val editorSession = session()
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.75f))

		editorSession.captureKeyOnTrack(track, angleX, KeyformAim.Pose)
		editorSession.undo()
		editorSession.redo()

		assertTrue(
			editorSession.pendingChannelEdits.value.isEmpty(),
			"the capture step holds no pending edit, because the capture is what consumed it",
		)
		assertEquals(listOf(-1f, 0f, 1f), editorSession.keysOn(angleX), "and the key it captured is back")
	}

	/**
	 * An aimed edit keeps the sheet's key selection on the key it named, as ONE undo step.
	 *
	 * Both directions of the same renumbering rule.  An insert shifts every key at or above it up one
	 * ordinal, so a selected mark to the right of the insertion point must shift with it to keep naming the
	 * same key; a removal shifts every key above it down one ordinal, so a selected mark above a removed key
	 * must shift the same way rather than staying on the ordinal the removal freed.
	 */
	@Test
	fun anAimedEditCarriesTheKeySelectionWithIt() {
		val editorSession = session()
		// Keys at [-1, 1] with the one at 1 selected; the insert at 0 lands between them.
		editorSession.setKeySelection(setOf(TrackKeyRef(angleX, row, 1)))

		editorSession.captureKeyOnTrack(track, angleX, KeyformAim.Position(0f, keyIndex = null), row)

		assertEquals(listOf(-1f, 0f, 1f), editorSession.keysOn(angleX))
		assertEquals(
			setOf(TrackKeyRef(angleX, row, 2)),
			editorSession.keySelection.value,
			"the selection follows its own key past the one just inserted below it",
		)

		// And back out again: the removal renumbers the other way.
		editorSession.removeKeyOnTrack(track, angleX, KeyformAim.Position(0f, keyIndex = 1), row)

		assertEquals(listOf(-1f, 1f), editorSession.keysOn(angleX))
		assertEquals(
			setOf(TrackKeyRef(angleX, row, 1)),
			editorSession.keySelection.value,
			"and back down again when the key below it goes",
		)
	}

	/** An insert ABOVE the selection renumbers nothing, and the whole capture stays one undo step. */
	@Test
	fun anInsertAboveTheSelectionLeavesItAloneAndRecordsOneStep() {
		val editorSession = session()
		editorSession.setKeySelection(setOf(TrackKeyRef(angleX, row, 0)))

		editorSession.captureKeyOnTrack(track, angleX, KeyformAim.Position(0f, keyIndex = null), row)

		assertEquals(setOf(TrackKeyRef(angleX, row, 0)), editorSession.keySelection.value, "nothing below it moved")
		editorSession.undo()
		assertEquals(listOf(-1f, 1f), editorSession.keysOn(angleX), "one undo reverses the whole capture")
		assertEquals(setOf(TrackKeyRef(angleX, row, 0)), editorSession.keySelection.value)
	}

	/**
	 * Aiming at track space with no key there removes NOTHING - it must not fall back to the pose, which
	 * would destroy a key the user never pointed at.
	 */
	@Test
	fun anAimAtEmptyTrackSpaceRemovesNothing() {
		val editorSession = session()
		// The pose stands exactly on a key, so a pose-fallback would have something to delete.
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to -1f))
		val modelBefore = editorSession.model.value

		editorSession.removeKeyOnTrack(track, angleX, KeyformAim.Position(0.3f, keyIndex = null))

		assertSame(modelBefore, editorSession.model.value, "aiming at nothing removed nothing")
		assertEquals(listOf(-1f, 1f), editorSession.keysOn(angleX))
	}

	/** With no place aimed at, a removal takes the key the pose is standing on. */
	@Test
	fun aPoseRemovalTakesTheKeyUnderThePose() {
		val editorSession = session()
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to -1f))

		editorSession.removeKeyOnTrack(track, angleX, KeyformAim.Pose)

		assertEquals(null, editorSession.keysOn(angleX), "the last key went, so the channel is unkeyed again")
	}

	/**
	 * A named parameter beats the targeted one: a linked pad's second section is keyed on an axis that is
	 * not the selection's active member, and an edit aimed there must land on THAT axis.
	 */
	@Test
	fun theNamedParameterBeatsTheTargetedOne() {
		val editorSession = session(keyedOn = angleY)
		assertEquals(angleX, editorSession.parameterSelection.value.active, "angleX is what is targeted")

		editorSession.captureKeyOnTrack(track, angleY, KeyformAim.Position(0.5f, keyIndex = null))

		assertEquals(listOf(-1f, 0.5f, 1f), editorSession.keysOn(angleY), "the key landed on the named axis")
		assertEquals(null, editorSession.keysOn(angleX), "the targeted axis was not touched")
	}

	/** With no parameter named, the edit falls back to the targeted one. */
	@Test
	fun anUnnamedParameterFallsBackToTheTarget() {
		val editorSession = session()

		editorSession.captureKeyOnTrack(track, parameterId = null, aim = KeyformAim.Position(0.5f, keyIndex = null))

		assertEquals(listOf(-1f, 0.5f, 1f), editorSession.keysOn(angleX))
	}

	/** With nothing named and nothing targeted there is no axis to write on, so the model is untouched. */
	@Test
	fun noParameterAtAllRefuses() {
		val editorSession = session()
		editorSession.setParameterSelection(ParameterSelection())
		val modelBefore = editorSession.model.value

		editorSession.captureKeyOnTrack(track, parameterId = null, aim = KeyformAim.Position(0.5f, keyIndex = null))

		assertSame(modelBefore, editorSession.model.value)
		assertNotNull(editorSession.notice.value, "the refusal is announced rather than silent")
	}
}
