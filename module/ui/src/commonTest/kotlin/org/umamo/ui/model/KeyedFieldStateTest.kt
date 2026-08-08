package org.umamo.ui.model

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
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the keyed-field tint states.
 *
 * These are what stop manual keying from silently discarding work: BetweenKeys warns that an edit here
 * needs an explicit key, and ModifiedUnkeyed says that has already happened.  A Cubism migrant has never
 * needed either signal - Cubism auto-keys - so getting them wrong is precisely the friction they hit.
 */
class KeyedFieldStateTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val drawableId = DrawableId("d")
	private val target = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.OPACITY)

	private fun opacityTrack(): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f))),
			listOf(
				KeyformCell<ChannelValue>(intArrayOf(0), ChannelValue.Scalar(0f)),
				KeyformCell<ChannelValue>(intArrayOf(1), ChannelValue.Scalar(0.5f)),
				KeyformCell<ChannelValue>(intArrayOf(2), ChannelValue.Scalar(1f)),
			),
		)

	/** Opacity keyed on BOTH parameters, keys at -30 / 0 / 30 on each - a linked pad's shape. */
	private fun twoAxisOpacityTrack(): KeyformGrid<ChannelValue> =
		KeyformGrid(
			listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f)), KeyformAxis(angleY, floatArrayOf(-30f, 0f, 30f))),
			(0..2).flatMap { yIndex ->
				(0..2).map { xIndex ->
					KeyformCell<ChannelValue>(intArrayOf(xIndex, yIndex), ChannelValue.Scalar(0.5f))
				}
			},
		)

	private fun model(channelGrids: ChannelGrids): PuppetModel =
		PuppetModel(
			parameters =
				listOf(
					Parameter(angleX, angleX.raw, min = -30f, max = 30f, default = 0f),
					Parameter(angleY, angleY.raw, min = -30f, max = 30f, default = 0f),
				),
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
						channelGrids = channelGrids,
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	private fun stateAt(
		channelGrids: ChannelGrids,
		pose: Map<ParameterId, Float>,
		pending: Map<KeyableTarget, ChannelValue> = emptyMap(),
	): KeyedFieldState =
		keyedFieldStateOf(
			puppet = model(channelGrids),
			target = target,
			pose = pose,
			pendingEdits = pending,
		)

	/** An untracked channel is not keyed, so the field looks ordinary. */
	@Test
	fun anUntrackedChannelIsNone() {
		assertEquals(KeyedFieldState.None, stateAt(ChannelGrids.Empty, mapOf(angleX to 0f)))
	}

	/**
	 * The state follows the TRACK's own axes, not whatever parameter is targeted.
	 *
	 * This is the difference between the tint answering "is the value under this field stored" and answering
	 * "is it stored on the axis you happen to have clicked".  A rigger reads it as the first; the second
	 * would paint a keyed opacity as unstored whenever the target was the other half of a linked pad.  The
	 * targeted parameter is not even an input here, so a pose sitting on a key reads as on-key regardless.
	 */
	@Test
	fun theStateFollowsTheTracksOwnAxes() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, mapOf(angleX to 0f)), "on a key of its own axis")
		assertEquals(
			KeyedFieldState.OnKey,
			stateAt(grids, mapOf(angleX to 0f, angleY to 17f)),
			"an unrelated parameter's value is irrelevant",
		)
	}

	/** Exactly on a key: editing here writes that key. */
	@Test
	fun onAKeyIsOnKey() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, mapOf(angleX to 0f)))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, mapOf(angleX to 30f)))
	}

	/**
	 * A parameter absent from the pose resolves at its DEFAULT, which is what the evaluator does too.
	 *
	 * A pose map need not list every parameter, and treating a missing one as zero would report a state for
	 * a pose the viewport is not showing.
	 */
	@Test
	fun aMissingPoseValueResolvesAtTheParameterDefault() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, emptyMap()), "the default, 0, is a key")
	}

	/**
	 * A MULTI-axis track is on-key only when the pose sits on a key of every axis.
	 *
	 * That is exactly when a capture overwrites the cell it lands on rather than inserting a slice, so it is
	 * the only reading under which OnKey's promise - "editing writes that key" - is true.
	 */
	@Test
	fun aMultiAxisTrackNeedsEveryAxisOnAKey() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to twoAxisOpacityTrack()))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, mapOf(angleX to 0f, angleY to 30f)))
		assertEquals(
			KeyedFieldState.BetweenKeys,
			stateAt(grids, mapOf(angleX to 0f, angleY to 15f)),
			"between keys on the second axis is still between keys",
		)
	}

	/**
	 * Within the evaluator's snap tolerance still counts as on-key.
	 *
	 * An exact compare would flicker to "between" a hair either side of a key, disagreeing with the key the
	 * pose actually resolved to - the tint has to match what is being rendered, not a stricter idea of it.
	 */
	@Test
	fun withinTheSnapToleranceIsStillOnKey() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, mapOf(angleX to 0.0005f)))
	}

	/** Between keys: an edit here is lost unless explicitly keyed. */
	@Test
	fun betweenKeysIsBetweenKeys() {
		assertEquals(
			KeyedFieldState.BetweenKeys,
			stateAt(ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack())), mapOf(angleX to 15f)),
		)
	}

	/** A pending edit outranks every other state - it is the one carrying a warning. */
	@Test
	fun aPendingEditWinsOverEveryOtherState() {
		val pending = mapOf(target to ChannelValue.Scalar(0.9f))
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(
			KeyedFieldState.ModifiedUnkeyed,
			stateAt(grids, mapOf(angleX to 0f), pending),
			"even sitting on a key",
		)
		assertEquals(KeyedFieldState.ModifiedUnkeyed, stateAt(grids, mapOf(angleX to 15f), pending), "and between keys")
	}

	/**
	 * An UNTRACKED channel is never tinted, pending edit or not.
	 *
	 * A field being scrubbed writes the pending buffer on every frame regardless of whether the channel is
	 * keyed (that is what makes the viewport follow the pointer), so reading the pending map before checking
	 * for a track would paint the orange "edited and not keyed" warning across every ordinary drag.  Nothing is
	 * uncommitted here: the release writes the owner's static as a plain undoable edit.
	 */
	@Test
	fun anUntrackedChannelIsNeverTintedEvenMidScrub() {
		val pending = mapOf(target to ChannelValue.Scalar(0.9f))
		assertEquals(KeyedFieldState.None, stateAt(ChannelGrids.Empty, mapOf(angleX to 0f), pending))
		assertEquals(KeyedFieldState.None, stateAt(ChannelGrids.Empty, emptyMap(), pending), "and at any pose")
	}
}