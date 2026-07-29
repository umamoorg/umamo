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
		parameterId: ParameterId?,
		poseValue: Float,
		pending: Map<KeyableTarget, ChannelValue> = emptyMap(),
	): KeyedFieldState =
		keyedFieldStateOf(
			puppet = model(channelGrids),
			target = target,
			parameterId = parameterId,
			pose = if (parameterId == null) emptyMap() else mapOf(parameterId to poseValue),
			pendingEdits = pending,
		)

	/** An untracked channel is not keyed, so the field looks ordinary. */
	@Test
	fun anUntrackedChannelIsNone() {
		assertEquals(KeyedFieldState.None, stateAt(ChannelGrids.Empty, angleX, 0f))
	}

	/** With no parameter targeted there is nothing to be keyed against. */
	@Test
	fun noTargetedParameterIsNone() {
		assertEquals(KeyedFieldState.None, stateAt(ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack())), null, 0f))
	}

	/** A channel keyed on a DIFFERENT parameter is not keyed against this one. */
	@Test
	fun keyedOnAnotherParameterIsNone() {
		assertEquals(KeyedFieldState.None, stateAt(ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack())), angleY, 0f))
	}

	/** Exactly on a key: editing here writes that key. */
	@Test
	fun onAKeyIsOnKey() {
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, angleX, 0f))
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, angleX, 30f))
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
		assertEquals(KeyedFieldState.OnKey, stateAt(grids, angleX, 0.0005f))
	}

	/** Between keys: an edit here is lost unless explicitly keyed. */
	@Test
	fun betweenKeysIsBetweenKeys() {
		assertEquals(
			KeyedFieldState.BetweenKeys,
			stateAt(ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack())), angleX, 15f),
		)
	}

	/** A pending edit outranks every other state - it is the one carrying a warning. */
	@Test
	fun aPendingEditWinsOverEveryOtherState() {
		val pending = mapOf(target to ChannelValue.Scalar(0.9f))
		val grids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack()))
		assertEquals(KeyedFieldState.ModifiedUnkeyed, stateAt(grids, angleX, 0f, pending), "even sitting on a key")
		assertEquals(KeyedFieldState.ModifiedUnkeyed, stateAt(grids, angleX, 15f, pending), "and between keys")
		assertEquals(KeyedFieldState.ModifiedUnkeyed, stateAt(ChannelGrids.Empty, angleX, 0f, pending), "and untracked")
	}
}
