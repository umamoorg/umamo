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
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A lane gesture carries on when the axis changes under it, as a zoom or pan does: the press is not dropped,
 * the moves after the change map through the new axis and its walls, and the release reports as usual.
 */
@OptIn(ExperimentalTestApi::class)
class TrackLaneAxisChangeTest {
	private val fullAxis = TrackAxis(-30f, 30f)
	private val zoomedAxis = TrackAxis(-20f, 20f)

	/** What the sheet reported, and the axis it is drawn over, which a test changes mid-gesture. */
	private class Record {
		var axis: TrackAxis by mutableStateOf(TrackAxis(-30f, 30f))
		val scrubEnds = mutableListOf<Float>()
		val markClicks = mutableListOf<Float>()
		val markDragEnds = mutableListOf<Pair<Float, Float>>()
	}

	/**
	 * Mounts the fixture rows over [record]'s axis, the ends wired to [record].
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
					onTrackScrubEnd = { _, value -> record.scrubEnds += value },
					onMarkDragEnd = { _, mark, value -> record.markDragEnds += mark.position to value },
				)
			}
		}
	}

	/**
	 * The point on the Opacity row (marks at -30, 0, and 30) where [axis] draws [value].
	 *
	 * @param TrackAxis axis The axis the lane is drawn over.
	 * @param Float value The domain value.
	 * @return Offset The point in sheet pixels.
	 */
	private fun opacityAt(axis: TrackAxis, value: Float): Offset = Offset(laneXOf(axis, value), rowCenterY(1))

	/**
	 * Presses at [from] and moves through [before], changes the axis to [zoomedAxis] with the button still held,
	 * moves through [after], and releases.
	 *
	 * @param Record record The record whose axis changes.
	 * @param Offset from Where the press lands.
	 * @param List before The points the pointer moves through before the change.
	 * @param List after The points it moves through after it.
	 */
	private fun ComposeUiTest.changeAxisMidGesture(record: Record, from: Offset, before: List<Offset>, after: List<Offset>) {
		onNodeWithTag(SHEET_TAG).performMouseInput {
			moveTo(from)
			press()

			for (point in before) {
				moveTo(point)
			}
		}
		runOnIdle { record.axis = zoomedAxis }
		waitForIdle()
		onNodeWithTag(SHEET_TAG).performMouseInput {
			for (point in after) {
				moveTo(point)
			}

			release()
		}
		waitForIdle()
	}

	/** A scrub carries on across the change and releases where the new axis puts the pointer. */
	@Test
	fun aScrubCarriesOnAcrossAnAxisChange() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			changeAxisMidGesture(
				record,
				opacityAt(fullAxis, -15f),
				listOf(opacityAt(fullAxis, -10f)),
				listOf(opacityAt(zoomedAxis, 12f)),
			)

			val released = record.scrubEnds.single()
			assertTrue(abs(released - 12f) < 0.2f, "the release maps through the new axis, at $released")
		}

	/** A mark drag carries on across the change and releases where the new axis puts the pointer. */
	@Test
	fun aMarkDragCarriesOnAcrossAnAxisChange() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			changeAxisMidGesture(
				record,
				opacityAt(fullAxis, 0f),
				listOf(opacityAt(fullAxis, 4f), opacityAt(fullAxis, 8f)),
				listOf(opacityAt(zoomedAxis, 15f)),
			)

			val (dragged, released) = record.markDragEnds.single()
			assertEquals(0f, dragged, "the mark pressed is the one dragged")
			assertTrue(abs(released - 15f) < 0.2f, "the release maps through the new axis, at $released")
			assertTrue(record.markClicks.isEmpty(), "a drag is no click")
		}

	/** After the change, the drag stops at the new axis's wall rather than the old one. */
	@Test
	fun aDragIsHeldToTheNewWalls() =
		runComposeUiTest {
			val record = Record()
			mount(record)

			changeAxisMidGesture(
				record,
				opacityAt(fullAxis, 0f),
				listOf(opacityAt(fullAxis, 4f)),
				listOf(Offset(SHEET_WIDTH.value + 200f, rowCenterY(1))),
			)

			assertEquals(20f, record.markDragEnds.single().second, "the zoomed window ends at 20, not 30")
		}
}