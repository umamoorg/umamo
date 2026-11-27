package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.workspace.spaces.parameters.PANEL_BODY_TAG
import org.umamo.ui.workspace.spaces.parameters.PANEL_HEIGHT
import org.umamo.ui.workspace.spaces.parameters.PANEL_OUTLINER_HEIGHT
import org.umamo.ui.workspace.spaces.parameters.PANEL_ROOT_TAG
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.popupShows
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins where a row's hover art preview shows in the outliner: beside the row while the window has room
 * there, and at the pointer when the outliner spans the window and no side has room.  The preview must
 * never sit under the pointer: there it takes the row's hover and the press meant for the row.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerHoverPreviewTest {
	/**
	 * Mounts the outliner across the window but for a sliver on its left too narrow for the preview, so
	 * neither side of a row has room.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @return ParametersPanelHarness The mounted harness.
	 */
	private fun mountSpanningOutliner(test: ComposeUiTest): ParametersPanelHarness {
		val harness = test.mountOutliner(thumbnails = StubThumbnails)
		val windowBounds = test.onNodeWithTag(PANEL_ROOT_TAG).getUnclippedBoundsInRoot()
		val windowWidth = windowBounds.right - windowBounds.left
		test.runOnIdle {
			harness.panelSize = DpSize(SLIVER_WIDTH, PANEL_HEIGHT)
			harness.outlinerSize = DpSize(windowWidth - SLIVER_WIDTH, PANEL_OUTLINER_HEIGHT)
		}
		test.waitForIdle()

		return harness
	}

	/**
	 * Moves the pointer to [point] and leaves it there, without waiting for the composition to settle.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Offset point Where the pointer rests, in the panel body's pixels.
	 */
	private fun restAt(test: ComposeUiTest, point: Offset) {
		test.onNodeWithTag(PANEL_BODY_TAG).performMouseInput { moveTo(point) }
	}

	/**
	 * Runs [count] frames of a clock that is being stepped by hand.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Int count How many frames to run.
	 */
	private fun advanceFrames(test: ComposeUiTest, count: Int) {
		for (frame in 0 until count) {
			test.mainClock.advanceTimeBy(FRAME_MILLIS)
		}
	}

	/**
	 * Asserts two pixel positions agree to within the rounding of one layout pass.
	 *
	 * @param Float expected The position the placement should give.
	 * @param Float actual The position the preview has.
	 * @param String message What the assertion is about.
	 */
	private fun assertPixel(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) <= 1f, "$message: expected $expected, got $actual")
	}

	/** With room beside the outliner, the preview sits just right of the row, level with its top. */
	@Test
	fun withRoomBesideTheRowThePreviewSitsAtItsSide() =
		runComposeUiTest {
			mountOutliner(thumbnails = StubThumbnails)
			val row = rowBox(OutlinerNames.HEAD)

			hoverAt(row.center)

			assertTrue(popupShows(OutlinerNames.HEAD), "the preview must really have popped")
			val card = previewCardInWindow(OutlinerNames.HEAD)
			val rowInWindow = Rect(pointInWindow(Offset(row.left, row.top)), pointInWindow(Offset(row.right, row.bottom)))
			assertPixel(rowInWindow.right + SIDE_GAP, card.left, "the card starts a gap right of the row")
			assertPixel(rowInWindow.top, card.top, "the card is level with the row")
		}

	/** With no room beside the row, the preview sits below and right of the pointer, never under it. */
	@Test
	fun withNoRoomBesideTheRowThePreviewAnchorsToThePointer() =
		runComposeUiTest {
			mountSpanningOutliner(this)
			val point = rowBox(OutlinerNames.HEAD).at(0.4f)

			hoverAt(point)

			assertTrue(popupShows(OutlinerNames.HEAD), "the preview must really have popped")
			val card = previewCardInWindow(OutlinerNames.HEAD)
			val pointer = pointInWindow(point)
			assertPixel(pointer.x + POINTER_GAP, card.left, "the card starts a gap right of the pointer")
			assertPixel(pointer.y + POINTER_GAP, card.top, "the card starts a gap below the pointer")
			assertFalse(card.contains(pointer), "the card is never under the pointer")
		}

	/** A preview anchored to the pointer follows it along the row. */
	@Test
	fun aPointerAnchoredPreviewFollowsThePointer() =
		runComposeUiTest {
			mountSpanningOutliner(this)
			val row = rowBox(OutlinerNames.HEAD)
			hoverAt(row.at(0.3f))
			val before = previewCardInWindow(OutlinerNames.HEAD)

			hoverAt(row.at(0.6f))

			assertTrue(popupShows(OutlinerNames.HEAD), "the row is still the one hovered")
			val after = previewCardInWindow(OutlinerNames.HEAD)
			val pointer = pointInWindow(row.at(0.6f))
			assertPixel(pointer.x + POINTER_GAP, after.left, "the card moved with the pointer")
			assertTrue(after.left > before.left, "and is not where it first popped")
		}

	/**
	 * With the pointer resting on the row's chevron, the preview stays up, frame after frame, and stays clear
	 * of the pointer.
	 *
	 * The clock is stepped by hand.  A preview under the pointer trades the row's hover back and forth without
	 * end, and a clock left to run until the composition is idle would never stop.
	 */
	@Test
	fun aPointerOnTheChevronKeepsThePreviewClearOfIt() =
		runComposeUiTest {
			val harness = mountSpanningOutliner(this)
			val chevron = slotPoint(harness.text.expand, OutlinerNames.HEAD)
			mainClock.autoAdvance = false

			restAt(this, chevron)
			advanceFrames(this, RISE_FRAMES)

			for (frame in 0 until SETTLE_FRAMES) {
				mainClock.advanceTimeBy(FRAME_MILLIS)
				assertTrue(popupShows(OutlinerNames.HEAD), "the preview stays up while the pointer rests, frame $frame")
				assertFalse(previewCardInWindow(OutlinerNames.HEAD).contains(pointInWindow(chevron)), "and stays clear of the pointer, frame $frame")
			}
		}

	/**
	 * A chevron with the preview showing still folds its branch: the press reaches the chevron, not the card.
	 * Stepped by hand, for the reason the case above is.
	 */
	@Test
	fun aChevronUnderAShownPreviewStillFolds() =
		runComposeUiTest {
			val harness = mountSpanningOutliner(this)
			val chevron = slotPoint(harness.text.expand, OutlinerNames.HEAD)
			mainClock.autoAdvance = false
			restAt(this, chevron)
			advanceFrames(this, RISE_FRAMES)
			assertTrue(popupShows(OutlinerNames.HEAD), "the preview must really be showing")

			onNodeWithTag(PANEL_BODY_TAG).performMouseInput {
				press()
				advanceEventTime(FRAME_MILLIS)
				release()
			}
			advanceFrames(this, RISE_FRAMES)

			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD))
			assertTrue(outlinerShows(OutlinerNames.EYE))
		}

	private companion object {
		/**
		 * Narrower than the preview card, so a row starting after it has no room on its left, and narrow enough
		 * that a card held at the window's left edge would reach over the row's chevron.
		 */
		val SLIVER_WIDTH = 80.dp

		/** The gap RowThumbnailPreview leaves between a row and a preview beside it, in pixels at the test's density. */
		const val SIDE_GAP = 8f

		/** The gap RowThumbnailPreview leaves between the pointer and a preview anchored to it. */
		const val POINTER_GAP = 16f

		/** How many frames a rested-on row is given to pop its preview: the rest delay and the popup's first layout. */
		const val RISE_FRAMES = 6

		/** How many frames a resting pointer is watched for. */
		const val SETTLE_FRAMES = 12

		/** One frame, in milliseconds. */
		const val FRAME_MILLIS = 16L
	}
}