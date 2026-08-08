package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins that a pending unkeyed channel edit reaches the renderer.
 *
 * A pending edit is session state, never document state - so it cannot ride the model to the renderer and
 * instead travels beside the pose, exactly as the pose itself travels beside the model.  Without this the
 * field would show a value the puppet does not reflect, which defeats the point of adjusting before keying.
 *
 * The override wins over BOTH a stored track and the static fallback, because it represents the value the
 * user is looking at right now.
 */
class ChannelOverrideEvalTest {
	private val paramA = ParameterId("A")
	private val drawableId = DrawableId("d")
	private val owner = KeyformOwner.Drawable(drawableId)

	private fun model(channelGrids: org.umamo.runtime.model.ChannelGrids): PuppetModel {
		val positions = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f)
		val drawable =
			Drawable(
				id = drawableId,
				name = "d",
				parentDeformerId = null,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(positions, FloatArray(positions.size), intArrayOf(0, 1, 2)),
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(paramA, floatArrayOf(0f))),
						listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(positions.size)))),
					),
				channelGrids = channelGrids,
				opacity = 0.25f,
				multiplyColor = ColorRgb(1f, 1f, 1f),
			)
		return PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawableId)),
			rootPartId = null,
		)
	}

	/** With no override, the channel reads its static as usual - the parameter is inert by default. */
	@Test
	fun withoutAnOverrideTheStaticHolds() {
		val inputs = preparePose(model(org.umamo.runtime.model.ChannelGrids.Empty), emptyMap())
		assertEquals(0.25f, inputs.drawables.single().opacity)
	}

	/** An override replaces the static for an untracked channel. */
	@Test
	fun anOverrideBeatsTheStatic() {
		val overrides = mapOf(KeyableTarget(owner, FormChannel.OPACITY) to ChannelValue.Scalar(0.9f))
		val inputs = preparePose(model(org.umamo.runtime.model.ChannelGrids.Empty), emptyMap(), overrides)
		assertEquals(0.9f, inputs.drawables.single().opacity, "the typed value is what the viewport shows")
	}

	/**
	 * An override beats a STORED TRACK too.
	 *
	 * This is the case that matters: editing a property that is already keyed is precisely when the value
	 * on screen must follow what was typed rather than what the grid evaluates, or the field and the puppet
	 * disagree while the user decides whether to key it.
	 */
	@Test
	fun anOverrideBeatsAStoredTrack() {
		val track =
			KeyformGrid(
				listOf(KeyformAxis(paramA, floatArrayOf(-1f, 1f))),
				listOf(
					KeyformCell<ChannelValue>(intArrayOf(0), ChannelValue.Scalar(0f)),
					KeyformCell<ChannelValue>(intArrayOf(1), ChannelValue.Scalar(1f)),
				),
			)
		val keyed = org.umamo.runtime.model.ChannelGrids(mapOf(FormChannel.OPACITY to track))
		val withoutOverride = preparePose(model(keyed), mapOf(paramA to 0f))
		assertEquals(0.5f, withoutOverride.drawables.single().opacity, "the track blends to its midpoint")

		val overrides = mapOf(KeyableTarget(owner, FormChannel.OPACITY) to ChannelValue.Scalar(0.1f))
		val withOverride = preparePose(model(keyed), mapOf(paramA to 0f), overrides)
		assertEquals(0.1f, withOverride.drawables.single().opacity, "the pending edit wins over the stored key")
	}

	/** Colors override the same way, and an override on one channel leaves the others alone. */
	@Test
	fun anOverrideIsScopedToItsChannel() {
		val overrides = mapOf(KeyableTarget(owner, FormChannel.MULTIPLY_COLOR) to ChannelValue.Color(ColorRgb(1f, 0f, 0f)))
		val resolved = preparePose(model(org.umamo.runtime.model.ChannelGrids.Empty), emptyMap(), overrides).drawables.single()
		assertEquals(ColorRgb(1f, 0f, 0f), resolved.multiplyColor)
		assertEquals(0.25f, resolved.opacity, "an unrelated channel keeps its own value")
	}
}