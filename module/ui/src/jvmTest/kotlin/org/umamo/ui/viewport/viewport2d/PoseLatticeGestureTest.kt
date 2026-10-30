package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.KeyformAim
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.OperatorParameter
import org.umamo.edit.ParameterChange
import org.umamo.edit.ParameterSelection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.keyform.captureKeysOnTracks
import org.umamo.edit.setMeshSelectMode
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.beginMeshOperator
import org.umamo.edit.withParameter
import org.umamo.render.eval.warpLatticeAt
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.KeyformTrackRef
import org.umamo.ui.viewport.LiveParams
import org.umamo.ui.viewport.LiveParamsAdapter
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.moveIn
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins Pose mode on a warp's lattice through the 2D overlay itself, at a pose between the rig's keys: Ctrl+Tab opens
 * the selected warp's points, the select modes pick points, grid lines, and cells, a drag previews through the
 * renderer's lattice preview so the art follows with no model push, and the confirm holds the shape pending as one
 * step the strip amends; keying it stores the shape and clears the mark, a scrub discards it with a notice, and Alt+Q
 * moves the session to another warp.
 *
 * The lattice rig's body warp spans world x 0..40 and z -40..0: points 0 (0, 0), 1 (40, 0), 2 (0, -40), and
 * 3 (40, -40), one cell between them; the head warp sits inside it.
 */
@OptIn(ExperimentalTestApi::class)
class PoseLatticeGestureTest {
	private val body: ElementOwner = ElementOwner.Deformer(LATTICE_RIG_BODY)

	/** Where the pointer rests before a gesture latches: the gesture measures from here. */
	private val gestureStart = LATTICE_RIG_GESTURE_START

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = LATTICE_RIG_TEN_UNITS_RIGHT

	/** The pose the cases shape at: between the rig's two keys. */
	private val pose = mapOf(LATTICE_RIG_PARAMETER to 0.5f)

	/**
	 * The lattice rig posed between its keys with the body warp selected, then Ctrl+Tab, with [points] selected and
	 * [active] the active one.
	 *
	 * @param Set<Int> points The selected points.
	 * @param Int? active The active point, or null.
	 * @return EditorSession The session, in Pose mode.
	 */
	private fun poseSession(
		points: Set<Int> = emptySet(),
		active: Int? = null,
	): EditorSession {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		session.commitPose(ParameterChange.SetValue(listOf(LATTICE_RIG_PARAMETER)), session.pose.value + pose)
		session.setParameterSelection(ParameterSelection(setOf(LATTICE_RIG_PARAMETER), LATTICE_RIG_PARAMETER))
		session.togglePoseMode()
		assertEquals(EditorMode.Pose, session.mode.value)
		assertEquals(listOf(body), session.meshSelection.value.owners, "Ctrl+Tab on the warp poses its lattice")

		session.selectLatticePoints(body, points, active)

		return session
	}

	/**
	 * A warp's lattice in world space as the session's pose draws it with its pending lattices.
	 *
	 * @param EditorSession session The session.
	 * @param DeformerId deformerId The warp.
	 * @return FloatArray The world points.
	 */
	private fun posedWorld(
		session: EditorSession,
		deformerId: DeformerId,
	): FloatArray = assertNotNull(warpLatticeAt(session.model.value, session.pose.value, deformerId, session.pendingLatticeEdits.value)).world

	@Test
	fun theSelectModesPickPointsGridLinesAndCells() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession())
			val session = fixture.session

			clickIn(LEFT_AREA, rigScreenOf(40f, 0f))
			assertEquals(setOf<MeshElement>(MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(body), "a click picks the point under it")

			session.setMeshSelectMode(MeshSelectMode.Edge)
			waitForIdle()
			clickIn(LEFT_AREA, rigScreenOf(20f, 0f))
			assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(body), "2 picks the grid line under the click")

			session.setMeshSelectMode(MeshSelectMode.Face)
			waitForIdle()
			clickIn(LEFT_AREA, rigScreenOf(21f, -21f))
			assertEquals(setOf<MeshElement>(MeshElement.Face(0)), session.meshSelection.value.elementsOf(body), "3 picks the whole cell")
		}

	@Test
	fun aGridLineGrabMovesBothItsPoints() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession())
			val session = fixture.session
			session.setMeshSelectMode(MeshSelectMode.Edge)
			waitForIdle()
			clickIn(LEFT_AREA, rigScreenOf(20f, 0f))
			moveIn(LEFT_AREA, listOf(gestureStart))

			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			clickIn(LEFT_AREA, tenUnitsRight)

			val landed = posedWorld(session, LATTICE_RIG_BODY)
			assertEquals(10f, landed[0], 1e-3f, "the line's first point moved")
			assertEquals(50f, landed[2], 1e-3f, "and its second")
			assertEquals(0f, landed[4], 1e-3f, "the rest held")
		}

	@Test
	fun aDragPreviewsTheArtAndLandsAsOnePendingStep() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession(points = setOf(3), active = 3))
			val session = fixture.session
			val modelBefore = session.model.value
			val headBefore = posedWorld(session, LATTICE_RIG_HEAD)
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size
			val pushesBefore = fixture.service.pushedModels.size

			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			assertEquals(pushesBefore, fixture.service.pushedModels.size, "a posed drag pushes no model")
			assertNotNull(fixture.service.pushedLatticePreviews.lastOrNull()?.get(LATTICE_RIG_BODY), "the art follows through the lattice preview")
			assertNotNull(fixture.livePreview.latticeFormsByDeformer?.get(LATTICE_RIG_BODY), "and the cage through the same forms")

			clickIn(LEFT_AREA, tenUnitsRight)

			assertNull(session.activeMeshOperator.value, "the confirm cleared the operator")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals("change.pose.move", session.historyView.value.steps.last().labelKey)
			assertSame(modelBefore, session.model.value, "the document is untouched")
			val landed = posedWorld(session, LATTICE_RIG_BODY)
			assertEquals(50f, landed[6], 1e-3f, "the point is held where it was dragged")
			assertEquals(-40f, landed[7], 1e-3f)
			assertTrue(posedWorld(session, LATTICE_RIG_HEAD).contentEquals(headBefore).not(), "the warp inside follows, as the art does")
			assertSame(session.pendingLatticeEdits.value, fixture.service.pushedLatticeOverrides.last(), "the renderer took the pending lattice")
			assertTrue(fixture.service.pushedLatticePreviews.last().isEmpty(), "before the preview cleared")

			// The strip's Move X reruns the drag from the step's base and amends the pending lattice.
			val record = assertNotNull(session.adjustableOperation.value)
			val row = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.MOVE_X } as OperatorParameter.FloatParameter
			session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.MOVE_X, row.copy(value = 4f)), adjustedKey = TransformParameterKeys.MOVE_X)
			assertEquals(44f, posedWorld(session, LATTICE_RIG_BODY)[6], 1e-3f, "the rerun holds it four units right instead")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "amending the same step")

			session.undo()
			assertTrue(session.pendingLatticeEdits.value.isEmpty(), "undo takes the shape back")
		}

	@Test
	fun aCancelLeavesNothingPending() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession(points = setOf(3), active = 3))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size

			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)

			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertTrue(session.pendingLatticeEdits.value.isEmpty())
			assertTrue(fixture.service.pushedLatticePreviews.last().isEmpty(), "the preview is gone")
			assertNull(fixture.livePreview.latticeFormsByDeformer)
		}

	@Test
	fun keyingStoresTheShapeAndAScrubDiscardsAnother() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession(points = setOf(3), active = 3))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			clickIn(LEFT_AREA, tenUnitsRight)
			val held = assertNotNull(session.pendingLatticeEdits.value[LATTICE_RIG_BODY])

			session.captureKeysOnTracks(listOf(KeyformTrackRef.Geometry(KeyformOwner.Deformer(LATTICE_RIG_BODY))), null, KeyformAim.Pose)

			assertTrue(session.pendingLatticeEdits.value.isEmpty(), "the key consumed the shape")
			val grid = assertNotNull((session.model.value.deformers.first { deformer -> deformer.id == LATTICE_RIG_BODY } as Deformer.Warp).geometryGrid)
			assertEquals(listOf(-1f, 0.5f, 1f), grid.axes.single().keys.toList())
			assertEquals(50f, posedWorld(session, LATTICE_RIG_BODY)[6], 1e-3f, "the keyed lattice shows the shape at the pose")
			assertEquals(50f, held.controlPoints[6], 1e-3f, "the shape keyed is the one that was held")
			assertEquals(40f, assertNotNull(warpLatticeAt(session.model.value, mapOf(LATTICE_RIG_PARAMETER to 1f), LATTICE_RIG_BODY)).world[6], 1e-3f, "and the other keys are what they were")

			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			clickIn(LEFT_AREA, tenUnitsRight)
			assertEquals(1, session.pendingLatticeEdits.value.size)

			LiveParamsAdapter(LiveParams(session.pose.value), session).preview(LATTICE_RIG_PARAMETER, 0.75f)

			assertTrue(session.pendingLatticeEdits.value.isEmpty(), "a scrub discards it")
			assertEquals("notice.pose.latticeEditsDiscarded", assertNotNull(session.notice.value).messageKey)
		}

	@Test
	fun altQPosesTheWarpUnderThePointer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(rigScreenOf(70f, -10f)))

			fixture.service.latticeStackByArea[LEFT_AREA] = listOf(LATTICE_RIG_GUIDE)
			session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(LATTICE_RIG_GUIDE)), session.meshSelection.value.owners, "a drawn lattice under the pointer")

			fixture.service.latticeStackByArea[LEFT_AREA] = emptyList()
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(LATTICE_RIG_ART, 0f, 0f))
			session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(listOf<ElementOwner>(ElementOwner.Deformer(LATTICE_RIG_GUIDE)), session.meshSelection.value.owners, "art no warp moves switches nothing")
		}

	@Test
	fun theObjectOverlayStaysOutOfPoseMode() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(poseSession())
			val session = fixture.session
			val selectionBefore = session.selection.value

			clickIn(LEFT_AREA, rigScreenOf(-30f, 20f))

			assertEquals(selectionBefore, session.selection.value, "an empty click clears the points, never the object selection")
			assertEquals(listOf(body), session.meshSelection.value.owners)
		}
}