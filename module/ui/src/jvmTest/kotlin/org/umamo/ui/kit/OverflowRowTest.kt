package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertWidthIsEqualTo
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
	 * Shrinking a strip with a compressible control past its overflow point, twice: the first pass learns
	 * the chip's width, so the second packs in two walks that offer the search box two different bounds.
	 * A strip that measured the box on each walk would measure one Measurable twice in a pass, which
	 * Compose refuses; the strip must instead settle with the box squeezed and the trailing control in
	 * the chip.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun shrinkingPastTheOverflowPointSqueezesRatherThanCrashing() {
		runComposeUiTest {
			var stripWidth by mutableStateOf(400.dp)
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.width(stripWidth)) {
						OverflowRow {
							pinnedItem("a") { Box(modifier = Modifier.size(40.dp).testTag("a")) }
							item("search", minWidth = 40.dp) { Box(modifier = Modifier.width(120.dp).height(20.dp).testTag("search")) }
							item("b") { Box(modifier = Modifier.size(40.dp).testTag("b")) }
							item("c") { Box(modifier = Modifier.size(40.dp).testTag("c")) }
						}
					}
				}
			}
			waitForIdle()
			onNodeWithTag("search").assertWidthIsEqualTo(120.dp)
			// Past the point where c no longer fits: the chip appears and the box gives up width for b.
			stripWidth = 180.dp
			waitForIdle()
			onNodeWithContentDescription(MORE_LABEL).assertExists()
			// Narrower again, now with the chip's width known: the second-walk bound differs from the first.
			stripWidth = 150.dp
			waitForIdle()
			onNodeWithTag("a").assertExists()
			onNodeWithTag("search").assertExists()
			onNodeWithContentDescription(MORE_LABEL).assertExists()
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