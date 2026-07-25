package org.umamo.edit

import org.umamo.runtime.eval.scalarAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.ColorRgb
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
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the scalar / color keyform authoring loop: bind, capture, remove, and undo.
 *
 * These channels come first precisely because they need no deformer-chain inverse - the capture always
 * lands on a key, so the write is a plain assignment.  What the tests below are really guarding is the two
 * evaluator-forced invariants that make the difference between a key that works and one that makes art
 * disappear: a seeded axis SPANS the parameter's range, and removal COLLAPSES below two keys.
 */
class KeyformChannelEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val drawableId = DrawableId("d")
	private val target = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.OPACITY)

	private fun parameter(id: ParameterId): Parameter = Parameter(id, id.raw, min = -30f, max = 30f, default = 0f)

	private fun model(): PuppetModel =
		PuppetModel(
			parameters = listOf(parameter(angleX), parameter(angleY)),
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
		)

	/** The opacity a model's drawable evaluates to at [value] on [parameterId]. */
	private fun opacityAt(puppet: PuppetModel, parameterId: ParameterId, value: Float): Float {
		val drawable = puppet.drawables.single()
		return drawable.channelGrids.scalarAt(
			FormChannel.OPACITY,
			drawable.opacity,
			paramValue = { id -> if (id == parameterId) value else 0f },
		)
	}

	/**
	 * The first capture BINDS: it seeds an axis spanning min / default / max holding the entity's current
	 * static value, then writes the captured value at the pose.
	 *
	 * Spanning matters - an axis that stopped short would leave the channel falling back to its static at
	 * the ends of the slider, which reads as the key mysteriously not applying.
	 */
	@Test
	fun theFirstCaptureBindsAnAxisSpanningTheRange() {
		val captured =
			model().withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Scalar(1f))

		val track = assertNotNull(captured.drawables.single().channelGrids[FormChannel.OPACITY])
		assertEquals(listOf(angleX), track.axes.map { it.parameterId })
		assertEquals(listOf(-30f, 0f, 15f, 30f), track.axes.single().keys.toList(), "seeded min/default/max plus the captured key")
		assertEquals(1f, opacityAt(captured, angleX, 15f), "the captured value lands at the pose")
		assertEquals(0.25f, opacityAt(captured, angleX, -30f), "the static holds at the ends - binding alone changed nothing")
		assertEquals(0.25f, opacityAt(captured, angleX, 30f))
	}

	/** Capturing on a second parameter adds an axis and preserves the motion already authored. */
	@Test
	fun capturingOnASecondParameterPreservesExistingMotion() {
		val first = model().withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 30f), ChannelValue.Scalar(1f))
		val second = first.withChannelKeyCaptured(target, parameter(angleY), mapOf(angleY to 30f), ChannelValue.Scalar(0.5f))

		val track = assertNotNull(second.drawables.single().channelGrids[FormChannel.OPACITY])
		assertEquals(listOf(angleX, angleY), track.axes.map { it.parameterId }, "the new axis is appended")
		// angleY sits at its default, so the angleX motion authored first must read back unchanged.
		assertEquals(1f, opacityAt(second, angleX, 30f), "the first parameter's motion survives the second bind")
	}

	/** A capture at a pose already on a key overwrites that cell without reshaping the axis. */
	@Test
	fun capturingOnAnExistingKeyOverwrites() {
		val first = model().withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Scalar(1f))
		val second = first.withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Scalar(0.5f))

		val track = assertNotNull(second.drawables.single().channelGrids[FormChannel.OPACITY])
		assertEquals(4, track.axes.single().keys.size, "no new key was added")
		assertEquals(0.5f, opacityAt(second, angleX, 15f))
	}

	/** A value of the wrong kind for the channel is refused rather than silently stored. */
	@Test
	fun aMismatchedValueKindIsRefused() {
		val start = model()
		val colorIntoScalar =
			start.withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Color(ColorRgb(1f, 0f, 0f)))
		assertSame(start, colorIntoScalar)
	}

	/** Removing an interior key leaves the axis intact. */
	@Test
	fun removingAnInteriorKeyKeepsTheAxis() {
		val captured = model().withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Scalar(1f))
		val removed = captured.withChannelKeyRemoved(target, parameter(angleX), mapOf(angleX to 15f))

		val track = assertNotNull(removed.drawables.single().channelGrids[FormChannel.OPACITY])
		assertEquals(listOf(-30f, 0f, 30f), track.axes.single().keys.toList())
	}

	/**
	 * Removing down past two keys collapses the axis, and collapsing the only axis drops the track - the
	 * channel goes back to its static rather than being left on a one-key axis that resolves nowhere.
	 */
	@Test
	fun removingPastTwoKeysUnbindsTheChannel() {
		var puppet = model().withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Scalar(1f))
		for (keyValue in listOf(15f, 0f, -30f)) {
			puppet = puppet.withChannelKeyRemoved(target, parameter(angleX), mapOf(angleX to keyValue))
		}
		assertTrue(puppet.drawables.single().channelGrids.isEmpty, "the track is gone")
		assertFalse(puppet.isChannelKeyedOn(target, angleX))
		assertEquals(0.25f, opacityAt(puppet, angleX, 15f), "the channel reads its static again")
	}

	/** A pose that is not ON a key removes nothing - picking "the nearest" would be a guess. */
	@Test
	fun removingBetweenKeysIsARefusal() {
		val captured = model().withChannelKeyCaptured(target, parameter(angleX), mapOf(angleX to 15f), ChannelValue.Scalar(1f))
		assertSame(captured, captured.withChannelKeyRemoved(target, parameter(angleX), mapOf(angleX to 7f)))
	}

	/** Capture and removal are each one undo step, and undo restores the prior track exactly. */
	@Test
	fun captureAndRemovalUndoAsSingleSteps() {
		val session = EditorSession(model())
		session.captureChannelKey(target, parameter(angleX), ChannelValue.Scalar(1f))
		assertTrue(session.model.value.isChannelKeyedOn(target, angleX))

		session.removeChannelKey(target, parameter(angleX))
		session.undo()
		assertTrue(session.model.value.isChannelKeyedOn(target, angleX), "undo restores the removed key")

		session.undo()
		assertFalse(session.model.value.isChannelKeyedOn(target, angleX), "undo again unbinds the channel")
	}
}
