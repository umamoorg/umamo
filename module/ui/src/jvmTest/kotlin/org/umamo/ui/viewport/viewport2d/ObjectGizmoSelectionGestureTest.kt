package org.umamo.ui.viewport.viewport2d

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.CIRCLE_RADIUS_STEP_PX
import org.umamo.edit.DEFAULT_CIRCLE_RADIUS_PX
import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SnapKind
import org.umamo.edit.structure.toggleSelectable
import org.umamo.edit.transform.beginBoxSelect
import org.umamo.edit.transform.beginCircleSelect
import org.umamo.render.pick.PickCandidate
import org.umamo.runtime.model.DrawableId
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins what the Object-mode gizmo overlay does with the pointer when no transform runs: the click pick
 * and its modifiers, the Alt overlap stack, the un-armed box, the armed Box (B) and Circle (C) tools with
 * the circle's tint preview, Escape, the other area staying inert, the Shift+S snaps, and a drag while Zoom
 * Region is armed.  The stub answers a click pick from the area's stack, whatever the point, and the region
 * tests from the centroids each case places: the quad's at screen (200, 150), the other's at (346.7, 136.7).
 */
@OptIn(ExperimentalTestApi::class)
class ObjectGizmoSelectionGestureTest {
	/** The quad's centroid on screen. */
	private val quadCentroid = rigScreenOf(10f, -10f)

	/** The other drawable's centroid on screen. */
	private val otherCentroid = rigScreenOf(140f / 3f, -20f / 3f)

	/** A box around the quad's centroid only. */
	private val aroundQuad = Offset(180f, 130f) to Offset(220f, 170f)

	/** A box around the other drawable's centroid only. */
	private val aroundOther = Offset(330f, 120f) to Offset(365f, 155f)

	/** A box around neither. */
	private val aroundNothing = Offset(20f, 20f) to Offset(60f, 60f)

	/**
	 * Places both drawables' centroids where the region selections look for them.
	 *
	 * @param GizmoOverlayFixture fixture The mounted fixture.
	 */
	private fun placeCentroids(fixture: GizmoOverlayFixture) {
		fixture.service.centroids[RIG_QUAD] = floatArrayOf(10f, -10f)
		fixture.service.centroids[RIG_OTHER] = floatArrayOf(140f / 3f, -20f / 3f)
	}

	/**
	 * The drawables the session's object selection holds.
	 *
	 * @param EditorSession session The session.
	 * @return Set<DrawableId> The selected drawables.
	 */
	private fun selectedIn(session: EditorSession): Set<DrawableId> =
		session.selection.value.targets.mapNotNull { target -> (target as? SelectionTarget.Drawable)?.id }.toSet()

	/**
	 * The active drawable, or null.
	 *
	 * @param EditorSession session The session.
	 * @return DrawableId? The active drawable.
	 */
	private fun activeIn(session: EditorSession): DrawableId? = (session.selection.value.active as? SelectionTarget.Drawable)?.id

	/** A plain click replaces the selection with the drawable under the pointer. */
	@Test
	fun aClickPicksTheDrawable() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_QUAD, 0f, 0f))

			clickIn(LEFT_AREA, quadCentroid)

			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session))
			assertEquals(RIG_QUAD, activeIn(fixture.session))
		}

	/** Shift and Ctrl toggle membership: a second modified click on a selected drawable takes it out. */
	@Test
	fun aModifiedClickTogglesMembership() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_OTHER, 0f, 0f))

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, otherCentroid) }
			assertEquals(setOf(RIG_QUAD, RIG_OTHER), selectedIn(fixture.session), "Shift adds")
			assertEquals(RIG_OTHER, activeIn(fixture.session))

			withKeyHeld(LEFT_AREA, Key.CtrlLeft) { clickIn(LEFT_AREA, otherCentroid) }
			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session), "Ctrl takes it out again")
		}

	/** A plain click on empty canvas clears; a modified one keeps the selection. */
	@Test
	fun anEmptyClickClearsUnlessModified() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, Offset(40f, 40f)) }
			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session), "a modified empty click keeps it")

			clickIn(LEFT_AREA, Offset(40f, 40f))
			assertEquals(emptySet(), selectedIn(fixture.session), "a plain one clears")
		}

	/** A drawable that cannot be selected is clicked through. */
	@Test
	fun anUnselectableDrawableIsClickedThrough() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			fixture.session.toggleSelectable(SelectionTarget.Drawable(RIG_QUAD))
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_QUAD, 0f, 0f))

			clickIn(LEFT_AREA, quadCentroid)

			assertEquals(emptySet(), selectedIn(fixture.session))
		}

	/** An Alt click over a stack asks for the picker; over exactly one selectable drawable it selects it. */
	@Test
	fun anAltClickResolvesTheStack() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			val stack = listOf(PickCandidate(RIG_OTHER, 0f, 0f), PickCandidate(RIG_QUAD, 1f, 0f))
			fixture.service.stackByArea[LEFT_AREA] = stack

			withKeyHeld(LEFT_AREA, Key.AltLeft) { clickIn(LEFT_AREA, quadCentroid) }
			assertEquals(listOf(LEFT_AREA to stack), fixture.overlapRequests.map { request -> request.first to request.third })
			assertEquals(emptySet(), selectedIn(fixture.session), "the picker decides, not the click")

			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_OTHER, 0f, 0f))
			withKeyHeld(LEFT_AREA, Key.AltLeft) { clickIn(LEFT_AREA, otherCentroid) }
			assertEquals(setOf(RIG_OTHER), selectedIn(fixture.session))
		}

	/**
	 * An idle drag boxes the drawables whose centroid it encloses.  The gesture flag is up while the box
	 * is in flight, and the centroids are read once, at the press.
	 */
	@Test
	fun anIdleDragBoxSelects() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)

			pressIn(LEFT_AREA, aroundQuad.first)
			moveIn(LEFT_AREA, listOf(Offset(200f, 150f), aroundQuad.second))
			assertTrue(fixture.session.viewportGestureActive.value, "the flag is up mid-drag")
			assertEquals(1, fixture.service.centroidSnapshots, "one snapshot, at the press")
			// Moving the quad away after the press changes nothing: the gesture keeps what it took.
			fixture.service.centroids[RIG_QUAD] = floatArrayOf(500f, 500f)
			releaseIn(LEFT_AREA)

			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session))
			assertFalse(fixture.session.viewportGestureActive.value, "and down after")
		}

	/** Shift adds a box to the selection; an empty plain box clears it. */
	@Test
	fun aBoxAddsWithShiftAndClearsWhenEmpty() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			placeCentroids(fixture)

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { dragIn(LEFT_AREA, aroundOther.first, listOf(aroundOther.second)) }
			assertEquals(setOf(RIG_QUAD, RIG_OTHER), selectedIn(fixture.session))
			assertEquals(RIG_OTHER, activeIn(fixture.session))

			dragIn(LEFT_AREA, aroundNothing.first, listOf(aroundNothing.second))
			assertEquals(emptySet(), selectedIn(fixture.session))
		}

	/** The armed Box tool (B) boxes and then disarms, taking one snapshot. */
	@Test
	fun theArmedBoxSelectsThenDisarms() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)
			fixture.session.beginBoxSelect(LEFT_AREA)
			waitForIdle()

			pressIn(LEFT_AREA, aroundQuad.first)
			moveIn(LEFT_AREA, listOf(aroundQuad.second))
			assertTrue(fixture.session.viewportGestureActive.value, "an armed box raises the flag like any select drag")
			releaseIn(LEFT_AREA)

			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session))
			assertNull(fixture.session.activeSelectTool.value, "one-shot")
			assertFalse(fixture.session.viewportGestureActive.value)
			assertEquals(1, fixture.service.centroidSnapshots)
		}

	/** A sub-threshold click with B armed disarms it without picking what is under the pointer. */
	@Test
	fun anArmedClickDisarmsWithoutPicking() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			fixture.service.stackByArea[LEFT_AREA] = listOf(PickCandidate(RIG_QUAD, 0f, 0f))
			fixture.session.beginBoxSelect(LEFT_AREA)
			waitForIdle()

			clickIn(LEFT_AREA, quadCentroid)

			assertNull(fixture.session.activeSelectTool.value)
			assertEquals(emptySet(), selectedIn(fixture.session))
		}

	/** A right-click disarms B; so does Shift+RightClick, which does not place the cursor while B is armed. */
	@Test
	fun aRightClickDisarmsTheBox() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			fixture.session.beginBoxSelect(LEFT_AREA)
			waitForIdle()
			clickIn(LEFT_AREA, quadCentroid, MouseButton.Secondary)
			assertNull(fixture.session.activeSelectTool.value)

			fixture.session.beginBoxSelect(LEFT_AREA)
			waitForIdle()
			withKeyHeld(LEFT_AREA, Key.ShiftLeft) { clickIn(LEFT_AREA, quadCentroid, MouseButton.Secondary) }
			assertNull(fixture.session.activeSelectTool.value)
			assertNull(fixture.session.cursor2d.value, "the cursor stays where it was")
		}

	/** A right-click during an armed box drag disarms, and the primary release then applies nothing. */
	@Test
	fun aRightClickMidArmedBoxAppliesNothing() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)
			fixture.session.beginBoxSelect(LEFT_AREA)
			waitForIdle()

			pressIn(LEFT_AREA, aroundQuad.first)
			moveIn(LEFT_AREA, listOf(aroundQuad.second))
			pressIn(LEFT_AREA, aroundQuad.second, MouseButton.Secondary)
			releaseIn(LEFT_AREA, MouseButton.Secondary)
			releaseIn(LEFT_AREA)

			assertNull(fixture.session.activeSelectTool.value)
			assertEquals(emptySet(), selectedIn(fixture.session))
			assertFalse(fixture.session.viewportGestureActive.value)
		}

	/**
	 * The Circle tool (C) previews the stroke through the tint while it paints and commits it once on
	 * release, clearing the preview.
	 */
	@Test
	fun aCircleStrokePreviewsThenCommits() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			val session = fixture.session
			placeCentroids(fixture)
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			val stepsBefore = session.historyView.value.steps.size

			pressIn(LEFT_AREA, quadCentroid)
			assertEquals(setOf(RIG_QUAD), session.previewSelection.value, "the stroke tints what it painted")
			assertEquals(emptySet(), selectedIn(session), "and commits nothing yet")
			moveIn(LEFT_AREA, listOf(otherCentroid))
			assertEquals(setOf(RIG_QUAD, RIG_OTHER), session.previewSelection.value)
			releaseIn(LEFT_AREA)

			assertEquals(setOf(RIG_QUAD, RIG_OTHER), selectedIn(session))
			assertEquals(stepsBefore + 1, session.historyView.value.steps.size, "one step")
			assertNull(session.previewSelection.value)
			assertIs<ActiveSelectTool.Circle>(session.activeSelectTool.value, "the tool stays armed")
		}

	/** Shift and the middle button erase. */
	@Test
	fun aCircleStrokeErases() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(listOf(RIG_QUAD, RIG_OTHER)))
			placeCentroids(fixture)
			fixture.session.beginCircleSelect(LEFT_AREA)
			waitForIdle()

			withKeyHeld(LEFT_AREA, Key.ShiftLeft) {
				pressIn(LEFT_AREA, otherCentroid)
				releaseIn(LEFT_AREA)
			}
			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session), "Shift erases")

			pressIn(LEFT_AREA, quadCentroid, MouseButton.Tertiary)
			releaseIn(LEFT_AREA, MouseButton.Tertiary)
			assertEquals(emptySet(), selectedIn(fixture.session), "so does the middle button")
		}

	/** A right-click leaves the Circle tool keeping what the held stroke painted; the wheel resizes the brush. */
	@Test
	fun theCircleToolKeepsItsPaintAndResizes() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)
			fixture.session.beginCircleSelect(LEFT_AREA)
			waitForIdle()

			moveIn(LEFT_AREA, listOf(Offset(40f, 40f)))
			scrollIn(LEFT_AREA, -1f)
			val circle = assertIs<ActiveSelectTool.Circle>(fixture.session.activeSelectTool.value)
			assertEquals(DEFAULT_CIRCLE_RADIUS_PX + CIRCLE_RADIUS_STEP_PX, circle.radiusPx, "wheel-up grows the brush")

			pressIn(LEFT_AREA, quadCentroid)
			pressIn(LEFT_AREA, quadCentroid, MouseButton.Secondary)
			releaseIn(LEFT_AREA, MouseButton.Secondary)
			releaseIn(LEFT_AREA)

			assertNull(fixture.session.activeSelectTool.value, "the right-click left the tool")
			assertEquals(setOf(RIG_QUAD), selectedIn(fixture.session), "keeping the paint")
			assertNull(fixture.session.previewSelection.value)
		}

	/** Escape abandons an idle box (nothing applies, the flag drops) and commits a circle stroke's paint. */
	@Test
	fun escapeResolvesTheSelectGesture() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			val session = fixture.session
			placeCentroids(fixture)

			pressIn(LEFT_AREA, aroundQuad.first)
			moveIn(LEFT_AREA, listOf(aroundQuad.second))
			session.requestMeshGestureCancel()
			waitForIdle()
			assertFalse(session.viewportGestureActive.value)
			releaseIn(LEFT_AREA)
			assertEquals(emptySet(), selectedIn(session), "the abandoned box applied nothing")

			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, quadCentroid)
			session.requestMeshGestureCancel()
			waitForIdle()
			assertEquals(setOf(RIG_QUAD), selectedIn(session), "the stroke kept its paint")
			assertNull(session.previewSelection.value)
			releaseIn(LEFT_AREA)
		}

	/** While the left area's tool is armed the right area is inert, and its own idle box is dropped. */
	@Test
	fun theOtherAreaStaysInert() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)

			pressIn(RIGHT_AREA, aroundQuad.first)
			moveIn(RIGHT_AREA, listOf(aroundQuad.second))
			fixture.session.beginBoxSelect(LEFT_AREA)
			waitForIdle()
			moveIn(RIGHT_AREA, listOf(Offset(221f, 171f)))
			assertFalse(fixture.session.viewportGestureActive.value, "the right area's box was dropped")
			releaseIn(RIGHT_AREA)

			assertEquals(emptySet(), selectedIn(fixture.session))
			assertIs<ActiveSelectTool.BoxArmed>(fixture.session.activeSelectTool.value, "and the left area's tool is untouched")
		}

	/** A snap runs in the area it names: Cursor to Selected lands on the selected drawable's centroid. */
	@Test
	fun aSnapRunsInItsOwnArea() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session

			session.requestSnap(SnapKind.CursorToSelected, "elsewhere")
			session.requestSnap(SnapKind.CursorToSelected, null)
			waitForIdle()
			assertNull(session.cursor2d.value, "no area of this fixture answered")

			session.requestSnap(SnapKind.CursorToSelected, LEFT_AREA)
			waitForIdle()
			val cursor = assertNotNull(session.cursor2d.value)
			assertEquals(10f, cursor.worldX)
			assertEquals(-10f, cursor.worldZ)
		}

	/** With Zoom Region armed, a drag zooms and selects nothing. */
	@Test
	fun aZoomRegionDragSelectsNothing() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)
			fixture.session.armZoomRegion(LEFT_AREA)
			waitForIdle()

			dragIn(LEFT_AREA, aroundQuad.first, listOf(aroundQuad.second))

			assertEquals(listOf(LEFT_AREA), fixture.service.zoomRegionRequests.map { request -> request.first })
			assertEquals(emptySet(), selectedIn(fixture.session))
			assertFalse(fixture.session.viewportGestureActive.value)
		}

	/**
	 * An area closing mid-box drops the box with it: the gesture flag it raised would otherwise stay up for
	 * the whole session, freezing navigation in every area.
	 */
	@Test
	fun unmountingMidBoxDropsTheGestureFlag() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)
			pressIn(LEFT_AREA, aroundQuad.first)
			moveIn(LEFT_AREA, listOf(aroundQuad.second))
			assertTrue(fixture.session.viewportGestureActive.value)

			fixture.mountedAreas.value = setOf(RIGHT_AREA)
			waitForIdle()

			assertFalse(fixture.session.viewportGestureActive.value)
			releaseIn(LEFT_AREA)
			assertEquals(emptySet(), selectedIn(fixture.session), "and nothing applied")
		}

	/** Leaving Object mode mid-stroke drops the stroke uncommitted and takes its tint preview down. */
	@Test
	fun leavingObjectModeMidStrokeDropsTheStroke() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			placeCentroids(fixture)
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, otherCentroid)
			assertEquals(setOf(RIG_QUAD, RIG_OTHER), session.previewSelection.value)

			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertEquals(EditorMode.Edit, session.mode.value)
			assertNull(session.previewSelection.value, "the tint is down")
			assertEquals(setOf(RIG_QUAD), selectedIn(session), "and the stroke was not committed")
			releaseIn(LEFT_AREA)
		}

	/** An area closing mid-stroke takes its own preview down; another area closing leaves it alone. */
	@Test
	fun unmountingMidStrokeClearsOnlyItsOwnPreview() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			val session = fixture.session
			placeCentroids(fixture)
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, quadCentroid)

			fixture.mountedAreas.value = setOf(LEFT_AREA)
			waitForIdle()
			assertEquals(setOf(RIG_QUAD), session.previewSelection.value, "the right area closing leaves the left's stroke")

			fixture.mountedAreas.value = emptySet()
			waitForIdle()
			assertNull(session.previewSelection.value, "the left area closing takes it down")
			releaseIn(LEFT_AREA)
			assertEquals(emptySet(), selectedIn(session))
		}

	/** Leaving Object mode mid-box abandons the box: it never lands on the selection the new mode starts from. */
	@Test
	fun leavingObjectModeMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			placeCentroids(fixture)
			pressIn(LEFT_AREA, aroundOther.first)
			moveIn(LEFT_AREA, listOf(aroundOther.second))

			session.setMode(EditorMode.Edit)
			waitForIdle()

			assertEquals(setOf(RIG_QUAD), selectedIn(session), "the box around the other drawable did not land")
			assertFalse(session.viewportGestureActive.value)
			releaseIn(LEFT_AREA)
		}

	/** Arming Zoom Region mid-box abandons the box: nothing lands. */
	@Test
	fun armingZoomRegionMidBoxAbandonsIt() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			placeCentroids(fixture)
			pressIn(LEFT_AREA, aroundQuad.first)
			moveIn(LEFT_AREA, listOf(aroundQuad.second))

			fixture.session.armZoomRegion(LEFT_AREA)
			waitForIdle()
			moveIn(LEFT_AREA, listOf(Offset(221f, 171f)))
			releaseIn(LEFT_AREA)

			assertEquals(emptySet(), selectedIn(fixture.session))
			assertFalse(fixture.session.viewportGestureActive.value)
		}

	/** A second area opening mid-stroke leaves the stroking area's tint up. */
	@Test
	fun aSplitOpeningMidStrokeKeepsTheTint() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession(emptyList()))
			val session = fixture.session
			placeCentroids(fixture)
			fixture.mountedAreas.value = setOf(LEFT_AREA)
			waitForIdle()
			session.beginCircleSelect(LEFT_AREA)
			waitForIdle()
			pressIn(LEFT_AREA, quadCentroid)

			fixture.mountedAreas.value = setOf(LEFT_AREA, RIGHT_AREA)
			waitForIdle()

			assertEquals(setOf(RIG_QUAD), session.previewSelection.value)
			releaseIn(LEFT_AREA)
			assertEquals(setOf(RIG_QUAD), selectedIn(session))
		}

	/** Undo mid-box is ignored until release; the box then lands as its own step. */
	@Test
	fun undoMidBoxWaitsForTheRelease() =
		runComposeUiTest {
			val fixture = mountGizmoOverlays(gizmoObjectSession())
			val session = fixture.session
			placeCentroids(fixture)
			val historyBefore = session.historyView.value

			pressIn(LEFT_AREA, aroundOther.first)
			moveIn(LEFT_AREA, listOf(aroundOther.second))
			session.undo()
			waitForIdle()
			assertEquals(historyBefore, session.historyView.value, "the undo was ignored")
			releaseIn(LEFT_AREA)

			assertEquals(setOf(RIG_OTHER), selectedIn(session))
			assertEquals(historyBefore.steps.size + 1, session.historyView.value.steps.size)
		}
}