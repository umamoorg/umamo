package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * A menu with a search box and section headings: the box is pinned, focused on open, and fixes the
 * menu's width; the caller filters the rows through the query it hands back; a heading is inert; an
 * action still dismisses.  The shape the Sources relink menu and the UV layer picker are built on.
 */
class MenuSearchAndHeadingTest {
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aSearchMenuFiltersThroughItsCallerAndKeepsItsWidth() {
		var picked: String? = null
		var dismissed = 0
		runComposeUiTest {
			setContent {
				UmamoTheme {
					var expanded by remember { mutableStateOf(true) }
					var query by remember { mutableStateOf("") }
					val names = ALL_ROWS.filter { name -> name.contains(query, ignoreCase = true) }
					Box(modifier = Modifier.size(400.dp, 300.dp)) {
						DropdownChip(
							expanded = expanded,
							onExpandRequest = { expanded = true },
							contentDescription = CHIP_LABEL,
							label = CHIP_LABEL,
						) {
							Menu(
								items =
									buildList {
										add(MenuItem.Search(value = query, onValueChange = { updated -> query = updated }, width = MENU_WIDTH, placeholder = HINT))
										add(MenuItem.Heading(HEADING))
										for (name in names) {
											add(MenuItem.Action(label = name, onSelect = { picked = name }))
										}
									},
								onDismissRequest = {
									expanded = false
									dismissed++
								},
								positionProvider = BelowAnchorPositionProvider,
								modifier = Modifier.testTag(ROWS_TAG),
							)
						}
					}
				}
			}
			waitForIdle()
			onNodeWithText(HEADING, useUnmergedTree = true).assertExists()
			onNode(hasSetTextAction(), useUnmergedTree = true).assertIsFocused()
			onNodeWithTag(ROWS_TAG, useUnmergedTree = true).assertWidthIsEqualTo(MENU_WIDTH)

			// Typing reaches the caller, which rebuilds the rows; the menu stays open at the same width even
			// though the longest row is gone.
			onNode(hasSetTextAction(), useUnmergedTree = true).performTextInput("eye")
			waitForIdle()
			onNodeWithText(ALL_ROWS[0], useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(ALL_ROWS[2], useUnmergedTree = true).assertDoesNotExist()
			onNodeWithText(ALL_ROWS[1], useUnmergedTree = true).assertExists()
			assertEquals(0, dismissed, "typing does not dismiss")
			onNodeWithTag(ROWS_TAG, useUnmergedTree = true).assertWidthIsEqualTo(MENU_WIDTH)

			// A heading is inert.
			onNodeWithText(HEADING, useUnmergedTree = true).performClick()
			waitForIdle()
			assertEquals(0, dismissed, "a heading does not dismiss")
			assertNull(picked, "a heading selects nothing")

			// An action picks and dismisses as ever.
			onNodeWithText(ALL_ROWS[1], useUnmergedTree = true).performClick()
			waitForIdle()
			assertEquals(ALL_ROWS[1], picked)
			assertEquals(1, dismissed)
		}
	}

	/**
	 * A search menu over a long list stays under its chip: its rows cap well below the window and scroll,
	 * instead of standing as tall as the window and being clamped to the window's bottom edge - which
	 * opened the layer pickers far from their chip until a search shrank them back.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aSearchMenuOverALongListStaysAnchoredUnderItsChip() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					var expanded by remember { mutableStateOf(true) }
					Box(modifier = Modifier.size(400.dp, 700.dp)) {
						DropdownChip(
							expanded = expanded,
							onExpandRequest = { expanded = true },
							contentDescription = CHIP_LABEL,
							label = CHIP_LABEL,
							modifier = Modifier.testTag(CHIP_TAG),
						) {
							Menu(
								items =
									buildList {
										add(MenuItem.Search(value = "", onValueChange = {}, width = MENU_WIDTH))
										for (index in 0 until 80) {
											add(MenuItem.Action(label = "Layer $index", onSelect = {}))
										}
									},
								onDismissRequest = { expanded = false },
								positionProvider = BelowAnchorPositionProvider,
								modifier = Modifier.testTag(ROWS_TAG),
							)
						}
					}
				}
			}
			waitForIdle()
			val chip = onNodeWithTag(CHIP_TAG, useUnmergedTree = true).getBoundsInRoot()
			val rows = onNodeWithTag(ROWS_TAG, useUnmergedTree = true).getBoundsInRoot()
			assertTrue(rows.height <= MENU_SEARCH_ROWS_MAX_HEIGHT, "rows are capped at ${MENU_SEARCH_ROWS_MAX_HEIGHT}, got ${rows.height}")
			assertTrue(rows.top >= chip.bottom, "the menu hangs under the chip (rows top ${rows.top}, chip bottom ${chip.bottom})")
		}
	}

	private companion object {
		const val CHIP_TAG = "chip"
		const val CHIP_LABEL = "Relink"
		const val HEADING = "erica.psd"
		const val HINT = "Filter layers"
		const val ROWS_TAG = "rows"
		val MENU_WIDTH = 240.dp
		val ALL_ROWS = listOf("Hair Front", "Eye L", "A layer whose name runs well past the width of the menu")
	}
}