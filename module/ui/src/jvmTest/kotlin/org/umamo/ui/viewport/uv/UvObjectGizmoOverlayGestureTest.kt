package org.umamo.ui.viewport.uv

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.SelectionTarget
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.viewport.gizmo.LEFT_AREA
import org.umamo.ui.viewport.gizmo.RIGHT_AREA
import org.umamo.ui.viewport.gizmo.clickIn
import org.umamo.ui.viewport.gizmo.dragIn
import org.umamo.ui.viewport.gizmo.moveIn
import org.umamo.ui.viewport.gizmo.pressIn
import org.umamo.ui.viewport.gizmo.releaseIn
import org.umamo.ui.viewport.gizmo.withKeyHeld
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the UV editor's Object-mode gizmo overlay does with the pointer and the session's requests,
 * through the overlay itself: a placement gesture over the shown page moves the selected tiles as one step
 * with its readout on the host, previews nothing to the renderer, drops with a notice where it cannot run,
 * belongs to the area it started in, and is cancelled rather than left latched when the overlay goes away;
 * the idle pointer picks and boxes whole islands and places the UV cursor.
 */
@OptIn(ExperimentalTestApi::class)
class UvObjectGizmoOverlayGestureTest {
	/** Inside the triangle beside the quad. */
	private val insideOther = Offset(340f, 170f)

	/** Empty canvas, clear of both islands. */
	private val emptyCanvas = Offset(60f, 60f)

	/** A box around the quad, started on empty canvas. */
	private val boxFrom = Offset(130f, 90f)
	private val boxTo = Offset(250f, 200f)

	/**
	 * Mounts the overlays over the placed model in Object mode, showing its page with its art retained.
	 *
	 * @param List<DrawableId> selected The drawables to select, the first active.
	 * @return UvGizmoOverlayFixture The fixture.
	 */
	private fun ComposeUiTest.mountPlaced(selected: List<DrawableId> = listOf(UV_RIG_QUAD)): UvGizmoOverlayFixture =
		mountUvGizmoOverlays(uvObjectSession(selected, uvRigPlacedModel()), uvRigPlacementSurface())

	/**
	 * Latches a Grab in the left area with the pointer at [UV_RIG_GESTURE_START] and waits for its capture to land.
	 *
	 * @param UvGizmoOverlayFixture fixture The mounted fixture.
	 */
	private fun ComposeUiTest.latchPlacementGrab(fixture: UvGizmoOverlayFixture) {
		moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START))
		fixture.session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
		waitForIdle()
		assertEquals(MeshOperatorKind.Grab, fixture.session.activeUvOperator.value?.kind, "the session latched the placement")
		awaitPlacementCapture(fixture, LEFT_AREA, UV_RIG_GESTURE_START)
	}

	/**
	 * The drawables an Object selection holds.
	 *
	 * @param EditorSession session The session.
	 * @return Set<DrawableId> The selected drawables.
	 */
	private fun selectedDrawables(session: EditorSession): Set<DrawableId> =
		session.selection.value.targets.mapNotNullTo(HashSet()) { target -> (target as? SelectionTarget.Drawable)?.id }

	/** A Grab moved and clicked moves the quad's tile ten page pixels as ONE step, its readout cleared after. */
	@Test
	fun aGrabCommitsOneStepAndMovesTheTile() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			val stepsBefore = session.historyView.value.steps.size
			latchPlacementGrab(fixture)

			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			assertEquals(10, assertNotNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value).deltaX, "the host's readout follows the drag")
			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE), "a drag commits nothing")
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			assertNull(session.activeUvOperator.value, "the confirm cleared the operator")
			assertEquals(uvRigTilePlacement(110f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertEquals(uvRigTilePlacement(140f), uvRigPlacementOf(session, UV_RIG_OTHER_TILE), "the bystander stays")
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one undo step")
			assertEquals(LEFT_AREA, assertNotNull(session.adjustableOperation.value).areaId, "the strip shows in the gesture's area")
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value, "the readout cleared")
		}

	/** Enter confirms through the session's request, the same commit as a click. */
	@Test
	fun enterConfirmsThePlacement() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			latchPlacementGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))

			session.requestMeshConfirm()
			waitForIdle()

			assertNull(session.activeUvOperator.value)
			assertEquals(uvRigTilePlacement(110f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value)
		}

	/** A right-click cancels: nothing commits and the readout clears. */
	@Test
	fun aRightClickCancelsThePlacement() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			val stepsBefore = session.historyView.value.steps.size
			latchPlacementGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))

			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT, MouseButton.Secondary)

			assertNull(session.activeUvOperator.value)
			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE), "nothing committed")
			assertEquals(stepsBefore, session.historyView.value.steps.size)
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value)
		}

	/**
	 * A placement drag pushes no model preview and leaves the renderer nothing to resync: what it shows goes
	 * through the area's own scene, which changes on every drive and clears as the gesture ends.
	 */
	@Test
	fun noModelPreviewIsPushed() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val scene = fixture.placementSceneByArea.getValue(LEFT_AREA)
			latchPlacementGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))
			val first = assertNotNull(scene.drag, "a drive writes the area's scene")
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT + Offset(8f, 0f)))
			assertNotSame(first, scene.drag, "and every drive anew")
			assertNull(fixture.placementSceneByArea.getValue(RIGHT_AREA).drag, "the other area's scene is untouched")
			clickIn(LEFT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			assertEquals(uvRigTilePlacement(110f), uvRigPlacementOf(fixture.session, UV_RIG_QUAD_TILE), "the placement did land")
			assertNull(scene.drag, "the end takes the drag down")
			assertTrue(fixture.renderSync.previewed.isEmpty(), "no model preview")
			assertEquals(0, fixture.renderSync.resyncs, "no resync")
		}

	/** Over a source layer there is no page to place on: the latch drops with its notice. */
	@Test
	fun overASourceLayerThePlacementDropsWithTheNotice() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			fixture.show(LEFT_AREA, UV_RIG_LAYER_SURFACE)
			waitForIdle()

			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitForIdle()

			assertNull(session.activeUvOperator.value)
			assertEquals("notice.uv.placement.pageViewOnly", assertNotNull(session.notice.value).messageKey)
		}

	/** A selection with nothing on the shown page drops the latch with its notice once the capture is built. */
	@Test
	fun aSelectionOffThePageDropsWithTheNotice() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			fixture.show(LEFT_AREA, UvRigSurface(shown = listOf(UV_RIG_OTHER)))
			waitForIdle()

			session.beginUvOperator(MeshOperatorKind.Grab, LEFT_AREA)
			waitUntil(timeoutMillis = 5000) { session.activeUvOperator.value == null }

			assertEquals("notice.uv.placement.notOnPage", assertNotNull(session.notice.value).messageKey)
		}

	/** A placement latched in the left area leaves the right one inert. */
	@Test
	fun theOtherAreaStaysInert() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			latchPlacementGrab(fixture)

			moveIn(RIGHT_AREA, listOf(UV_RIG_GESTURE_START, UV_RIG_TEN_TEXELS_RIGHT))
			clickIn(RIGHT_AREA, UV_RIG_TEN_TEXELS_RIGHT)

			assertEquals(LEFT_AREA, session.activeUvOperator.value?.areaId, "the right area did not confirm the left area's gesture")
			assertNull(fixture.placementDragStatusByArea.getValue(RIGHT_AREA).value)
			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
		}

	/**
	 * A select tool armed in any area - this one included, unlike the Edit overlay, which runs the tool armed
	 * in its own area - leaves the Object overlays inert: a click picks nothing.
	 */
	@Test
	fun aToolArmedAnywhereMakesObjectInert() =
		runComposeUiTest {
			val fixture = mountPlaced(selected = emptyList())
			val session = fixture.session
			for (armedIn in listOf(RIGHT_AREA, LEFT_AREA)) {
				session.beginBoxSelect(armedIn)
				waitForIdle()
				assertEquals(armedIn, session.activeSelectTool.value?.areaId, "armed in $armedIn")

				clickIn(LEFT_AREA, UV_RIG_GESTURE_START)

				assertEquals(emptySet(), selectedDrawables(session), "armed in $armedIn")
			}
		}

	/** An idle click picks the island under it; a click on empty canvas clears. */
	@Test
	fun aClickPicksTheIslandAndAnEmptyClickClears() =
		runComposeUiTest {
			val fixture = mountPlaced(selected = emptyList())
			val session = fixture.session

			clickIn(LEFT_AREA, UV_RIG_GESTURE_START)
			assertEquals(setOf(UV_RIG_QUAD), selectedDrawables(session))
			assertEquals(SelectionTarget.Drawable(UV_RIG_QUAD), session.selection.value.active)

			clickIn(LEFT_AREA, emptyCanvas)
			assertEquals(emptySet(), selectedDrawables(session))
		}

	/** Shift+click adds an island to the selection and makes it active. */
	@Test
	fun aShiftClickAddsTheIsland() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, insideOther) }

			assertEquals(setOf(UV_RIG_QUAD, UV_RIG_OTHER), selectedDrawables(session))
			assertEquals(SelectionTarget.Drawable(UV_RIG_OTHER), session.selection.value.active)
		}

	/** An idle drag boxes every island with a vertex inside the box. */
	@Test
	fun aDragBoxesTheEnclosedIslands() =
		runComposeUiTest {
			val fixture = mountPlaced(selected = emptyList())
			val session = fixture.session

			dragIn(LEFT_AREA, boxFrom, listOf(Offset(180f, 150f), boxTo))

			assertEquals(setOf(UV_RIG_QUAD), selectedDrawables(session))
		}

	/** Shift+RightClick places the UV cursor through the shown surface's frame, in stored coordinates. */
	@Test
	fun aShiftRightClickPlacesTheCursor() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, UV_RIG_GESTURE_START, MouseButton.Secondary) }

			val cursor = assertNotNull(session.uvCursor.value)
			assertEquals(110f / 256, cursor.u, 1e-6f)
			assertEquals(1f - 110f / 256, cursor.v, 1e-6f)
		}

	/** Leaving Object mode mid-placement ends it with the overlay gone: nothing commits and the readout clears. */
	@Test
	fun leavingObjectModeMidPlacementClearsTheReadout() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			latchPlacementGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))

			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertNull(session.activeUvOperator.value)
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value)
			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
		}

	/**
	 * A landing's ghost stands in the area's scene while its atlas is the committed one, goes once another
	 * atlas is committed, and goes with the overlay when the mode leaves Object.
	 */
	@Test
	fun leavingObjectModeDropsTheGhost() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			val scene = fixture.placementSceneByArea.getValue(LEFT_AREA)
			scene.ghost = PlacementGhost(session.model.value.atlas, UV_RIG_PAGE_SIDE, emptyList())
			waitForIdle()
			assertNotNull(scene.ghost, "a ghost of the committed atlas stands")

			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertNull(scene.ghost, "leaving Object mode takes it down with the overlay")
		}

	/** A ghost whose atlas is no longer the committed one (an undo, a newer commit) is taken down. */
	@Test
	fun aGhostOfAnUncommittedAtlasIsDismissed() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val scene = fixture.placementSceneByArea.getValue(LEFT_AREA)

			scene.ghost = PlacementGhost(fixture.session.model.value.atlas.copy(), UV_RIG_PAGE_SIDE, emptyList())
			waitForIdle()

			assertNull(scene.ghost, "an equal atlas that is not the committed instance does not hold it")
		}

	/** Unmounting mid-placement cancels it: the latch and the readout go with the overlay. */
	@Test
	fun unmountingMidPlacementCancelsIt() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			latchPlacementGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()

			assertNull(session.activeUvOperator.value, "the gesture was cancelled")
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value)
			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE))
		}

	/**
	 * Losing the frame camera mid-placement cancels it, and the placement does not restart from a fresh
	 * gesture state whose pointer is (0, 0) when the camera comes back.
	 */
	@Test
	fun losingTheCameraMidPlacementCancelsIt() =
		runComposeUiTest {
			val fixture = mountPlaced()
			val session = fixture.session
			latchPlacementGrab(fixture)
			moveIn(LEFT_AREA, listOf(UV_RIG_TEN_TEXELS_RIGHT))

			fixture.show(LEFT_AREA, UvRigSurface(cameraLost = true))
			waitForIdle()
			assertNull(session.activeUvOperator.value, "the gesture was cancelled")
			assertNull(fixture.placementDragStatusByArea.getValue(LEFT_AREA).value)

			fixture.show(LEFT_AREA, UvRigSurface())
			waitForIdle()
			moveIn(LEFT_AREA, listOf(UV_RIG_GESTURE_START, UV_RIG_TEN_TEXELS_RIGHT))
			session.requestMeshConfirm()
			waitForIdle()

			assertEquals(uvRigTilePlacement(100f), uvRigPlacementOf(session, UV_RIG_QUAD_TILE), "nothing restarted or landed")
		}

	/** Arming Zoom Region mid-box abandons the box, which does not come back when Zoom Region disarms. */
	@Test
	fun armingZoomRegionMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountPlaced(selected = emptyList())
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

			assertEquals(emptySet(), selectedDrawables(session))
			assertEquals(false, session.viewportGestureActive.value)
		}

	/** Unmounting mid-box drops the box and lowers the gesture flag it raised. */
	@Test
	fun unmountingMidBoxLowersTheGestureFlag() =
		runComposeUiTest {
			val fixture = mountPlaced(selected = emptyList())
			val session = fixture.session
			pressIn(LEFT_AREA, boxFrom)
			moveIn(LEFT_AREA, listOf(boxTo))
			assertEquals(true, session.viewportGestureActive.value)

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()
			releaseIn(LEFT_AREA)

			assertEquals(false, session.viewportGestureActive.value)
			assertEquals(emptySet(), selectedDrawables(session))
		}
}