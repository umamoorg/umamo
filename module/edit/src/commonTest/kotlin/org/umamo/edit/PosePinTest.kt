package org.umamo.edit

import org.umamo.edit.keyform.captureKeyOnTrack
import org.umamo.edit.keyform.channelValueAt
import org.umamo.edit.keyform.removeKeyOnTrack
import org.umamo.edit.parameter.setParameterRange
import org.umamo.runtime.keyform.axisIndexOf
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.ChannelGrids
import org.umamo.runtime.model.ChannelValue
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.FormChannel
import org.umamo.runtime.model.KeyableTarget
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The pose while Edit mode pins it.
 *
 * Edit mode edits the neutral state of the base mesh and shows the rig at rest, so the pose Object mode
 * left is held until Edit mode is left.  The session is where that is enforced: a caller asking for a pose
 * move is refused whoever it is, and what it hands over is never trusted - in Edit mode the pose a view
 * has on hand is the rest pose it is being shown, not the rig's.
 *
 * An edit aimed at "the pose" acts at the pose that is shown, which in Edit mode is the rest pose: a key
 * inserted there lands where the rigger is looking, not at a pose that will only come back on exit.
 */
class PosePinTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val meshId = DrawableId("mesh")
	private val objectModePose = mapOf(angleX to 12f, angleY to -7f)

	/**
	 * A session over two parameters and one meshed drawable, posed off its defaults.
	 *
	 * @return EditorSession The session, in Object mode.
	 */
	private fun session(): EditorSession =
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

	/**
	 * The same session once it is in Edit mode.
	 *
	 * @return EditorSession The session, which must really have entered Edit mode.
	 */
	private fun pinnedSession(): EditorSession {
		val editorSession = session()
		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, editorSession.mode.value, "the fixture must really be in Edit mode")
		return editorSession
	}

	private fun keyAt(keyIndex: Int): TrackKeyRef = TrackKeyRef(angleX, "drawable:mesh/GEOMETRY", keyIndex)

	private val opacity = KeyableTarget(KeyformOwner.Drawable(meshId), FormChannel.OPACITY)

	/**
	 * A session in Edit mode whose mesh keys its geometry at the two ends of Angle X, and its opacity at
	 * [opacityKeys] on Angle X, from 0.25 at the first key to 1 at the last.
	 *
	 * @param FloatArray opacityKeys The opacity track's key positions on Angle X, ascending.
	 * @return EditorSession The session, which must really have entered Edit mode.
	 */
	private fun pinnedKeyedSession(opacityKeys: FloatArray = floatArrayOf(-30f, 30f)): EditorSession {
		val editorSession = session()
		val geometryTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, floatArrayOf(-30f, 30f))),
				listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6)))),
			)
		val opacityTrack =
			KeyformGrid(
				listOf(KeyformAxis(angleX, opacityKeys)),
				opacityKeys.indices.map { keyIndex ->
					KeyformCell<ChannelValue>(intArrayOf(keyIndex), ChannelValue.Scalar(0.25f + 0.75f * keyIndex / (opacityKeys.size - 1)))
				},
			)
		editorSession.mutate(ParameterChange.SetValue(emptyList())) { model ->
			model.copy(
				drawables =
					model.drawables.map { drawable ->
						drawable.copy(geometryGrid = geometryTrack, channelGrids = ChannelGrids(mapOf(FormChannel.OPACITY to opacityTrack)))
					},
			)
		}
		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)
		assertEquals(EditorMode.Edit, editorSession.mode.value, "the fixture must really be in Edit mode")
		return editorSession
	}

	/**
	 * The key positions on Angle X of the mesh's opacity track.
	 *
	 * @return List<Float> The positions, ascending.
	 */
	private fun EditorSession.opacityKeys(): List<Float> {
		val grid = model.value.drawables.single().channelGrids[FormChannel.OPACITY]!!
		return grid.axes[grid.axisIndexOf(angleX)].keys.toList()
	}

	/**
	 * The key positions on Angle X of the mesh's geometry track.
	 *
	 * @return List<Float> The positions, ascending.
	 */
	private fun EditorSession.geometryKeys(): List<Float> {
		val grid = model.value.drawables.single().geometryGrid!!
		return grid.axes[grid.axisIndexOf(angleX)].keys.toList()
	}

	/** The pose is pinned in Edit mode and nowhere else. */
	@Test
	fun editModeAlonePinsThePose() {
		val editorSession = session()
		assertFalse(editorSession.posePinned)

		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)
		assertTrue(editorSession.posePinned)

		editorSession.setMode(EditorMode.Object)
		assertFalse(editorSession.posePinned)
	}

	/** A pose commit is refused: the pose stays, and nothing is recorded. */
	@Test
	fun aPoseCommitIsRefusedWhilePinned() {
		val editorSession = pinnedSession()
		val cursorBefore = editorSession.historyView.value.cursor

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), objectModePose + (angleX to 25f))

		assertEquals(objectModePose, editorSession.pose.value)
		assertEquals(cursorBefore, editorSession.historyView.value.cursor)
	}

	/**
	 * A commit of the pose a view was showing must not become the rig's pose.  In Edit mode that pose is
	 * the rest pose, which names no parameter at all, so taking it would drop every value the rig held.
	 */
	@Test
	fun theDisplayedRestPoseNeverReplacesThePinnedOne() {
		val editorSession = pinnedSession()

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), mapOf(angleX to 25f))
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), emptyMap())

		assertEquals(objectModePose, editorSession.pose.value)
	}

	/** A click on a key selects it, as one step, and leaves the pose where it is pinned. */
	@Test
	fun aKeyClickSelectsAndLeavesThePinnedPose() {
		val editorSession = pinnedSession()
		val cursorBefore = editorSession.historyView.value.cursor

		editorSession.selectKeysAtPose(setOf(keyAt(0)), mapOf(angleX to 25f))

		assertEquals(setOf(keyAt(0)), editorSession.keySelection.value)
		assertEquals(objectModePose, editorSession.pose.value)
		assertEquals(cursorBefore + 1, editorSession.historyView.value.cursor, "the selection is still a step")

		editorSession.undo()
		assertTrue(editorSession.keySelection.value.isEmpty())
		assertEquals(objectModePose, editorSession.pose.value)
	}

	/** A click on the key already selected records nothing, pinned or not. */
	@Test
	fun aRepeatedKeyClickRecordsNothingWhilePinned() {
		val editorSession = pinnedSession()
		editorSession.selectKeysAtPose(setOf(keyAt(0)), emptyMap())
		val cursorBefore = editorSession.historyView.value.cursor

		editorSession.selectKeysAtPose(setOf(keyAt(0)), emptyMap())

		assertEquals(cursorBefore, editorSession.historyView.value.cursor)
		assertEquals(objectModePose, editorSession.pose.value)
	}

	/** The pose moves again the moment Edit mode is left. */
	@Test
	fun thePoseMovesAgainOnceEditModeIsLeft() {
		val editorSession = pinnedSession()
		editorSession.setMode(EditorMode.Object)

		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), objectModePose + (angleX to 25f))

		assertEquals(25f, editorSession.pose.value[angleX])
		assertEquals(-7f, editorSession.pose.value[angleY])
	}

	/**
	 * A range is the document's, and an edit to it is allowed in Edit mode.  The pose it holds has to stay
	 * inside the range, so that edit still pulls the pinned value in with it.
	 */
	@Test
	fun aRangeEditStillClampsThePinnedPose() {
		val editorSession = pinnedSession()

		editorSession.setParameterRange(angleX, min = -5f, default = 0f, max = 5f)

		assertEquals(5f, editorSession.pose.value[angleX])
		assertEquals(-7f, editorSession.pose.value[angleY])
	}

	/** The pose that is shown is the rig's pose, and while pinned, every parameter at its default. */
	@Test
	fun theShownPoseIsTheRestPoseWhilePinned() {
		assertEquals(objectModePose, session().shownPose)
		assertEquals(mapOf(angleX to 0f, angleY to 0f), pinnedSession().shownPose)
	}

	/** A channel key captured at the pose while pinned lands at the rest pose, holding the value shown there. */
	@Test
	fun aChannelKeyCapturedAtThePoseLandsAtRest() {
		val editorSession = pinnedKeyedSession()

		editorSession.captureKeyOnTrack(KeyformTrackRef.Channel(opacity), angleX, KeyformAim.Pose)

		assertEquals(listOf(-30f, 0f, 30f), editorSession.opacityKeys())
		assertEquals(ChannelValue.Scalar(0.625f), editorSession.model.value.channelValueAt(opacity, mapOf(angleX to 0f)))
		assertEquals(objectModePose, editorSession.pose.value, "keying moves no pose")
	}

	/** A geometry key inserted at the pose while pinned lands at the rest pose. */
	@Test
	fun aGeometryKeyInsertedAtThePoseLandsAtRest() {
		val editorSession = pinnedKeyedSession()

		editorSession.captureKeyOnTrack(KeyformTrackRef.Geometry(KeyformOwner.Drawable(meshId)), angleX, KeyformAim.Pose)

		assertEquals(listOf(-30f, 0f, 30f), editorSession.geometryKeys())
	}

	/** A key removed at the pose while pinned is the one at the rest pose. */
	@Test
	fun aKeyRemovedAtThePoseIsTheOneAtRest() {
		val editorSession = pinnedKeyedSession(opacityKeys = floatArrayOf(-30f, 0f, 12f, 30f))

		editorSession.removeKeyOnTrack(KeyformTrackRef.Channel(opacity), angleX, KeyformAim.Pose)

		assertEquals(listOf(-30f, 12f, 30f), editorSession.opacityKeys(), "the key at the pinned pose's 12 is kept")
	}

	/** Out of Edit mode, "the pose" is the rig's pose as before. */
	@Test
	fun inObjectModeAKeyCapturedAtThePoseLandsAtThePose() {
		val editorSession = pinnedKeyedSession()
		editorSession.setMode(EditorMode.Object)

		editorSession.captureKeyOnTrack(KeyformTrackRef.Channel(opacity), angleX, KeyformAim.Pose)

		assertEquals(listOf(-30f, 12f, 30f), editorSession.opacityKeys())
	}

	/** Undo and redo restore what they recorded, pinned or not. */
	@Test
	fun undoStillRestoresAPoseWhilePinned() {
		val editorSession = session()
		editorSession.commitPose(ParameterChange.SetValue(listOf(angleX)), objectModePose + (angleX to 25f))
		val target = SelectionTarget.Drawable(meshId)
		editorSession.setSelection(Selection(setOf(target), target))
		editorSession.setMode(EditorMode.Edit)

		editorSession.undo()
		editorSession.undo()
		editorSession.undo()

		assertEquals(objectModePose, editorSession.pose.value)
	}
}