package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lane gesture torn down before its release, here by the axis changing under it as a zoom does, is reported as
 * cancelled in place of its end, so the owner can take back what the press and the moves previewed.  A press
 * that never showed anything reports nothing, and a gesture that is released reports no cancel.
 */
@OptIn(ExperimentalTestApi::class)
class TrackLaneCancelTest {
	private val fullAxis = TrackAxis(-30f, 30f)

	/** What the sheet reported, and the axis it is drawn over, which a test changes mid-gesture. */
	private class Record {
		var axis: TrackAxis by mutableStateOf(TrackAxis(-30f, 30f))
		val scrubEnds = mutableListOf<String>()
		val scrubCancels = mutableListOf<String>()
		val markClicks = mutableListOf<Float>()
		val markDragEnds = mutableListOf<Float>()
		val markDragCancels = mutableListOf<Float>()
	}

	/**
	 * Mounts the fixture rows over [record]'s axis, every end and cancel wired to [record].
	 *
	 * @param Record record The record.
	 */
	private fun ComposeUiTest.mount(record: Record) {
		setContent {
			Box(modifier = Modifier.size(width = SHEET_WIDTH, height = SHEET_HEIGHT).testTag(SHEET_TAG)) {
				TrackSheet(
					rows = trackSheetFixtureRows(),
					axis = record.axis,
					playhead = null,
					modifier = Modifier.fillMaxSize(),
					expandedKeys = setOf(FixtureRows.OWNER),
					onMarkClick = { _, mark, _ -> record.markClicks += mark.position },
					onTrackScrub = { _, _, _ -> },
					onTrackScrubEnd = { row, _ -> record.scrubEnds += row.key },
					onMarkDragEnd = { _, mark, _ -> record.markDragEnds += mark.position },
					onTrackScrubCancel = { row -> record.scrubCancels += row.key },
					onMarkDragCancel = { _, mark -> record.markDragCancels += mark.position },
				)
			}
		}
	}

	/**
	 * The point on the Opacity row (marks at -30, 0, and 30) where the full axis draws [value].
	 *
	 * @param Float value The domain value.
	 * @return Offset The point in sheet pixels.
	 */
	private fun opacityAt(value: Float): Offset = Offset(laneXOf(fullAxis, value), rowCenterY(1))

	/**
	 * Presses at [from] and moves through [through], then zooms the axis in with the button still held, then
	 * releases.
	 *
	 * @param Record record The record whose axis is zoomed.
	 * @param Offset from Where the press lands.
	 * @param List through The points the pointer moves through before the zoom.
	 */
	private fun ComposeUiTest.zoomMidGesture(record: Record, from: Offset, through: List<Offset>) {
		onNodeWithTag(SHEET_TAG).performMouseInput {
			moveTo(from)
			press()

			for (point in through) {
				moveTo(point)
			}
		}
		runOnIdle { record.axis = TrackAxis(-20f, 20f) }
		waitForIdle()
		onNodeWithTag(SHEET_TAG).performMouseInput { release() }
		waitForIdle()
	}

	/** A scrub the zoom tears down reports a cancel and no end, and the next scrub is reported as usual. */
	@Test
	fun aTornDownScrubReportsACancel() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			zoomMidGesture(record, opacityAt(-15f), listOf(opacityAt(-10f), opacityAt(-5f)))

			assertEquals(listOf(FixtureRows.OPACITY), record.scrubCancels)
			assertTrue(record.scrubEnds.isEmpty(), "a torn-down scrub never ends")

			onNodeWithTag(SHEET_TAG).performMouseInput {
				moveTo(opacityAt(-15f))
				press()
				release()
			}
			waitForIdle()
			assertEquals(listOf(FixtureRows.OPACITY), record.scrubEnds, "the restarted lane takes the next press")
		}

	/** A mark drag the zoom tears down reports a cancel for the dragged mark and no end. */
	@Test
	fun aTornDownMarkDragReportsACancel() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			zoomMidGesture(record, opacityAt(0f), listOf(opacityAt(4f), opacityAt(8f)))

			assertEquals(listOf(0f), record.markDragCancels)
			assertTrue(record.markDragEnds.isEmpty() && record.markClicks.isEmpty(), "and neither an end nor a click")
		}

	/** A press on a mark that never moved showed nothing, so tearing it down reports nothing at all. */
	@Test
	fun aTornDownPressOnAMarkReportsNothing() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			zoomMidGesture(record, opacityAt(0f), emptyList())

			assertTrue(record.markDragCancels.isEmpty() && record.scrubCancels.isEmpty(), "nothing to take back")
			assertTrue(record.markClicks.isEmpty(), "and no click either: the gesture never ended")
		}

	/** A drag and a scrub that are released report their ends and no cancel. */
	@Test
	fun aReleasedGestureReportsNoCancel() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			onNodeWithTag(SHEET_TAG).performMouseInput {
				moveTo(opacityAt(0f))
				press()
				moveTo(opacityAt(4f))
				moveTo(opacityAt(8f))
				release()
				moveTo(opacityAt(-15f))
				press()
				moveTo(opacityAt(-10f))
				release()
			}
			waitForIdle()

			assertEquals(listOf(0f), record.markDragEnds)
			assertEquals(listOf(FixtureRows.OPACITY), record.scrubEnds)
			assertTrue(record.markDragCancels.isEmpty() && record.scrubCancels.isEmpty())
		}
}