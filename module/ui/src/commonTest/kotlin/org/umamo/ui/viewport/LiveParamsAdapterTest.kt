package org.umamo.ui.viewport

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
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
 * What a live parameter preview does to a pending unkeyed channel edit, and what the seam refuses while
 * Edit mode pins the pose.
 *
 * A pending value is chosen FOR one pose, and the session already discards them when a pose COMMIT moves.
 * The preview path does not go through commit, so without its own discard a scrub would carry the pending
 * value across every pose it passes through: a half-typed opacity would show as a flat override over the
 * whole range being scrubbed, snapping back to the track only on release.
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

	private val angleY = ParameterId("ParamAngleY")
	private val meshId = DrawableId("mesh")
	private val objectModePose = mapOf(angleX to 12f, angleY to -7f)

	/**
	 * A session in Edit mode over two posed parameters, with the hand-off holding what the viewport
	 * binding gives it there: the rest pose, which names no parameter.
	 *
	 * @return Triple The session, the hand-off, and the seam over both.
	 */
	private fun pinned(): Triple<EditorSession, LiveParams, LiveParamsAdapter> {
		val editorSession =
			EditorSession(
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
								id = meshId,
								name = "mesh",
								parentDeformerId = null,
								blendMode = BlendMode.Normal,
								maskedBy = emptyList(),
								mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), FloatArray(6), intArrayOf(0, 1, 2)),
								geometryGrid = null,
							),
						),
					rootChildren = emptyList(),
					rootPartId = null,
				),
				objectModePose,
			)
		val selected = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(selected), selected))
		editorSession.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, editorSession.mode.value, "the fixture must really be in Edit mode")
		val liveParams = LiveParams(emptyMap())
		return Triple(editorSession, liveParams, LiveParamsAdapter(liveParams, editorSession))
	}

	/** A preview is refused while the pose is pinned: the renderer keeps the rest pose it was handed. */
	@Test
	fun aPreviewIsRefusedWhileThePoseIsPinned() {
		val (editorSession, liveParams, live) = pinned()
		editorSession.setPendingChannelEdit(target, ChannelValue.Scalar(0.5f))

		live.preview(angleX, 15f)

		assertTrue(liveParams.values.isEmpty(), "Edit mode shows the rig at rest, and a preview must not pose it")
		assertEquals(ChannelValue.Scalar(0.5f), editorSession.pendingChannelEdits.value[target], "a refused preview moved nothing, so it retires nothing")
	}

	/** A commit is refused too, and records nothing. */
	@Test
	fun aCommitIsRefusedWhileThePoseIsPinned() {
		val (editorSession, _, live) = pinned()
		val cursorBefore = editorSession.historyView.value.cursor

		live.commit(setOf(angleX))

		assertEquals(objectModePose, editorSession.pose.value)
		assertEquals(cursorBefore, editorSession.historyView.value.cursor)
	}

	/**
	 * A whole scrub through the seam in Edit mode leaves the rig's pose as Object mode left it.  The
	 * hand-off holds the rest pose there, so a commit of what it holds would replace every value the rig
	 * had with the one parameter the scrub touched.
	 */
	@Test
	fun aPinnedScrubLeavesEveryParameterWhereItWas() {
		val (editorSession, liveParams, live) = pinned()

		live.preview(angleX, 15f)
		live.preview(angleX, 20f)
		live.commit(setOf(angleX))

		assertEquals(objectModePose, editorSession.pose.value)
		assertTrue(liveParams.values.isEmpty())
	}
}