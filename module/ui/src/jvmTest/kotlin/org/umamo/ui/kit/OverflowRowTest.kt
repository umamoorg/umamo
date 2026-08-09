package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test

/**
 * The strip collapses instead of crushing: controls that no longer fit stop being composed at all and
 * reappear inside the overflow chip's panel, while a pinned control survives every width.
 *
 * assertDoesNotExist rather than a visibility check is the point of several of these - a collapsed
 * control must be genuinely uncomposed, not merely unplaced, or its state and its own popups would live
 * on invisibly.
 */
class OverflowRowTest {
	/** With room to spare every control is present and the chip is not. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aRoomyStripShowsEverythingAndNoChip() {
		runComposeUiTest {
			setStrip(stripWidth = 400.dp)
			onNodeWithTag("a").assertExists()
			onNodeWithTag("b").assertExists()
			onNodeWithTag("c").assertExists()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** A narrow strip drops the trailing controls entirely and raises the chip. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aNarrowStripCollapsesTheTrailingControls() {
		runComposeUiTest {
			setStrip(stripWidth = 120.dp)
			onNodeWithTag("a").assertExists()
			onNodeWithTag("c").assertDoesNotExist()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).assertExists()
		}
	}

	/** Opening the chip brings the collapsed controls back, inside its panel. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theChipPanelHoldsTheCollapsedControls() {
		runComposeUiTest {
			setStrip(stripWidth = 120.dp)
			onNodeWithTag("c").assertDoesNotExist()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithTag("c").assertExists()
		}
	}

	/** A pinned control is placed at every width, however little room is left. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aPinnedControlSurvivesEveryWidth() {
		runComposeUiTest {
			setStrip(stripWidth = 10.dp)
			onNodeWithTag("a").assertExists()
		}
	}

	/** An item that renders nothing is dropped rather than collapsed - it never reaches the panel. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun anEmptyItemIsDroppedNotCollapsed() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.width(400.dp)) {
						OverflowRow {
							item("present") { Box(modifier = Modifier.size(40.dp).testTag("present")) }
							item("empty") { }
						}
					}
				}
			}
			waitForIdle()
			onNodeWithTag("present").assertExists()
			// Nothing collapsed, so no chip: an item with no content costs the strip nothing at all.
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/**
	 * Composes the standard three-control strip inside a fixed-width box: one pinned 40.dp control
	 * followed by two collapsible 40.dp controls.
	 *
	 * @param Dp stripWidth The width the strip is given.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.setStrip(stripWidth: Dp) {
		setContent {
			UmamoTheme {
				Box(modifier = Modifier.width(stripWidth)) {
					OverflowRow {
						pinnedItem("a") { Box(modifier = Modifier.size(40.dp).testTag("a")) }
						item("b") { Box(modifier = Modifier.size(40.dp).testTag("b")) }
						item("c") { Box(modifier = Modifier.size(40.dp).testTag("c")) }
					}
				}
			}
		}
		waitForIdle()
	}

	private companion object {
		/** The overflow chip's English name; it doubles as its accessible label. */
		const val MORE_LABEL = "More"
	}
}