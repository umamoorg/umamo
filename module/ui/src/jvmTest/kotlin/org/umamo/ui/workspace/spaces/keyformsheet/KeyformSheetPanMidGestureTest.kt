package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.tracks.TrackWindow
import org.umamo.ui.workspace.spaces.parameters.GESTURE_STEP_MILLIS
import org.umamo.ui.workspace.spaces.parameters.PANEL_BODY_TAG
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.assertNear
import org.umamo.ui.workspace.spaces.parameters.moveOn
import org.umamo.ui.workspace.spaces.parameters.pressAndMove
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals

/**
 * A sheet gesture carries on across a middle-drag pan, as it does across a zoom: the pan takes the pointer
 * only while the middle button is down, and the scrub or the key drag it interrupted then goes on in the
 * panned window and commits at the release as one step.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetPanMidGestureTest {
	/**
	 * Zooms the sheet in about its middle, so its window has room to pan: Body X shows -6 to 6.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 */
	private fun ComposeUiTest.zoomIn(harness: ParametersPanelHarness) {
		runOnIdle { harness.sheetViewState.window = TrackWindow.Full.zoomedBy(0.6f, 0.5f) }
		waitForIdle()
	}

	/**
	 * Presses the middle button where the pointer is and drags it 60 pixels left, with the primary button
	 * still held.
	 */
	private fun ComposeUiTest.pressMiddleAndPan() {
		onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
			advanceEventTime(GESTURE_STEP_MILLIS)
			press(MouseButton.Tertiary)
			repeat(3) {
				advanceEventTime(GESTURE_STEP_MILLIS)
				moveBy(Offset(-20f, 0f))
			}
		}
		waitForIdle()
	}

	/** Lets the middle button go. */
	private fun ComposeUiTest.releaseMiddle() {
		onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
			advanceEventTime(GESTURE_STEP_MILLIS)
			release(MouseButton.Tertiary)
		}
		waitForIdle()
	}

	/** A key drag goes on after the pan and lands where it ends in the panned window, as one step. */
	@Test
	fun aKeyDragCarriesOnAfterAPan() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			zoomIn(harness)
			val zoomed = harness.sheetViewState.window
			val cursorBefore = harness.historyCursor

			pressAndMove(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f), listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 4f)))
			pressMiddleAndPan()
			releaseMiddle()
			val panned = harness.sheetViewState.window
			moveOn(listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 3f)))
			releasePress()

			assertNotEquals(zoomed, panned, "the middle drag panned the window")
			assertEquals(panned, harness.sheetViewState.window, "and nothing panned it after the middle button came up")
			assertKeysNear(listOf(-5f, 0f, 3f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the key landed where the drag ended")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** A scrub goes on after the pan and commits where it ends in the panned window. */
	@Test
	fun aScrubCarriesOnAfterAPan() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			zoomIn(harness)

			pressAndMove(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f), listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2f)))
			pressMiddleAndPan()
			releaseMiddle()
			moveOn(listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 1f)))
			releasePress()

			assertNear(1f, harness.committed(PanelIds.bodyX), "the scrub committed where it ended")
		}

	/**
	 * Letting the primary button go while the middle one still pans ends the key drag there and then, with
	 * the key where the hand held it; the pan moved the content with the hand, so that is still 4.
	 */
	@Test
	fun releasingThePrimaryMidPanCommitsTheDrag() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			zoomIn(harness)
			val cursorBefore = harness.historyCursor

			pressAndMove(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f), listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 4f)))
			pressMiddleAndPan()
			releasePress()

			assertKeysNear(listOf(-5f, 0f, 4f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "the drag committed at the primary release")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			releaseMiddle()

			assertEquals(cursorBefore + 1, harness.historyCursor, "the middle release commits nothing more")
		}
}