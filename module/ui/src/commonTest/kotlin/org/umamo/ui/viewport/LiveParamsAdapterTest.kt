package org.umamo.ui.viewport

import org.umamo.edit.EditorSession
import org.umamo.runtime.model.ChannelValue
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
 * What a live parameter preview does to a pending unkeyed channel edit.
 *
 * A pending value is chosen FOR one pose, and the session already discards them when a pose COMMIT moves.
 * The preview path does not go through commit, so a scrub carried the pending value across every pose it
 * passed through: a half-typed opacity showed as a flat override over the whole range being scrubbed and
 * only snapped back to the track on release.
 */
class LiveParamsAdapterTest {
	private val angleX = ParameterId("ParamAngleX")
	private val target = KeyableTarget(KeyformOwner.Drawable(DrawableId("d")), FormChannel.OPACITY)

	private fun session(): EditorSession =
		EditorSession(
			PuppetModel(
				parameters = listOf(Parameter(angleX, angleX.raw, min = -30f, max = 30f, default = 0f)),
				parts = emptyList(),
				deformers = emptyList(),
				drawables = emptyList(),
				rootChildren = emptyList(),
				rootPartId = null,
			),
		)

	private fun adapter(editorSession: EditorSession): Pair<LiveParams, LiveParamsAdapter> {
		val liveParams = LiveParams(mapOf(angleX to 0f))
		return liveParams to LiveParamsAdapter(liveParams, editorSession)
	}

	/** The first frame that actually moves the pose retires the pending edits - not the release. */
	@Test
	fun aPreviewThatMovesThePoseRetiresPendingEdits() {
		val editorSession = session()
		val (liveParams, live) = adapter(editorSession)
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.5f))

		live.preview(angleX, 15f)

		assertTrue(editorSession.pendingChannelEdits.value.isEmpty(), "retired on the way, not on release")
		assertEquals(15f, liveParams.values[angleX], "and the preview still reached the render hand-off")
	}

	/**
	 * A preview that does not move the pose leaves them alone.
	 *
	 * The parameter panel republishes the current value on every frame of a gesture that has not moved yet,
	 * and a typed-then-not-yet-keyed value must survive that - it is only invalidated by actually going
	 * somewhere else.
	 */
	@Test
	fun aPreviewAtTheSameValueLeavesPendingEditsAlone() {
		val editorSession = session()
		val (_, live) = adapter(editorSession)
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.5f))

		live.preview(angleX, 0f)

		assertEquals(ChannelValue.Scalar(0.5f), editorSession.pendingChannelEdits.value[target])
	}
}
