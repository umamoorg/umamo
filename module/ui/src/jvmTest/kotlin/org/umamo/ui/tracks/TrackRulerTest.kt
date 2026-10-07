package org.umamo.ui.tracks

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The ruler strip over a mounted sheet: which tick labels it shows, through which formatter, and where it
 * puts them - centered on the inset tick line, and clamped inside the strip at both ends.
 */
@OptIn(ExperimentalTestApi::class)
class TrackRulerTest {
	private val axis = TrackAxis(-30f, 30f)

	/** A -30..30 domain rules at tens, labelled by the default formatter without a trailing decimal. */
	@Test
	fun tickLabelsRenderAtReadableSteps() =
		runComposeUiTest {
			mountTrackSheet(TrackSheetHarness(), axis = axis)
			for (label in listOf("-30", "-20", "-10", "0", "10", "20", "30")) {
				onNodeWithText(label).assertExists("the ruler shows $label")
			}
			onNodeWithText("30.0").assertDoesNotExist()
		}

	/** A custom formatter renders every tick. */
	@Test
	fun aCustomFormatterLabelsTheTicks() =
		runComposeUiTest {
			mountTrackSheet(TrackSheetHarness(), axis = axis, formatTick = { value -> "t${value.toInt()}" })
			onNodeWithText("t-30").assertExists()
			onNodeWithText("t0").assertExists()
			onNodeWithText("t30").assertExists()
			onNodeWithText("30").assertDoesNotExist()
		}

	/**
	 * A label is centered on the x its tick line is drawn at - the inset lane mapping, not the raw fraction.
	 * Checked off-center, where the two mappings differ by a mark radius; at the middle they coincide.
	 */
	@Test
	fun aLabelIsCenteredOnItsTick() =
		runComposeUiTest {
			mountTrackSheet(TrackSheetHarness(), axis = axis)
			for ((label, value) in listOf("-20" to -20f, "20" to 20f)) {
				val bounds = onNodeWithText(label).getUnclippedBoundsInRoot()
				val center = (bounds.left.value + bounds.right.value) / 2f
				assertTrue(abs(center - laneXOf(axis, value)) < 1f, "the $label label is centered on its tick (got $center, tick at ${laneXOf(axis, value)})")
			}
		}

	/** The end labels stay fully inside the strip rather than being pushed past its clipped edges. */
	@Test
	fun theEndLabelsStayInsideTheRuler() =
		runComposeUiTest {
			mountTrackSheet(TrackSheetHarness(), axis = axis)
			val first = onNodeWithText("-30").getUnclippedBoundsInRoot()
			val last = onNodeWithText("30").getUnclippedBoundsInRoot()
			assertTrue(first.left.value >= LANE_LEFT - 0.5f, "the min label starts inside the track region (left ${first.left.value})")
			assertTrue(last.right.value <= SHEET_WIDTH.value + 0.5f, "the max label ends inside the sheet (right ${last.right.value})")
			assertTrue(last.bottom.value <= TRACK_RULER_HEIGHT.value + 0.5f, "and inside the ruler strip (bottom ${last.bottom.value})")
		}
}