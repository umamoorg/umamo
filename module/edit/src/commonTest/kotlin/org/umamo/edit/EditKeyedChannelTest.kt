package org.umamo.edit

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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins the ONE keyable-row edit-routing rule: a KEYED channel's typed value goes pending (writing the
 * static would be shadowed by the track, so the edit would appear silently rejected), and an unkeyed
 * channel writes the static.  The rule used to be hand-copied per row, and the part rows shipped without
 * it entirely.
 */
class EditKeyedChannelTest {
	private val angleX = ParameterId("ParamAngleX")
	private val drawableId = DrawableId("d")
	private val keyedTarget = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.OPACITY)
	private val unkeyedTarget = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.MULTIPLY_COLOR)

	/** The descriptor a real opacity row supplies - the same one whichever branch stores the value. */
	private fun change(opacity: Float): Change = DrawableChange.SetOpacity(drawableId, opacity)

	private fun session(): EditorSession {
		val opacityTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
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
		return EditorSession(
			PuppetModel(
				parameters = listOf(Parameter(angleX, angleX.raw, min = -1f, max = 1f, default = 0f)),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = listOf(drawable),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)
	}

	/** A keyed channel's edit goes pending; the static write path must not run. */
	@Test
	fun aKeyedChannelGoesPending() {
		val editorSession = session()
		var staticWrites = 0

		editorSession.editKeyedChannel(keyedTarget, ChannelValue.Scalar(0.5f), change(0.5f)) { staticWrites++ }

		assertEquals(0, staticWrites, "the shadowed static must not be written")
		assertEquals(
			ChannelValue.Scalar(0.5f),
			editorSession.pendingChannelEdits.value[keyedTarget],
			"the typed value waits as a pending edit for `I`",
		)
		assertTrue(editorSession.canUndo.value, "and it is still one undo step")
	}

	/** An unkeyed channel has no track to shadow it, so the static is the real store. */
	@Test
	fun anUnkeyedChannelWritesTheStatic() {
		val editorSession = session()
		var staticWrites = 0

		editorSession.editKeyedChannel(unkeyedTarget, ChannelValue.Scalar(0.5f), change(0.5f)) { staticWrites++ }

		assertEquals(1, staticWrites, "the unkeyed path writes the static")
		assertFalse(unkeyedTarget in editorSession.pendingChannelEdits.value, "nothing is left pending")
		assertTrue(editorSession.pendingChannelEdits.value.isEmpty())
	}
}
