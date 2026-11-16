package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.TrackKeyRef
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.assertNear
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.moveOn
import org.umamo.ui.workspace.spaces.parameters.pressAndMove
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sheet gesture carries on when the window zooms under it, as Ctrl+wheel does mid-drag: the scrub or the key
 * drag keeps going in the zoomed window and commits at the release as one step, as it would have unzoomed.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetZoomMidGestureTest {
	private val geometryKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 0)
	private val opacityKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.OPACITY, 0)

	/**
	 * Presses at [from] and moves through [before], zooms the window in with the button still held, moves to
	 * where the zoomed window draws [after] on the geometry row, and releases.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Offset from Where the press lands.
	 * @param List before The points the pointer moves through before the zoom.
	 * @param Float after The value the pointer ends on, through the zoomed window.
	 */
	private fun ComposeUiTest.zoomMidGesture(harness: ParametersPanelHarness, from: Offset, before: List<Offset>, after: Float) {
		pressAndMove(from, before)
		runOnIdle { harness.sheetViewState.window = harness.sheetViewState.window.zoomedBy(0.8f, 0.5f) }
		waitForIdle()
		moveOn(listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, after)))
		releasePress()
	}

	/** A scrub carries on across the zoom and commits where it ends, as one step. */
	@Test
	fun aScrubCarriesOnAcrossAZoom() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			val cursorBefore = harness.historyCursor

			zoomMidGesture(
				harness,
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f)),
				after = 3f,
			)

			assertNear(3f, harness.committed(PanelIds.bodyX), "the scrub committed where it ended")
			assertTrue(harness.session.keySelection.value.isEmpty(), "and its press dropped the selection")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A key drag carries on across the zoom and lands where it ends, as one step. */
	@Test
	fun aKeyDragCarriesOnAcrossAZoom() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val cursorBefore = harness.historyCursor

			zoomMidGesture(
				harness,
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 6f)),
				after = 7f,
			)

			assertKeysNear(listOf(-5f, 0f, 7f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the key landed after the zoom")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A group drag carries on across the zoom, and its preview ends with the release. */
	@Test
	fun aGroupDragCarriesOnAcrossAZoom() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			shiftClickAt(lanePoint(harness, SheetRows.OPACITY, PanelIds.bodyX, 0f))
			val cursorBefore = harness.historyCursor

			zoomMidGesture(
				harness,
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -4f)),
				after = -3f,
			)

			assertKeysNear(listOf(-3f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the key under the hand moved")
			assertKeysNear(listOf(2f, 5f, 8f), opacityKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "and the other selected key with it")
			assertEquals(setOf(geometryKey0, opacityKey0), harness.session.keySelection.value)
			assertNull(harness.sheetViewState.dragPreviewFraction, "the release ends the preview")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}
}