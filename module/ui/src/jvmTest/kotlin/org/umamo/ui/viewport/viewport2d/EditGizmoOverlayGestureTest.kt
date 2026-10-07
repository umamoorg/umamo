package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.MeshSelectMode
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformParameterKeys
import org.umamo.edit.beginBoxSelect
import org.umamo.edit.beginCircleSelect
import org.umamo.edit.beginMeshOperator
import org.umamo.edit.floatValue
import org.umamo.edit.setDrawableParentDeformer
import org.umamo.edit.setMeshSelectMode
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DeformerId
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.dragIn
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.pressIn
import org.umamo.ui.viewport.gizmo.releaseIn
import org.umamo.ui.viewport.gizmo.scrollIn
import org.umamo.ui.viewport.gizmo.withKeyHeld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what the Edit-mode gizmo overlay does with the pointer and the session's requests, through the
 * overlay itself: a modal transform previews into the render service and commits one step when
 * confirmed, a gesture belongs to the area it started in, the idle pointer selects, and the requests
 * that need no geometry still answer when there is none to project.
 */
@OptIn(ExperimentalTestApi::class)
class EditGizmoOverlayGestureTest {
	/** Where the pointer rests before a gesture latches: the gesture measures from here. */
	private val gestureStart = Offset(200f, 150f)

	/** Forty pixels right of [gestureStart]: ten world units at the rig's zoom. */
	private val tenUnitsRight = Offset(240f, 150f)

	/** A Grab moved and clicked commits ONE step, registers the strip for its area, and resyncs once. */
	@Test
	fun aClickConfirmsTheGrabAsOneStep() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size

			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the move previewed through the render service")
			assertEquals(listOf(0f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "a preview commits nothing")
			clickIn(LEFT_AREA, tenUnitsRight)

			assertNull(session.activeMeshOperator.value, "the confirm cleared the operator")
			assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "vertex 0 moved ten units right")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId, "the strip shows in the gesture's area")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the teardown resynced the renderer to the commit")
			assertEquals(1, fixture.service.pushedModels.count { pushed -> pushed === session.model.value }, "exactly one resync")
		}

	/** Enter confirms through the session's request, the same commit as a click. */
	@Test
	fun enterConfirmsTheGrab() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			session.requestMeshConfirm()
			waitForIdle()

			assertNull(session.activeMeshOperator.value)
			assertEquals(10f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	/** A right-click cancels: nothing commits, and the renderer goes back to the committed model. */
	@Test
	fun aRightClickCancelsTheGrab() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			val stepsBefore = session.historyView.value.steps.size
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			clickIn(LEFT_AREA, tenUnitsRight, MouseButton.Secondary)

			assertNull(session.activeMeshOperator.value)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing committed")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the renderer is back on the committed model")
		}

	/** The wheel resizes the proportional radius mid-gesture and re-drives the preview without a move. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			val pushesBefore = fixture.service.pushedModels.size

			scrollIn(LEFT_AREA, -1f)

			assertEquals(10f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(session.proportionalEdit.value).radiusWorld, 1e-4f, "wheel-up grows the radius one step")
			assertEquals(pushesBefore + 1, fixture.service.pushedModels.size, "the scroll alone re-drove the preview")
		}

	/** The wheel leaves the radius alone during a Vertex Slide, which takes no weights. */
	@Test
	fun theWheelLeavesTheRadiusAloneDuringASlide() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			moveIn(LEFT_AREA, listOf(rigScreenOf(0f, 0f)))
			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()

			scrollIn(LEFT_AREA, -1f)

			assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld)
		}

	/**
	 * A Vertex Slide follows the edge whose far end is nearest the pointer: halfway toward vertex 1, the
	 * vertex lands halfway along that edge, and the strip offers its Factor row.
	 */
	@Test
	fun aSlideLandsOnTheNearestEdge() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(rigScreenOf(0f, 0f)))
			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()
			val halfwayTowardVertexOne = Offset(200f, 112f)
			moveIn(LEFT_AREA, listOf(halfwayTowardVertexOne))

			clickIn(LEFT_AREA, halfwayTowardVertexOne)

			assertEquals(listOf(10f, 0f, 20f, 0f, 20f, 20f, 0f, 20f), rigPositionsOf(session, RIG_QUAD), "halfway along the edge to vertex 1")
			val record = assertNotNull(session.adjustableOperation.value)
			assertEquals(0.5f, record.parameters.floatValue(TransformParameterKeys.SLIDE_FACTOR, -1f), 1e-4f, "the Factor row holds where it landed")
		}

	/** A Vertex Slide with no active vertex to slide drops the latch and resyncs once. */
	@Test
	fun aSlideWithNoActiveVertexDrops() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.setMeshSelectMode(MeshSelectMode.Edge)
			moveIn(LEFT_AREA, listOf(gestureStart))
			clickIn(LEFT_AREA, Offset(200f, 110f))
			assertEquals(setOf<MeshElement>(MeshElement.Edge(0, 1)), session.meshSelection.value.elementsOf(RIG_QUAD), "the top edge is selected")

			session.beginMeshOperator(MeshOperatorKind.VertexSlide, LEFT_AREA)
			waitForIdle()

			assertNull(session.activeMeshOperator.value, "an edge is no vertex to slide")
			assertEquals(listOf(session.model.value), fixture.service.pushedModels.toList(), "one resync, and no preview")
		}

	/** A gesture latched in the left area leaves the right one inert: moves there drive nothing. */
	@Test
	fun theOtherAreaStaysInert() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()

			moveIn(RIGHT_AREA, listOf(gestureStart, tenUnitsRight))
			clickIn(RIGHT_AREA, tenUnitsRight)

			assertTrue(fixture.service.pushedModels.isEmpty(), "the right area drove no preview")
			assertEquals(MeshOperatorKind.Grab, session.activeMeshOperator.value?.kind, "and did not confirm the left area's gesture")
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	/** An idle drag from empty canvas boxes the vertices it encloses. */
	@Test
	fun anIdleDragBoxSelects() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session

			dragIn(LEFT_AREA, Offset(140f, 95f), listOf(Offset(170f, 115f), Offset(250f, 125f)))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** An idle click on a vertex selects it. */
	@Test
	fun anIdleClickSelectsTheVertex() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session

			clickIn(LEFT_AREA, rigScreenOf(20f, -20f))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2)), session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** The armed Circle tool paints what the brush passes over, committed on release. */
	@Test
	fun aCircleStrokeSelects() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()

			dragIn(LEFT_AREA, rigScreenOf(20f, -20f), listOf(rigScreenOf(0f, -20f)))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2), MeshElement.Vertex(3)), session.meshSelection.value.elementsOf(RIG_QUAD))
		}

	/** Alt+Q over a single other mesh switches the edit to it; over a stack it asks for the picker. */
	@Test
	fun switchObjectFollowsThePick() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))

			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_OTHER, 0f, 0f), PickCandidate(RIG_QUAD, 1f, 0f))
			session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(LEFT_AREA, fixture.overlapRequests.single().first, "a stack opens the picker in the asking area")
			assertEquals(gestureStart, fixture.overlapRequests.single().second, "anchored at the host's pointer")

			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_OTHER, 0f, 0f))
			session.requestSwitchObjectUnderCursor(LEFT_AREA)
			waitForIdle()
			assertEquals(RIG_OTHER, session.meshSelection.value.activeDrawableId, "one candidate switches directly")
		}

	/**
	 * With every mesh in the edit behind an unprojectable parent there is nothing to draw, but the
	 * requests still answer - with the notice, once, from the asking area alone.
	 */
	@Test
	fun requestsAnswerWithNoGeometry() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(editing = listOf(RIG_HIDDEN)))
			val session = fixture.session
			val serialBefore = session.notice.value?.serial ?: 0L

			session.requestRip(LEFT_AREA)
			waitForIdle()

			val notice = assertNotNull(session.notice.value)
			assertEquals("notice.edit.noEditableGeometry", notice.messageKey)
			assertEquals(serialBefore + 1, notice.serial, "one notice, not one per area")
		}

	/**
	 * Leaving Edit mode mid-gesture ends the gesture with the overlay gone, so nothing is left to tear it
	 * down but the overlay's own disposal: the renderer goes back to the committed model, which the
	 * uncommitted preview never reached.
	 */
	@Test
	fun leavingEditModeMidGestureRestoresTheRenderer() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			assertTrue(fixture.service.pushedModels.isNotEmpty(), "the move previewed")

			session.setMode(EditorMode.Object)
			waitForIdle()

			assertNull(session.activeMeshOperator.value)
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "nothing committed")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the renderer is back on the committed model")
		}

	/**
	 * When every mesh in the edit stops projecting mid-gesture (its deformer went missing), the gesture
	 * cannot go on: it is cancelled and the renderer resynced, so when the mesh projects again nothing
	 * restarts under the pointer.
	 */
	@Test
	fun losingEveryProjectableMeshMidGestureCancelsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(tenUnitsRight))

			session.setDrawableParentDeformer(RIG_QUAD, DeformerId("missing"), localPositions = null)
			waitForIdle()

			assertNull(session.activeMeshOperator.value, "the gesture was cancelled")
			assertSame(session.model.value, fixture.service.pushedModels.last(), "the renderer is back on the committed model")

			session.setDrawableParentDeformer(RIG_QUAD, null, localPositions = null)
			waitForIdle()
			val pushesBefore = fixture.service.pushedModels.size
			moveIn(LEFT_AREA, listOf(tenUnitsRight, Offset(250f, 150f)))

			assertNull(session.activeMeshOperator.value, "no gesture restarted")
			assertEquals(pushesBefore, fixture.service.pushedModels.size, "and nothing drove")
			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0])
		}

	/**
	 * Pins the restart the cancel prevents: a gesture that lived through a stretch with nothing to project
	 * would be captured again from a fresh gesture state whose pointer is (0, 0), so its next move would
	 * land dozens of units away.  Confirming right after the mesh projects again must not move it.
	 */
	@Test
	fun aGestureDoesNotRestartFromTheOrigin() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(gestureStart))
			session.beginMeshOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			session.setDrawableParentDeformer(RIG_QUAD, DeformerId("missing"), localPositions = null)
			waitForIdle()
			session.setDrawableParentDeformer(RIG_QUAD, null, localPositions = null)
			waitForIdle()

			moveIn(LEFT_AREA, listOf(tenUnitsRight))
			session.requestMeshConfirm()
			waitForIdle()

			assertEquals(0f, rigPositionsOf(session, RIG_QUAD)[0], "vertex 0 did not jump")
		}

	/** A right-click disarms Box select (B) in Edit mode, Shift+RightClick too: while B is armed it never places the cursor. */
	@Test
	fun aShiftRightClickDisarmsTheBox() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.beginBoxSelect(LEFT_AREA)
			waitForIdle()

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, gestureStart, MouseButton.Secondary) }

			assertNull(session.activeSelectTool.value, "the tool disarmed")
			assertNull(session.cursor2d.value, "and the cursor stayed where it was")
		}

	/** A box around vertices 0 and 1, started on empty canvas. */
	private val boxFrom = Offset(140f, 95f)
	private val boxTo = Offset(250f, 125f)

	/** A right-click mid-box abandons it: nothing lands. */
	@Test
	fun aRightClickMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))

			pressIn(LEFT_AREA, boxTo, MouseButton.Secondary)
			releaseIn(LEFT_AREA, MouseButton.Secondary)
			releaseIn(LEFT_AREA)

			assertEquals(emptySet(), session.meshSelection.value.elementsOf(RIG_QUAD))
			assertEquals(false, session.viewportGestureActive.value)
		}

	/** Shift+RightClick mid-box abandons it too, and does not place the cursor. */
	@Test
	fun aShiftRightClickMidBoxAbandonsItWithoutTheCursor() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) {
				pressIn(LEFT_AREA, boxTo, MouseButton.Secondary)
				releaseIn(LEFT_AREA, MouseButton.Secondary)
			}
			releaseIn(LEFT_AREA)

			assertEquals(emptySet(), session.meshSelection.value.elementsOf(RIG_QUAD))
			assertNull(session.cursor2d.value)
		}

	/** A box drag and a circle stroke both raise the session's gesture flag while they are held. */
	@Test
	fun aSelectDragRaisesTheGestureFlag() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session

			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))
			assertEquals(true, session.viewportGestureActive.value, "a box drag")
			releaseIn(LEFT_AREA)
			assertEquals(false, session.viewportGestureActive.value)

			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, rigScreenOf(20f, -20f))
			assertEquals(true, session.viewportGestureActive.value, "a circle stroke")
			releaseIn(LEFT_AREA)
			assertEquals(false, session.viewportGestureActive.value)
		}

	/** Leaving Edit mode mid-box abandons the box: it never lands on the mesh selection after the switch. */
	@Test
	fun leavingEditModeMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))

			session.setMode(EditorMode.Object)
			waitForIdle()
			releaseIn(LEFT_AREA)

			assertEquals(emptyMap(), session.meshSelection.value.elementsByDrawable)
			assertTrue(session.historyView.value.steps.none { step -> step.labelKey == "change.mesh.select" }, "no selection step after the switch")
			assertEquals(false, session.viewportGestureActive.value)
		}

	/** Arming Zoom Region mid-box abandons the box, which does not come back when Zoom Region disarms. */
	@Test
	fun armingZoomRegionMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))

			session.armZoomRegion(LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(260f, 130f)))
			releaseIn(LEFT_AREA)
			assertEquals(false, session.viewportGestureActive.value, "the box went with the arming")
			session.disarmZoomRegion()
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(300f, 200f)))

			assertEquals(emptySet(), session.meshSelection.value.elementsOf(RIG_QUAD))
			assertEquals(false, session.viewportGestureActive.value)
		}

	/** Undo mid-stroke is ignored until the stroke is released; the stroke then commits as its own step. */
	@Test
	fun undoMidStrokeWaitsForTheRelease() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			val historyBefore = session.historyView.value

			pressIn(LEFT_AREA, rigScreenOf(20f, -20f))
			session.undo()
			waitForIdle()
			assertEquals(historyBefore, session.historyView.value, "the undo was ignored")
			releaseIn(LEFT_AREA)

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(2)), session.meshSelection.value.elementsOf(RIG_QUAD))
			assertEquals(historyBefore.steps.size + 1, session.historyView.value.steps.size, "the stroke is its own step")
		}

	/**
	 * A circle stroke publishes what it has painted as the session's mesh preview, which the renderer's
	 * overlay shows, while the committed selection waits for the release; the release commits the stroke
	 * and takes the preview down.
	 */
	@Test
	fun theMeshBrushPreviewFollowsTheStroke() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()

			pressIn(LEFT_AREA, rigScreenOf(20f, -20f))
			waitForIdle()
			val preview = assertNotNull(session.meshPreviewSelection.value, "the stroke publishes its preview")
			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2)), preview.elementsOf(RIG_QUAD), "holding what the brush painted")
			assertTrue(session.meshSelection.value.isEmpty, "while nothing is committed yet")

			releaseIn(LEFT_AREA)
			waitForIdle()
			assertNull(session.meshPreviewSelection.value, "the release takes the preview down")
			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2)), session.meshSelection.value.elementsOf(RIG_QUAD), "and commits the stroke")
		}

	/** An area closing mid-stroke takes its own mesh preview down; another area closing leaves it alone. */
	@Test
	fun unmountingMidStrokeTakesOnlyItsOwnMeshPreviewDown() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, rigScreenOf(20f, -20f))
			waitForIdle()

			fixture.mountedAreas.value = setOf(LEFT_AREA)
			waitForIdle()
			assertNotNull(session.meshPreviewSelection.value, "the right area closing leaves the left's stroke")

			fixture.mountedAreas.value = emptySet()
			waitForIdle()
			assertNull(session.meshPreviewSelection.value, "the left area closing takes it down")
			releaseIn(LEFT_AREA)
			assertTrue(session.meshSelection.value.isEmpty, "and the stroke was not committed")
		}

	/** Leaving Edit mode mid-stroke takes the mesh preview down with the overlay. */
	@Test
	fun leavingEditModeMidStrokeTakesTheMeshPreviewDown() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoEditSession())
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, rigScreenOf(20f, -20f))
			waitForIdle()

			session.setMode(EditorMode.Object)
			waitForIdle()

			assertEquals(EditorMode.Object, session.mode.value)
			assertNull(session.meshPreviewSelection.value, "the preview is down")
			releaseIn(LEFT_AREA)
		}
}