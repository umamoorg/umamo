package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * A dropdown collapsed into the overflow panel is still usable from inside it.
 *
 * This is the nested-popup hazard the kit's own Menu docblock names: only one popup in a tree may be
 * focusable, or they fight over the dismiss and the inner press reads as "outside" to the outer popup,
 * tearing the inner one down mid-click.  LocalPopupDismissOwned is what makes the inner menu yield, and
 * this is the test that would catch it regressing.
 */
class OverflowPopupNestedMenuTest {
	/** A menu opened from inside the overflow panel survives long enough to be clicked. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aMenuInsideTheOverflowPanelCanBeDriven() {
		var selected = false
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.width(80.dp)) {
						OverflowRow {
							pinnedItem("pin") { Box(modifier = Modifier.size(40.dp).testTag("pin")) }
							item("chip") {
								var expanded by remember { mutableStateOf(false) }
								DropdownChip(
									expanded = expanded,
									onExpandRequest = { expanded = true },
									contentDescription = CHIP_LABEL,
									label = CHIP_LABEL,
								) {
									Menu(
										items = listOf(MenuItem.Action(label = ROW_LABEL, onSelect = { selected = true })),
										onDismissRequest = { expanded = false },
										positionProvider = BelowAnchorPositionProvider,
									)
								}
							}
						}
					}
				}
			}
			waitForIdle()
			// The chip did not fit, so it lives in the panel rather than on the strip.
			onNodeWithContentDescription(CHIP_LABEL, useUnmergedTree = true).assertDoesNotExist()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithContentDescription(CHIP_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithText(ROW_LABEL, useUnmergedTree = true).assertExists()
			onNodeWithText(ROW_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
		}
		assertTrue(selected, "the row inside the nested menu never fired")
	}

	/** Widening the strip past the collapse point cannot leave the panel open with no anchor. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun wideningTheStripClosesAnOpenPanel() {
		runComposeUiTest {
			var stripWidth by mutableStateOf(80.dp)
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.width(stripWidth)) {
						OverflowRow {
							pinnedItem("pin") { Box(modifier = Modifier.size(40.dp).testTag("pin")) }
							item("wide") { Box(modifier = Modifier.size(40.dp).testTag("wide")) }
						}
					}
				}
			}
			waitForIdle()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithTag("wide").assertExists()

			stripWidth = 400.dp
			waitForIdle()
			// Back on the strip and no longer in a panel: the chip itself is gone, so nothing is orphaned.
			onNodeWithTag("wide").assertExists()
			onNodeWithContentDescription(MORE_LABEL, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	private companion object {
		/** The overflow chip's English name; it doubles as its accessible label. */
		const val MORE_LABEL = "More"

		const val CHIP_LABEL = "Pivot"
		const val ROW_LABEL = "Median Point"
	}
}