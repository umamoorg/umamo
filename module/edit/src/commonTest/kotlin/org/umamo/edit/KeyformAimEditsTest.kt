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
