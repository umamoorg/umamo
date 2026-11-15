package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.TrackKeyRef
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.pressAndMove
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A sheet gesture torn down before its release, here by the window zooming under it as Ctrl+wheel does, leaves
 * nothing behind: the pose, the keys, the group preview, and the selection are what they were before the
 * press, and no undo step is recorded.
 */
@OptIn(ExperimentalTestApi::class)
class KeyformSheetInterruptTest {
	private val geometryKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.GEOMETRY, 0)
	private val opacityKey0 = TrackKeyRef(PanelIds.bodyX, SheetRows.OPACITY, 0)

	/**
	 * Presses at [from] and moves through [through], then zooms the window in with the button still held, then
	 * releases.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Offset from Where the press lands.
	 * @param List through The points the pointer moves through before the zoom.
	 */
	private fun ComposeUiTest.zoomMidGesture(harness: ParametersPanelHarness, from: Offset, through: List<Offset>) {
		pressAndMove(from, through)
		runOnIdle { harness.sheetViewState.window = harness.sheetViewState.window.zoomedBy(0.8f, 0.5f) }
		waitForIdle()
		releasePress()
	}

	/** A torn-down scrub puts the shown pose back on the recorded one, and the selection its press dropped. */
	@Test
	fun aTornDownScrubPutsThePoseAndSelectionBack() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			val cursorBefore = harness.historyCursor

			zoomMidGesture(
				harness,
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -2.5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 2.5f)),
			)

			assertEquals(-5f, harness.committed(PanelIds.bodyX), "the scrub never committed")
			assertEquals(-5f, harness.live(PanelIds.bodyX), "and the pose on screen is the recorded one again")
			assertEquals(setOf(geometryKey0), harness.session.keySelection.value, "the selection the press dropped is back")
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A torn-down group drag drops its preview, so the selected marks are drawn on their keys again. */
	@Test
	fun aTornDownGroupDragDropsItsPreview() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			clickAt(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f))
			shiftClickAt(lanePoint(harness, SheetRows.OPACITY, PanelIds.bodyX, 0f))
			val cursorBefore = harness.historyCursor

			zoomMidGesture(
				harness,
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -4f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, -3f)),
			)

			assertNull(harness.sheetViewState.dragPreviewFraction)
			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "no key moved")
			assertEquals(setOf(geometryKey0, opacityKey0), harness.session.keySelection.value)
			assertEquals(cursorBefore, harness.historyCursor)
		}

	/** A torn-down drag of an unselected key gives up the selection its start staged. */
	@Test
	fun aTornDownKeyDragGivesUpItsStagedSelection() =
		runComposeUiTest {
			val harness = mountSheet(listOf(PanelIds.bodyX))
			val cursorBefore = harness.historyCursor

			zoomMidGesture(
				harness,
				lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 5f),
				listOf(lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 6.5f), lanePoint(harness, SheetRows.GEOMETRY, PanelIds.bodyX, 7.5f)),
			)

			assertEquals(listOf(-5f, 0f, 5f), geometryKeysOf(harness, PanelIds.drawable, PanelIds.bodyX), "no key moved")
			assertTrue(harness.session.keySelection.value.isEmpty(), "nothing was selected before the drag")
			assertEquals(cursorBefore, harness.historyCursor)
		}
}