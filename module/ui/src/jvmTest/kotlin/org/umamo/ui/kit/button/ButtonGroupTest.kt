package org.umamo.ui.kit.button

import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.height
import androidx.compose.ui.unit.width
import org.umamo.ui.kit.StackAxis
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A button group's segments: a labeled segment shows its label beside its glyph and reports whether it is lit,
 * a vertical group stacks its segments into one block of one width, icon-only segments keep their fixed face,
 * and the hover label shows only when it says something the face does not.
 */
@OptIn(ExperimentalTestApi::class)
class ButtonGroupTest {
	/** A labeled segment shows its label, reports its selected state, and a click runs its action. */
	@Test
	fun aLabeledSegmentShowsItsLabelReportsItsStateAndClicks() =
		runComposeUiTest {
			var clicks = 0
			setContent {
				UmamoTheme {
					ButtonGroup(
						items =
							listOf(
								ButtonGroupItem(LocalUmamoIcons.falloffSmooth, selected = true, onClick = {}, contentDescription = SHORT, label = SHORT),
								ButtonGroupItem(LocalUmamoIcons.falloffSharp, selected = false, onClick = { clicks++ }, contentDescription = MEDIUM, label = MEDIUM),
							),
						axis = StackAxis.Vertical,
					)
				}
			}
			waitForIdle()

			assertTrue(onAllNodes(hasText(SHORT)).fetchSemanticsNodes().isNotEmpty(), "the label is on the face")
			onNodeWithContentDescription(SHORT).assertIsSelected()
			onNodeWithContentDescription(MEDIUM).assertIsNotSelected()
			onNodeWithContentDescription(MEDIUM).performClick()
			waitForIdle()
			assertEquals(1, clicks)
		}

	/** A vertical group stacks its segments down one left edge, every segment as wide as the widest. */
	@Test
	fun aVerticalGroupStacksItsSegmentsAtOneWidth() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					ButtonGroup(
						items =
							listOf(SHORT, MEDIUM, LONG).map { label ->
								ButtonGroupItem(LocalUmamoIcons.falloffSmooth, selected = false, onClick = {}, contentDescription = label, label = label)
							},
						axis = StackAxis.Vertical,
					)
				}
			}
			waitForIdle()

			val bounds = listOf(SHORT, MEDIUM, LONG).map { label -> boundsOf(label) }
			for (index in 1 until bounds.size) {
				assertTrue(bounds[index].top > bounds[index - 1].top, "segment $index sits below the one before it")
				assertEquals(bounds[0].left.value, bounds[index].left.value, 0.5f, "segment $index shares the left edge")
				assertEquals(bounds[0].width.value, bounds[index].width.value, 0.5f, "segment $index shares the width")
			}
			assertTrue(bounds[0].width.value > ICON_FACE_WIDTH_DP, "the shared width is the longest label's, past a bare glyph face")
		}

	/** Icon-only segments in a row keep their fixed glyph face, as the header strips lay them out. */
	@Test
	fun iconOnlySegmentsKeepTheirFace() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					ButtonGroup(
						items =
							listOf(
								ButtonGroupItem(LocalUmamoIcons.meshSelectVertex, selected = true, onClick = {}, contentDescription = SHORT),
								ButtonGroupItem(LocalUmamoIcons.meshSelectEdge, selected = false, onClick = {}, contentDescription = MEDIUM),
							),
					)
				}
			}
			waitForIdle()

			val first = boundsOf(SHORT)
			val second = boundsOf(MEDIUM)
			assertEquals(ICON_FACE_WIDTH_DP, first.width.value, 0.5f)
			assertEquals(ICON_FACE_HEIGHT_DP, first.height.value, 0.5f)
			assertEquals(first.top.value, second.top.value, 0.5f, "a row keeps its segments side by side")
			assertTrue(second.left > first.right, "the second segment follows the first past the seam")
		}

	/** A description that only repeats the label shows no hover label; one that adds to it does. */
	@Test
	fun theHoverLabelShowsOnlyWhenItAddsToTheFace() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					ButtonGroup(
						items =
							listOf(
								ButtonGroupItem(LocalUmamoIcons.falloffSmooth, selected = false, onClick = {}, contentDescription = SHORT, label = SHORT),
								ButtonGroupItem(LocalUmamoIcons.falloffSharp, selected = false, onClick = {}, contentDescription = DESCRIPTION, label = MEDIUM),
							),
						axis = StackAxis.Vertical,
					)
				}
			}
			waitForIdle()

			hover(SHORT)
			assertFalse(tooltipShows(SHORT), "a description equal to the label is not echoed")
			hover(DESCRIPTION)
			assertTrue(tooltipShows(DESCRIPTION), "a description beyond the label shows on hover")
		}

	/**
	 * The bounds of the segment carrying an accessible name.
	 *
	 * @param String description The segment's content description.
	 * @return DpRect The segment's bounds in the root.
	 */
	private fun ComposeUiTest.boundsOf(description: String): DpRect = onNodeWithContentDescription(description).getBoundsInRoot()

	/**
	 * Rests the pointer on the segment carrying an accessible name and lets the tooltip's dwell run out.
	 *
	 * @param String description The segment's content description.
	 */
	private fun ComposeUiTest.hover(description: String) {
		onNodeWithContentDescription(description).performMouseInput {
			moveTo(center)
		}
		mainClock.advanceTimeBy(TOOLTIP_WAIT_MILLIS)
		waitForIdle()
	}

	/**
	 * Whether a tooltip popup shows the text.
	 *
	 * @param String text The text.
	 * @return Boolean True while a popup carries it.
	 */
	private fun ComposeUiTest.tooltipShows(text: String): Boolean =
		onAllNodes(hasText(text) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	private companion object {
		/** A short label. */
		const val SHORT = "Root"

		/** A middling label. */
		const val MEDIUM = "Sharp"

		/** The longest label. */
		const val LONG = "Inverse Square"

		/** A description that says more than its segment's label. */
		const val DESCRIPTION = "Sharp falloff, steep near the selection"

		/** An icon-only segment's face width, in dp. */
		const val ICON_FACE_WIDTH_DP = 26f

		/** An icon-only segment's face height, in dp. */
		const val ICON_FACE_HEIGHT_DP = 20f

		/** Comfortably past the tooltip's dwell delay. */
		const val TOOLTIP_WAIT_MILLIS = 1_000L
	}
}