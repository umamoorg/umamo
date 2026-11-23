package org.umamo.ui.kit.field

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The kit property rows' description under real pointer input: it shows over a field row's label, never
 * over its control, over a checkbox row's box (never its heading), and a blank one shows nothing.
 *
 * The control half is the case worth pinning.  A tooltip wrapping the whole row compiles, looks right on a
 * plain row, and stacks a second card over every relation and color field's own button tooltips, because
 * nested tooltip areas all fire at once.
 */
@OptIn(ExperimentalTestApi::class)
class PropertyRowTooltipTest {
	/** Hovering the label half shows the description once the pointer has rested. */
	@Test
	fun aFieldRowsLabelShowsItsDescription() =
		runComposeUiTest {
			mountFieldRow(this, DESCRIPTION)
			hoverAt(this, LABEL_HALF)
			onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertExists()
		}

	/** Hovering the control half leaves the control's own hover alone. */
	@Test
	fun aFieldRowsControlDoesNotShowTheDescription() =
		runComposeUiTest {
			mountFieldRow(this, DESCRIPTION)
			hoverAt(this, CONTROL_HALF)
			onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertDoesNotExist()
			onAllNodes(isPopup()).assertCountEquals(0)
		}

	/** A row with nothing to say pops nothing, so a caller can pass an unconditional description. */
	@Test
	fun aBlankDescriptionAttachesNoTooltip() =
		runComposeUiTest {
			mountFieldRow(this, "")
			hoverAt(this, LABEL_HALF)
			onAllNodes(isPopup()).assertCountEquals(0)
		}

	/** A checkbox row's label sits beside its box in the right half, so the box is what the description tooltips. */
	@Test
	fun aCheckboxRowShowsItsDescriptionOverTheBox() =
		runComposeUiTest {
			mount(this) {
				PropertyCheckboxRow(checked = false, onCheckedChange = {}, label = LABEL, description = DESCRIPTION)
			}
			hoverAt(this, CONTROL_HALF)
			onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertExists()
		}

	/**
	 * A checkbox row's heading takes the label half, right-aligned against the control half as a field row's label
	 * is, and carries no tooltip of its own: the description stays on the box.
	 */
	@Test
	fun aCheckboxRowsHeadingSitsInTheLabelHalf() =
		runComposeUiTest {
			mount(this) {
				PropertyCheckboxRow(checked = false, onCheckedChange = {}, label = LABEL, description = DESCRIPTION, heading = HEADING)
			}
			val heading = onNodeWithText(HEADING, useUnmergedTree = true).getUnclippedBoundsInRoot()
			assertEquals(LABEL_HALF_END, heading.right, "the heading ends where a field row's label does")
			hoverAt(this, LABEL_HALF)
			onAllNodes(isPopup()).assertCountEquals(0)
			hoverAt(this, CONTROL_HALF)
			onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertExists()
		}

	/** A field row's icon leads its label without moving it off the half's end or taking the tooltip away. */
	@Test
	fun aFieldRowsIconKeepsItsLabelAndTooltip() =
		runComposeUiTest {
			mount(this) {
				PropertyFieldRow(LABEL, description = DESCRIPTION, icon = LocalUmamoIcons.language) {
					Box(modifier = Modifier.fillMaxWidth().height(20.dp))
				}
			}
			val label = onNodeWithText(LABEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
			assertEquals(LABEL_HALF_END, label.right, "the label stays right-aligned in its half")
			hoverAt(this, LABEL_HALF)
			onNodeWithText(DESCRIPTION, useUnmergedTree = true).assertExists()
		}

	/**
	 * Mounts a field row whose control is a plain box filling its half.
	 *
	 * @param ComposeUiTest test        The running UI test.
	 * @param String        description The row's description.
	 */
	private fun mountFieldRow(test: ComposeUiTest, description: String) {
		mount(test) {
			PropertyFieldRow(LABEL, description = description) {
				Box(modifier = Modifier.fillMaxWidth().height(20.dp))
			}
		}
	}

	/**
	 * Mounts [row] in a fixed-size themed box tagged for the pointer to find.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Function      row  The row under test.
	 */
	private fun mount(test: ComposeUiTest, row: @Composable () -> Unit) {
		test.setContent {
			UmamoTheme {
				Box(modifier = Modifier.size(width = 400.dp, height = 30.dp).testTag(ROW_TAG)) {
					row()
				}
			}
		}
	}

	/**
	 * Rests the pointer at [fractionX] of the row's width, halfway down, and lets the tooltip delay run out.
	 *
	 * @param ComposeUiTest test      The running UI test.
	 * @param Float         fractionX Where across the row to rest, 0 at the left edge and 1 at the right.
	 */
	private fun hoverAt(test: ComposeUiTest, fractionX: Float) {
		test.onNodeWithTag(ROW_TAG).performMouseInput {
			moveTo(Offset(width * fractionX, height / 2f))
		}
		test.mainClock.advanceTimeBy(TOOLTIP_WAIT_MILLIS)
		test.waitForIdle()
	}

	private companion object {
		const val ROW_TAG = "row"
		const val LABEL = "Opacity"
		const val DESCRIPTION = "How opaque the mesh draws."
		const val HEADING = "Display"

		/** Where a label half's text ends in the 400 dp row: the 200 dp half less its 8 dp gap to the control. */
		val LABEL_HALF_END = 192.dp

		/** A point in the label half of a field row. */
		const val LABEL_HALF = 0.25f

		/** A point in the control half, where a checkbox row's box and label sit. */
		const val CONTROL_HALF = 0.6f

		/** Comfortably past the tooltip's dwell delay. */
		const val TOOLTIP_WAIT_MILLIS = 1_000L
	}
}