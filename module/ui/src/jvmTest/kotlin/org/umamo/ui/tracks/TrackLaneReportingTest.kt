package org.umamo.ui.tracks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * What a lane reports to its owner beyond clicks and scrubs: its bounds, and the live values of a mark
 * drag before the drag ends.
 */
@OptIn(ExperimentalTestApi::class)
class TrackLaneReportingTest {
	private val axis = TrackAxis(-30f, 30f)

	/** Every shown row reports its lane's bounds, in window coordinates, laid out from the sheet's constants. */
	@Test
	fun laneBoundsAreReportedPerRow() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis)
			val owner = assertNotNull(harness.laneBounds[FixtureRows.OWNER])
			val opacity = assertNotNull(harness.laneBounds[FixtureRows.OPACITY])
			val angle = assertNotNull(harness.laneBounds[FixtureRows.ANGLE])
			assertEquals(rowTop(0), owner.top)
			assertEquals(rowTop(1), opacity.top)
			assertEquals(rowTop(2), angle.top)
			assertEquals(TRACK_ROW_HEIGHT.value, opacity.height)
			assertEquals(LANE_LEFT, opacity.left)
			assertEquals(SHEET_WIDTH.value, opacity.right)
		}

	/** A mark drag reports every move past the slop with its live value, and the end carries the last of them. */
	@Test
	fun aMarkDragReportsEveryMoveBeforeItsEnd() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, axis = axis)
			val startX = laneXOf(axis, 0f)
			val y = rowCenterY(1)
			onNodeWithTag(SHEET_TAG).performMouseInput {
				moveTo(Offset(startX, y))
				press()
				moveTo(Offset(startX + 30f, y))
				moveTo(Offset(startX + 50f, y))
				moveTo(Offset(startX + 70f, y))
				release()
			}
			waitForIdle()
			assertEquals(3, harness.markDrags.size, "one report per move past the slop")
			assertTrue(harness.markDrags.all { (row, mark, _) -> row.key == FixtureRows.OPACITY && mark.keyIndex == 1 })
			val values = harness.markDrags.map { (_, _, value) -> value }
			assertTrue(values.zipWithNext().all { (earlier, later) -> later > earlier }, "the live value follows the pointer right ($values)")
			val end = assertNotNull(harness.markDragEnds.singleOrNull(), "exactly one drag end")
			assertEquals(values.last(), end.third, "the end carries the last live value")
			assertTrue(harness.markClicks.isEmpty(), "a drag is not a click")
		}
}