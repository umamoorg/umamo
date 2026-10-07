package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.OperatorParameter
import org.umamo.edit.PROPORTIONAL_RADIUS_STEP_FACTOR
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.UvMirrorRequest
import org.umamo.edit.UvSnapKind
import org.umamo.edit.UvSnapRequest
import org.umamo.edit.floatValue
import org.umamo.edit.transform.TransformParameterKeys
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.edit.transform.beginCircleSelect
import org.umamo.edit.transform.beginUvOperator
import org.umamo.edit.withParameter
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
import kotlin.test.assertTrue

/**
 * Pins what the UV editor's Edit-mode gizmo overlay does with the pointer and the session's requests,
 * through the overlay itself: a modal transform previews through the render sync and commits one step
 * through the frame it was authored in, a gesture belongs to the area it started in, the proportional
 * radius belongs to the surface a gesture began on, a gesture that loses its surface is cancelled rather
 * than left latched and one latched over nothing is dropped, the idle pointer selects, and the requests
 * answer once, from the asking area, even over a surface with nothing of the edit on it.
 */
@OptIn(ExperimentalTestApi::class)
class UvEditGizmoOverlayGestureTest {
	/** The quad's stored coordinates after vertex 0 moved ten page texels right. */
	private val quadUvsMoved = listOf(110f / 256) + UV_RIG_QUAD_UVS.drop(1)

	/** A box around vertices 0 and 1, started on empty canvas. */
	private val boxFrom = Offset(130f, 170f)
	private val boxTo = Offset(250f, 200f)

	/**
	 * Latches a Grab in the left area with the pointer at [UV_RIG_GESTURE_START] and moves it ten texels right.
	 *
	 * @param EditorSession session The session.
	 */
	private fun ComposeUiTest.grabTenTexelsRight(session: EditorSession) {
		moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
		session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		waitForIdle()
		moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
	}

	/**
	 * Latches a Grab in the left area and cancels it with a right-click, which seeds the shown surface's
	 * radius and changes nothing else.
	 *
	 * @param EditorSession session The session.
	 */
	private fun ComposeUiTest.seedRadiusInLeftArea(session: EditorSession) {
		moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
		session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		waitForIdle()
		clickIn(LEFT_AREA, UV_RIG_GESTURE_START, MouseButton.Secondary)
		assertNull(session.activeUvOperator.value, "the seeding Grab was cancelled")
	}

	/** A Grab moved and clicked commits ONE step, registers the strip for its area, and resyncs once. */
	@Test
	fun aClickConfirmsTheGrabAsOneStep() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			val stepsBefore = session.historyView.value.steps.size

			grabTenTexelsRight(session)
			assertTrue(fixture.renderSync.previewed.isNotEmpty(), "the move previewed through the render sync")
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "a preview commits nothing")
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			assertNull(session.activeUvOperator.value, "the confirm cleared the operator")
			assertEquals(quadUvsMoved, uvRigUvsOf(session, UV_RIG_QUAD), "vertex 0 moved ten texels, every other coordinate bit-identical")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId, "the strip shows in the gesture's area")
			assertEquals(1, fixture.renderSync.resyncs, "exactly one resync")
		}

	/** Enter confirms through the session's request, the same commit as a click. */
	@Test
	fun enterConfirmsTheGrab() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			grabTenTexelsRight(session)

			session.requestMeshConfirm()
			waitForIdle()

			assertNull(session.activeUvOperator.value)
			assertEquals(quadUvsMoved, uvRigUvsOf(session, UV_RIG_QUAD))
		}

	/** A right-click cancels: nothing commits, and the renderer goes back to the committed model. */
	@Test
	fun aRightClickCancelsTheGrab() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			val stepsBefore = session.historyView.value.steps.size
			grabTenTexelsRight(session)

			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT, MouseButton.Secondary)

			assertNull(session.activeUvOperator.value)
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "nothing committed")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertEquals(1, fixture.renderSync.resyncs, "the renderer is back on the committed model")
		}

	/** A gesture latched in the left area leaves the right one inert: moves there drive nothing. */
	@Test
	fun theOtherAreaStaysInert() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()

			moveIn(RIGHT_AREA, listOf(UV_RIG_GESTURE_START, UV_RIG_TEN_TEXELS_RIGHT))
			clickIn(RIGHT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			assertTrue(fixture.renderSync.previewed.isEmpty(), "the right area drove no preview")
			assertEquals(MeshOperatorKind.Grab, session.activeUvOperator.value?.kind, "and did not confirm the left area's gesture")
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD))
		}

	/** The first latch seeds the shown surface's radius from its size, even with proportional editing off. */
	@Test
	fun theFirstLatchSeedsTheRadius() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			assertNull(session.proportionalEdit.value)
			assertNull(fixture.radiusOf(LEFT_AREA))

			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()

			assertEquals(UV_RIG_PAGE_SIDE / 8f, fixture.radiusOf(LEFT_AREA), "an eighth of the page")
		}

	/** The wheel resizes the texel radius mid-gesture and re-drives the preview without a move. */
	@Test
	fun theWheelResizesTheRadiusAndReDrives() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			grabTenTexelsRight(session)
			val previewsBefore = fixture.renderSync.previewed.size

			scrollIn(LEFT_AREA, -1f)

			assertEquals(UV_RIG_PAGE_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(fixture.radiusOf(LEFT_AREA)), 1e-4f, "wheel-up grows the texel radius one step")
			assertEquals(10f, assertNotNull(session.proportionalEdit.value).radiusWorld, "the session's world radius is not this editor's")
			assertEquals(previewsBefore + 1, fixture.renderSync.previewed.size, "the scroll alone re-drove the preview")
		}

	/** A Grab over the source layer commits through the layer's frame onto the stored coordinates. */
	@Test
	fun aGrabOverTheLayerCommitsThroughItsFrame() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()

			grabTenTexelsRight(session)
			session.requestMeshConfirm()
			waitForIdle()

			val committed = uvRigUvsOf(session, UV_RIG_QUAD)
			assertEquals(110f / 256, committed[0], 1e-6f, "ten layer texels are ten page texels at the rig's unscaled placement")
			assertEquals(UV_RIG_QUAD_UVS[1], committed[1], 1e-6f)
			assertEquals(UV_RIG_QUAD_UVS.drop(2), committed.drop(2), "every other coordinate bit-identical")
		}

	/** A surface switch mid-gesture still commits through the frame the gesture was captured in. */
	@Test
	fun aSurfaceHopMidGestureCommitsThroughTheFrozenFrame() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			grabTenTexelsRight(session)

			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(239f, 150f), UV_RIG_TEN_TEXELS_RIGHT))
			session.requestMeshConfirm()
			waitForIdle()

			assertEquals(quadUvsMoved, uvRigUvsOf(session, UV_RIG_QUAD), "the page frame converted the page coordinates")
		}

	/** An idle drag from empty canvas boxes the vertices it encloses. */
	@Test
	fun anIdleDragBoxSelects() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session

			dragIn(LEFT_AREA, boxFrom, listOf(Offset(170f, 180f), boxTo))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(0), MeshElement.Vertex(1)), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
		}

	/** An idle click on a vertex selects it. */
	@Test
	fun anIdleClickSelectsTheVertex() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session

			clickIn(LEFT_AREA, uvRigScreenOf(120f, 120f))

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2)), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
		}

	/** The armed Circle tool paints what the brush passes over, and publishes no mesh preview for the 2D renderer. */
	@Test
	fun aCircleStrokeSelectsWithoutAMeshPreview() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()

			pressIn(LEFT_AREA, uvRigScreenOf(120f, 120f))
			moveIn(LEFT_AREA, listOf(uvRigScreenOf(100f, 120f)))
			val stroke = assertNotNull(fixture.circleStrokeByArea.getValue(LEFT_AREA).value, "the area's stroke state holds the live stroke")
			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2), MeshElement.Vertex(3)), stroke.elementsOf(UV_RIG_QUAD), "as painted so far")
			assertNull(fixture.circleStrokeByArea.getValue(RIGHT_AREA).value, "the other area's holds nothing")
			assertNull(session.meshPreviewSelection.value, "and the session's preview stays clear: the stroke is drawn over this area alone")
			releaseIn(LEFT_AREA)

			assertEquals(setOf<MeshElement>(MeshElement.Vertex(2), MeshElement.Vertex(3)), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
			assertNull(fixture.circleStrokeByArea.getValue(LEFT_AREA).value, "the release commits the stroke and clears the state")
		}

	/** Shift+RightClick places the UV cursor through the shown surface's frame, in stored coordinates. */
	@Test
	fun aShiftRightClickPlacesTheCursorThroughTheFrame() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session
			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, UV_RIG_GESTURE_START, MouseButton.Secondary) }

			val cursor = assertNotNull(session.uvCursor.value)
			assertEquals(110f / 256, cursor.u, 1e-6f, "the quad's center on the layer is its center on the page")
			assertEquals(1f - 110f / 256, cursor.v, 1e-6f)
		}

	/** Leaving Edit mode mid-gesture ends it with the overlay gone: nothing commits and the renderer resyncs. */
	@Test
	fun leavingEditModeMidGestureRestoresTheRenderer() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			grabTenTexelsRight(session)

			session.setMode(EditorMode.Object)
			waitForIdle()

			assertNull(session.activeUvOperator.value)
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "nothing committed")
			assertEquals(1, fixture.renderSync.resyncs, "the renderer is back on the committed model")
		}

	/**
	 * When the shown surface stops holding any of the edit's meshes mid-gesture, the gesture cannot go on:
	 * it is cancelled and the renderer resynced, rather than left latched to an overlay that is gone.
	 */
	@Test
	fun emptyingTheSurfaceMidGestureCancelsIt() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			grabTenTexelsRight(session)

			fixture.show(LEFT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()

			assertNull(session.activeUvOperator.value, "the gesture was cancelled")
			assertEquals(1, fixture.renderSync.resyncs, "the renderer is back on the committed model")
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD))
		}

	/** Losing the frame camera mid-gesture cancels it the same way. */
	@Test
	fun losingTheCameraMidGestureCancelsIt() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			grabTenTexelsRight(session)

			fixture.show(LEFT_AREA, UvRigSurface(cameraLost = true))
			waitForIdle()

			assertNull(session.activeUvOperator.value, "the gesture was cancelled")
			assertEquals(1, fixture.renderSync.resyncs)
		}

	/**
	 * Pins the restart the cancel prevents: a gesture that lived through a stretch with nothing shown would
	 * be captured again from a fresh gesture state whose pointer is (0, 0), so its next move would land far
	 * away.  Confirming right after the meshes show again must not move anything.
	 */
	@Test
	fun aGestureDoesNotRestartFromTheOrigin() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			fixture.show(LEFT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()
			fixture.show(LEFT_AREA, UvRigSurface())
			waitForIdle()

			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			session.requestMeshConfirm()
			waitForIdle()

			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "vertex 0 did not jump")
		}

	/** Another area emptying is no reason to drop this area's gesture: its latch survives and still confirms. */
	@Test
	fun anotherAreasGestureSurvivesThisAreaEmptying() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			grabTenTexelsRight(session)

			fixture.show(RIGHT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()
			assertEquals(LEFT_AREA, session.activeUvOperator.value?.areaId, "the left area's gesture is still latched")
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			assertEquals(quadUvsMoved, uvRigUvsOf(session, UV_RIG_QUAD))
		}

	/** Arming Zoom Region mid-box abandons the box, which does not come back when Zoom Region disarms. */
	@Test
	fun armingZoomRegionMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))

			session.armZoomRegion(LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(260f, 210f)))
			releaseIn(LEFT_AREA)
			assertEquals(false, session.viewportGestureActive.value, "the box went with the arming")
			session.disarmZoomRegion()
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(300f, 220f)))

			assertEquals(emptySet(), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
			assertEquals(false, session.viewportGestureActive.value)
		}

	/** Unmounting mid-box drops the box and lowers the gesture flag it raised. */
	@Test
	fun unmountingMidBoxLowersTheGestureFlag() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))
			assertEquals(true, session.viewportGestureActive.value)

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()
			releaseIn(LEFT_AREA)

			assertEquals(false, session.viewportGestureActive.value)
			assertEquals(emptySet(), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
		}

	/**
	 * Asserts one request over the empty left area answers with the notice, once, and changes nothing.
	 *
	 * @param UvGizmoOverlayFixture fixture The mounted fixture, the left area showing nothing of the edit.
	 * @param Function request Sends the request.
	 */
	private fun ComposeUiTest.assertAnswersWithTheNotice(fixture: UvGizmoOverlayFixture, request: () -> Unit) {
		val session = fixture.session
		val serialBefore = session.notice.value?.serial ?: 0L
		val selectionBefore = session.meshSelection.value
		val cursorBefore = session.uvCursor.value

		request()
		waitForIdle()

		val notice = assertNotNull(session.notice.value)
		assertEquals("notice.uv.noEditableGeometry", notice.messageKey)
		assertEquals(serialBefore + 1, notice.serial, "one notice, not one per area")
		assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "nothing moved")
		assertEquals(selectionBefore, session.meshSelection.value, "nothing was selected")
		assertEquals(cursorBefore, session.uvCursor.value, "the cursor stayed")
	}

	/** Mirror, Select Linked, and Snap over a surface with none of the edit's meshes answer with the notice. */
	@Test
	fun requestsOverAnEmptySurfaceAnswerWithTheNotice() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			fixture.show(LEFT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()
			val session = fixture.session

			assertAnswersWithTheNotice(fixture) { session.requestUvMirror(UvMirrorRequest(mirrorU = true, areaId = LEFT_AREA)) }
			assertAnswersWithTheNotice(fixture) { session.requestSelectLinked(fromSelection = true, areaId = LEFT_AREA) }
			assertAnswersWithTheNotice(fixture) { session.requestUvSnap(UvSnapRequest(UvSnapKind.CursorToSelected, LEFT_AREA)) }
		}

	/**
	 * A Grab latched over a surface with none of the edit's meshes is dropped with the notice: nothing there
	 * could begin it, and left latched it would hold the area's pan and zoom off and begin from a fresh
	 * gesture state at (0, 0) once the meshes showed again.  Confirming after they show moves nothing.
	 */
	@Test
	fun aGrabOverAnEmptySurfaceIsDroppedWithTheNotice() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			fixture.show(LEFT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()
			moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))

			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()
			assertNull(session.activeUvOperator.value, "the latch was dropped")
			assertEquals("notice.uv.noEditableGeometry", session.notice.value?.messageKey)

			fixture.show(LEFT_AREA, UvRigSurface())
			waitForIdle()
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			session.requestMeshConfirm()
			waitForIdle()
			assertEquals(UV_RIG_QUAD_UVS, uvRigUvsOf(session, UV_RIG_QUAD), "nothing began, so nothing moved")
		}

	/** A box armed over a surface with nothing to select is dropped the same way, and so is one armed as the surface empties. */
	@Test
	fun aBoxOverAnEmptySurfaceIsDropped() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			fixture.show(LEFT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()

			session.beginBoxSelect(LEFT_AREA)
			waitForIdle()
			assertNull(session.activeSelectTool.value, "the box armed over the empty surface was dropped")
			assertEquals("notice.uv.noEditableGeometry", session.notice.value?.messageKey)

			fixture.show(LEFT_AREA, UvRigSurface())
			waitForIdle()
			session.beginBoxSelect(LEFT_AREA)
			waitForIdle()
			assertEquals(LEFT_AREA, session.activeSelectTool.value?.areaId, "a box armed over the meshes stays")
			fixture.show(LEFT_AREA, UvRigSurface(shown = emptyList()))
			waitForIdle()
			assertNull(session.activeSelectTool.value, "and goes once the surface empties")
		}

	/** Select Linked under the pointer floods the island the host's pointer is over. */
	@Test
	fun selectLinkedFollowsTheHostsPointer() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession())
			val session = fixture.session
			moveIn(LEFT_AREA, listOf(uvRigScreenOf(100f, 100f)))

			session.requestSelectLinked(fromSelection = false, areaId = LEFT_AREA)
			waitForIdle()

			assertEquals((0 until 4).map { vertexIndex -> MeshElement.Vertex(vertexIndex) }.toSet<MeshElement>(), session.meshSelection.value.elementsOf(UV_RIG_QUAD))
		}

	/** After a surface switch, the wheel resizes the radius of the surface now shown. */
	@Test
	fun theWheelWritesTheShownSurfacesRadiusAfterASwitch() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			seedRadiusInLeftArea(session)
			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()

			grabTenTexelsRight(session)
			assertEquals(UV_RIG_LAYER_SIDE / 8f, fixture.radiusOf(LEFT_AREA), "the layer seeds its own radius")
			scrollIn(LEFT_AREA, -1f)

			assertEquals(UV_RIG_LAYER_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(fixture.radiusOf(LEFT_AREA)), 1e-4f)
		}

	/**
	 * A surface switch mid-gesture leaves the wheel on the radius the gesture began with, whose texels its
	 * capture is still measured in; the radius of the surface now shown is not touched.
	 */
	@Test
	fun aSwitchMidGestureKeepsTheWheelOnTheGesturesRadius() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			grabTenTexelsRight(session)
			val pageRadius = fixture.radiusStateByArea.getValue(LEFT_AREA)

			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()
			assertEquals(LEFT_AREA, session.activeUvOperator.value?.areaId, "the gesture goes on over the layer")
			scrollIn(LEFT_AREA, -1f)

			assertEquals(UV_RIG_PAGE_SIDE / 8f * PROPORTIONAL_RADIUS_STEP_FACTOR, assertNotNull(pageRadius.value), 1e-4f, "the page's radius grew")
			assertNull(fixture.radiusOf(LEFT_AREA), "the layer's radius was not even seeded")
		}

	/** After a surface switch, the strip's Proportional Size row holds the radius of the surface the gesture ran on. */
	@Test
	fun theStripRowHoldsTheShownSurfacesRadius() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			seedRadiusInLeftArea(session)
			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()

			grabTenTexelsRight(session)
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			val record = assertNotNull(session.adjustableOperation.value)
			assertEquals(UV_RIG_LAYER_SIDE / 8f, record.parameters.floatValue(TransformParameterKeys.PROPORTIONAL_SIZE, -1f), 1e-4f)
		}

	/** An adjustment of the Proportional Size row lands where the host's badge reads the radius. */
	@Test
	fun anAdjustmentWritesTheRadiusWhereTheBadgeReadsIt() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			seedRadiusInLeftArea(session)
			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()
			grabTenTexelsRight(session)
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT)
			val record = assertNotNull(session.adjustableOperation.value)
			val sizeRow = record.parameters.first { parameter -> parameter.key == TransformParameterKeys.PROPORTIONAL_SIZE } as OperatorParameter.FloatParameter

			session.adjustLastOperation(record.parameters.withParameter(TransformParameterKeys.PROPORTIONAL_SIZE, sizeRow.copy(value = 12f)))
			waitForIdle()

			assertEquals(12f, assertNotNull(fixture.radiusOf(LEFT_AREA)), 1e-4f)
		}

	/** Each surface keeps its own radius for the area's life: going back to one brings its radius back. */
	@Test
	fun goingBackToASurfaceRestoresItsRadius() =
		runComposeUiTest {
			val fixture = mountUvGizmoOverlays(uvEditSession(elements = listOf(MeshElement.Vertex(0))))
			val session = fixture.session
			session.setProportionalEdit(ProportionalEditState(ProportionalFalloff.Linear, 10f))
			grabTenTexelsRight(session)
			scrollIn(LEFT_AREA, -1f)
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT, MouseButton.Secondary)
			val pageRadius = assertNotNull(fixture.radiusOf(LEFT_AREA))

			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()
			fixture.show(LEFT_AREA, UvRigSurface())
			waitForIdle()

			assertEquals(pageRadius, fixture.radiusOf(LEFT_AREA))
		}
}