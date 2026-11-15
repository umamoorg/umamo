package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.click
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasClickAction
import androidx.compose.ui.test.hasSetTextAction
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.chip.DropdownChip
import org.umamo.ui.kit.chip.PopupPanel
import org.umamo.ui.kit.field.HexColorField
import org.umamo.ui.kit.field.SelectField
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuBar
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.kit.menu.TopLevelMenu
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Clicking a popup's anchor toggles it: the click opens the popup, the next one closes it, and the one after
 * opens it again.  The non-focusable popups are the ones that pass the closing press through to the anchor,
 * which a plain clickable would answer by reopening: a menu-bar menu, a menu opened from inside another
 * popup, and the color swatch's popover.  A focusable dropdown is held to the same rule.
 */
@OptIn(ExperimentalTestApi::class)
class PopupAnchorClickableTest {
	/** A menu-bar label opens its menu and closes it again. */
	@Test
	fun aMenuBarLabelClosesItsOpenMenu() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(600.dp, 400.dp)) {
						MenuBar(menus = listOf(topLevel(FILE, FILE_ROW), topLevel(EDIT, EDIT_ROW)))
					}
				}
			}
			waitForIdle()

			assertToggles(anchor = { onNodeWithText(FILE) }, isOpen = { popupShows(FILE_ROW) })
		}

	/** A label the sweep opened by hover closes on a click, like one opened by its own click. */
	@Test
	fun aMenuBarLabelOpenedBySweepClosesOnClick() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(600.dp, 400.dp)) {
						MenuBar(menus = listOf(topLevel(FILE, FILE_ROW), topLevel(EDIT, EDIT_ROW)))
					}
				}
			}
			waitForIdle()

			onNodeWithText(FILE).performMouseInput { click() }
			waitForIdle()
			onNodeWithText(EDIT).performMouseInput { moveTo(center) }
			waitForIdle()
			assertTrue(popupShows(EDIT_ROW), "hovering the next label swept to its menu")
			assertFalse(popupShows(FILE_ROW), "and closed the first")

			onNodeWithText(EDIT).performMouseInput { click() }
			waitForIdle()
			assertFalse(popupShows(EDIT_ROW), "clicking the swept-to label closed its menu")
			assertFalse(popupShows(FILE_ROW), "and opened nothing else")
		}

	/**
	 * A chip inside a panel opens its menu non-focusable; its click closes that menu and leaves the panel open.
	 * Tapped, as on a tablet, and clicked.
	 */
	@Test
	fun aChipInsideAPanelClosesItsOpenMenu() {
		for (mouse in listOf(false, true)) {
			runComposeUiTest {
				setContent {
					UmamoTheme {
						Box(modifier = Modifier.size(600.dp, 400.dp)) {
							PopupPanel(onDismissRequest = {}) {
								Text(text = PANEL_ROW)
								var expanded by remember { mutableStateOf(false) }
								DropdownChip(expanded = expanded, onExpandRequest = { expanded = true }, contentDescription = CHIP, label = CHIP) {
									Menu(
										items = listOf(MenuItem.Action(label = CHIP_ROW, onSelect = {})),
										onDismissRequest = { expanded = false },
										positionProvider = BelowAnchorPositionProvider,
									)
								}
							}
						}
					}
				}
				waitForIdle()

				assertToggles(anchor = { onNodeWithContentDescription(CHIP, useUnmergedTree = true) }, isOpen = { popupShows(CHIP_ROW) }, mouse = mouse)
				assertTrue(popupShows(PANEL_ROW), "the panel around the chip stayed open")
			}
		}
	}

	/** The color swatch opens its slider popover and closes it again. */
	@Test
	fun theColorSwatchClosesItsOpenPopover() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(600.dp, 400.dp)) {
						HexColorField(value = COLOR, onValueChange = {})
					}
				}
			}
			waitForIdle()

			// The swatch is the field's one clickable that is not the text field.
			assertToggles(
				anchor = { onNode(hasClickAction() and !hasSetTextAction(), useUnmergedTree = true) },
				isOpen = { onAllNodes(isPopup()).fetchSemanticsNodes().isNotEmpty() },
			)
		}

	/** A focusable dropdown swallows the closing press itself, and its chip keeps to the same toggle. */
	@Test
	fun aFocusableDropdownClosesFromItsChip() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(600.dp, 400.dp)) {
						SelectField(selected = OPTION_A, options = listOf(OPTION_A, OPTION_B), label = { option -> option }, onSelect = {})
					}
				}
			}
			waitForIdle()

			assertToggles(anchor = { onNodeWithContentDescription(OPTION_A, useUnmergedTree = true) }, isOpen = { popupShows(OPTION_B) })
		}

	/**
	 * Clicks [anchor] three times and checks the popup opens, closes, and opens again.
	 *
	 * @param Function anchor Finds the anchor node.
	 * @param Function isOpen Whether the popup shows.
	 * @param Boolean mouse Whether to click with the mouse rather than tap.
	 */
	private fun ComposeUiTest.assertToggles(anchor: () -> SemanticsNodeInteraction, isOpen: () -> Boolean, mouse: Boolean = true) {
		val input = if (mouse) "click" else "tap"
		assertFalse(isOpen(), "the popup starts closed")

		press(anchor(), mouse)
		assertTrue(isOpen(), "a $input on the anchor opened the popup")

		press(anchor(), mouse)
		assertFalse(isOpen(), "a $input on the anchor of the open popup closed it")

		press(anchor(), mouse)
		assertTrue(isOpen(), "the next $input opened it again")
	}

	/**
	 * Clicks or taps [node] and lets the frame settle.
	 *
	 * @param SemanticsNodeInteraction node The node to press.
	 * @param Boolean mouse Whether to click with the mouse rather than tap.
	 */
	private fun ComposeUiTest.press(node: SemanticsNodeInteraction, mouse: Boolean) {
		if (mouse) {
			node.performMouseInput { click() }
		} else {
			node.performClick()
		}

		waitForIdle()
	}

	/**
	 * Whether a popup shows a node reading exactly [text].
	 *
	 * @param String text The node's text.
	 * @return Boolean True when it does.
	 */
	private fun ComposeUiTest.popupShows(text: String): Boolean = onAllNodes(hasText(text) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	/**
	 * A menu-bar menu holding one row.
	 *
	 * @param String label The bar label.
	 * @param String row The row's label.
	 * @return TopLevelMenu The menu.
	 */
	private fun topLevel(label: String, row: String): TopLevelMenu = TopLevelMenu(label = label) { listOf(MenuItem.Action(label = row, onSelect = {})) }

	private companion object {
		const val FILE = "File"
		const val FILE_ROW = "Open"
		const val EDIT = "Edit"
		const val EDIT_ROW = "Undo"
		const val CHIP = "Mode"
		const val CHIP_ROW = "Vertex"
		const val PANEL_ROW = "Overlays"
		const val COLOR = "#FF336699"
		const val OPTION_A = "Normal"
		const val OPTION_B = "Multiply"
	}
}