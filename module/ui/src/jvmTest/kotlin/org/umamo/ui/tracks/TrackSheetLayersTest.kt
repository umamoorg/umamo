package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The layers an owner stacks around its scrolling sheet, driven with real pointer input: the column
 * separator's drag and its bounds, and the window scrollbar's pan and its disappearance at full span.
 */
@OptIn(ExperimentalTestApi::class)
class TrackSheetLayersTest {
	/** The label column width the separator drives. */
	private var labelColumnWidth by mutableStateOf(TRACK_LABEL_COLUMN_DEFAULT_WIDTH)

	/** What the separator reported about a resize being in flight, in order. */
	private val draggingReports = mutableListOf<Boolean>()

	/** The window the scrollbar drives. */
	private var window by mutableStateOf(TrackWindow(0f, 0.5f))

	/**
	 * Mounts the separator alone over a [SHEET_WIDTH] x [SHEET_HEIGHT] box at the current column width.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun mountSeparator(test: ComposeUiTest) {
		test.setContent {
			Box(modifier = Modifier.size(width = SHEET_WIDTH, height = SHEET_HEIGHT).testTag(SHEET_TAG)) {
				TrackSheetSeparatorOverlay(
					labelColumnWidth = labelColumnWidth,
					onLabelColumnWidthChange = { width -> labelColumnWidth = width },
					modifier = Modifier.fillMaxSize(),
					onDraggingChange = { dragging -> draggingReports += dragging },
				)
			}
		}
	}

	/**
	 * Mounts the scrollbar alone in a [SHEET_WIDTH]-wide box that wraps its height, so a scrollbar that
	 * composes nothing leaves the box at zero height.
	 *
	 * @param ComposeUiTest test The running UI test.
	 */
	private fun mountScrollbar(test: ComposeUiTest) {
		test.setContent {
			Box(modifier = Modifier.width(SHEET_WIDTH).wrapContentHeight().testTag(SHEET_TAG)) {
				TrackWindowScrollbar(
					window = window,
					onWindowChange = { updated -> window = updated },
					labelColumnWidth = TRACK_LABEL_COLUMN_DEFAULT_WIDTH,
				)
			}
		}
	}

	/**
	 * The height of the box wrapping the scrollbar.
	 *
	 * @return Dp The height.
	 */
	private fun ComposeUiTest.wrapperHeight(): Dp {
		val bounds = onNodeWithTag(SHEET_TAG).getUnclippedBoundsInRoot()
		return bounds.bottom - bounds.top
	}

	/**
	 * Drags horizontally from [startX] through the given x positions at [y], primary button held.
	 *
	 * @param Float startX Where the press lands.
	 * @param List<Float> through The x positions moved through, in order.
	 * @param Float y The y of the whole drag.
	 */
	private fun ComposeUiTest.dragAcross(startX: Float, through: List<Float>, y: Float) {
		onNodeWithTag(SHEET_TAG).performMouseInput {
			moveTo(Offset(startX, y))
			press()
			for (x in through) {
				moveTo(Offset(x, y))
			}
			release()
		}
		waitForIdle()
	}

	/** Dragging the separator widens the column by the drag, and reports the resize beginning and ending. */
	@Test
	fun theSeparatorDragWidensTheColumn() =
		runComposeUiTest {
			mountSeparator(this)
			// The grab band straddles the divider at the column's right edge.
			dragAcross(LANE_LEFT, listOf(LANE_LEFT + 20f, LANE_LEFT + 40f, LANE_LEFT + 60f), y = 100f)
			// Compose's drag gesture eats a pointer slop (a fraction of a pixel for a mouse) before the
			// first delta, so the width lands a hair short of the full sixty.
			assertTrue(
				abs(labelColumnWidth.value - (TRACK_LABEL_COLUMN_DEFAULT_WIDTH.value + 60f)) < 1f,
				"a 60 px drag widens the column by about 60 dp (got ${labelColumnWidth.value})",
			)
			assertEquals(listOf(true, false), draggingReports, "the resize is reported as it begins and as it ends")
		}

	/** The column cannot be dragged narrower than its minimum or wider than its maximum. */
	@Test
	fun theSeparatorDragClampsToTheColumnBounds() =
		runComposeUiTest {
			mountSeparator(this)
			dragAcross(LANE_LEFT, listOf(LANE_LEFT - 100f, LANE_LEFT - 200f, LANE_LEFT - 500f), y = 100f)
			assertEquals(TRACK_LABEL_COLUMN_MIN_WIDTH, labelColumnWidth, "dragged far left, the column stops at its minimum")

			// The band now sits at the narrowed column's edge.
			val narrowedEdge = TRACK_LABEL_COLUMN_MIN_WIDTH.value + 1f
			dragAcross(narrowedEdge, listOf(narrowedEdge + 200f, narrowedEdge + 400f, narrowedEdge + 410f), y = 100f)
			assertEquals(TRACK_LABEL_COLUMN_MAX_WIDTH, labelColumnWidth, "dragged far right, the column stops at its maximum")
		}

	/** Dragging the scrollbar's track pans the window by the drag as a fraction of the track's width. */
	@Test
	fun theScrollbarDragPansTheWindow() =
		runComposeUiTest {
			mountScrollbar(this)
			val trackWidth = SHEET_WIDTH.value - LANE_LEFT
			val y = SCROLLBAR_THICKNESS.value / 2f
			dragAcross(LANE_LEFT + 50f, listOf(LANE_LEFT + 80f, LANE_LEFT + 110f), y = y)
			val expectedStart = 60f / trackWidth
			assertTrue(
				abs(window.start - expectedStart) < 0.005f,
				"a 60 px drag over a $trackWidth px track pans by about $expectedStart (got ${window.start})",
			)
			assertEquals(0.5f, window.span, 1e-5f, "the span is untouched by a pan")
		}

	/** At full span the scrollbar composes nothing: a full-width thumb would say nothing. */
	@Test
	fun theScrollbarHidesAtFullSpan() =
		runComposeUiTest {
			window = TrackWindow.Full
			mountScrollbar(this)
			assertEquals(Dp(0f), wrapperHeight(), "nothing composed, so the wrapping box has no height")

			runOnIdle { window = TrackWindow(0f, 0.5f) }
			waitForIdle()
			assertEquals(SCROLLBAR_THICKNESS, wrapperHeight(), "zoomed in, the bar takes its full thickness")
		}
}