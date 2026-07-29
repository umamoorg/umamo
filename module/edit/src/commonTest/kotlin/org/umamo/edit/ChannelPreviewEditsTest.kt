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
import kotlin.test.assertTrue

/**
 * The two-phase contract behind a property field's drag-scrub: preview is transient, commit is one step.
 *
 * A scrub that only reported its value on release moved the number and nothing else, so the viewport sat
 * still for the whole gesture; one that committed per frame would bury the history under drag noise.
 */
class ChannelPreviewEditsTest {
	private val angleX = ParameterId("ParamAngleX")
	private val drawableId = DrawableId("d")
	private val target = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.OPACITY)

	/** A drawable whose opacity channel is keyed when [keyed], and bare otherwise. */
	private fun model(keyed: Boolean): PuppetModel =
		PuppetModel(
			parameters = listOf(Parameter(angleX, "ParamAngleX", min = -30f, max = 30f, default = 0f)),
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
						opacity = 1f,
						channelGrids =
							if (!keyed) {
								ChannelGrids.Empty
							} else {
								ChannelGrids(
									mapOf(
										FormChannel.OPACITY to
											KeyformGrid(
												axes = listOf(KeyformAxis(angleX, floatArrayOf(-30f, 0f, 30f))),
												cells =
													listOf(
														KeyformCell(intArrayOf(0), ChannelValue.Scalar(1f)),
														KeyformCell(intArrayOf(1), ChannelValue.Scalar(1f)),
														KeyformCell(intArrayOf(2), ChannelValue.Scalar(1f)),
													),
											),
									),
								)
							},
					),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** A preview reaches the renderer's override map without touching the document or the history. */
	@Test
	fun previewIsTransientOnAnUnkeyedChannel() {
		val session = EditorSession(model(keyed = false))
		session.previewChannelEdit(target, ChannelValue.Scalar(0.25f))

		assertEquals(ChannelValue.Scalar(0.25f), session.pendingChannelEdits.value[target])
		assertEquals(1f, session.model.value.drawables.first().opacity, "the document is untouched")
		assertTrue(!session.canUndo.value, "a preview records no undo step")
	}

	/**
	 * Committing an unkeyed channel writes the static AND retires the preview.
	 *
	 * Without the retire the stale pending value keeps shadowing the static just written, so the field
	 * stays tinted as an uncommitted edit even though the edit landed.
	 */
	@Test
	fun commitOnAnUnkeyedChannelWritesTheStaticAndRetiresThePreview() {
		val session = EditorSession(model(keyed = false))
		session.previewChannelEdit(target, ChannelValue.Scalar(0.25f))
		session.editKeyedChannel(target, ChannelValue.Scalar(0.25f)) {
			session.setDrawableOpacity(drawableId, 0.25f)
		}

		assertEquals(0.25f, session.model.value.drawables.first().opacity)
		assertEquals(null, session.pendingChannelEdits.value[target], "the preview is retired")
		assertTrue(session.canUndo.value, "the commit is one undo step")
	}

	/** A whole scrub is ONE undo step however many frames it previewed through. */
	@Test
	fun aScrubIsOneUndoStep() {
		val session = EditorSession(model(keyed = false))
		for (frame in 1..20) {
			session.previewChannelEdit(target, ChannelValue.Scalar(frame / 20f))
		}
		session.editKeyedChannel(target, ChannelValue.Scalar(1f)) { session.setDrawableOpacity(drawableId, 0.5f) }
		session.undo()

		assertEquals(1f, session.model.value.drawables.first().opacity, "one undo returns to the start")
		assertTrue(!session.canUndo.value, "and there was only ever one step")
	}

	/**
	 * On a KEYED channel a commit stays pending, because writing the static would be shadowed by the track.
	 */
	@Test
	fun commitOnAKeyedChannelStaysPending() {
		val session = EditorSession(model(keyed = true))
		session.editKeyedChannel(target, ChannelValue.Scalar(0.25f)) { session.setDrawableOpacity(drawableId, 0.25f) }

		assertEquals(ChannelValue.Scalar(0.25f), session.pendingChannelEdits.value[target])
		assertEquals(1f, session.model.value.drawables.first().opacity, "the static is not the store here")
		assertTrue(!session.canUndo.value, "a pending edit is not a document change")
	}

	/** Retiring one target's preview leaves every other target's alone. */
	@Test
	fun retiringOnePreviewLeavesTheOthers() {
		val session = EditorSession(model(keyed = false))
		val other = KeyableTarget(KeyformOwner.Drawable(drawableId), FormChannel.DRAW_ORDER)
		session.previewChannelEdit(target, ChannelValue.Scalar(0.25f))
		session.previewChannelEdit(other, ChannelValue.Scalar(720f))
		session.editKeyedChannel(target, ChannelValue.Scalar(0.25f)) {
			session.setDrawableOpacity(drawableId, 0.25f)
		}

		assertEquals(null, session.pendingChannelEdits.value[target])
		assertEquals(ChannelValue.Scalar(720f), session.pendingChannelEdits.value[other], "the other survives")
	}
}
