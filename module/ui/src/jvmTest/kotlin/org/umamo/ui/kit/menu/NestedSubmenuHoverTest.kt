package org.umamo.ui.kit.menu

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.AtPointPositionProvider
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A flyout two levels deep holds its ancestors open while the pointer rests on it: an outer submenu row
 * counts the pointer as still on its flyout when it is on any flyout nested inside that one.
 */
@OptIn(ExperimentalTestApi::class)
class NestedSubmenuHoverTest {
	/** Resting on a second-level flyout keeps the whole chain open, and its row can be clicked. */
	@Test
	fun aSecondLevelFlyoutStaysOpenWhileHovered() {
		var picked = 0
		var dismissed = 0
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(800.dp, 600.dp)) {
						Menu(
							items =
								listOf(
									MenuItem.Action(label = ROOT_ACTION, onSelect = {}),
									MenuItem.Submenu(
										label = OUTER_SUBMENU,
										items =
											listOf(
												MenuItem.Action(label = OUTER_ACTION, onSelect = {}),
												MenuItem.Submenu(
													label = INNER_SUBMENU,
													items = listOf(MenuItem.Action(label = INNER_ACTION, onSelect = { picked++ })),
												),
											),
									),
								),
							onDismissRequest = { dismissed++ },
							positionProvider = AtPointPositionProvider(IntOffset(20, 20)),
							focusable = true,
						)
					}
				}
			}
			waitForIdle()

			hoverPopupText(OUTER_SUBMENU)
			hoverPopupText(INNER_SUBMENU)
			hoverPopupText(INNER_ACTION)
			assertTrue(popupShows(INNER_ACTION), "the second-level flyout closed under the pointer")
			assertTrue(popupShows(OUTER_ACTION), "the first-level flyout closed while its child was hovered")

			onNode(hasText(INNER_ACTION) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performClick()
			waitForIdle()
			assertEquals(1, picked, "the second-level row never fired")
			assertEquals(1, dismissed, "picking a nested row dismisses the tree once")
		}
	}

	/** Leaving a second-level flyout for a plain root row still closes the chain after the grace. */
	@Test
	fun leavingTheNestedChainClosesIt() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.size(800.dp, 600.dp)) {
						Menu(
							items =
								listOf(
									MenuItem.Action(label = ROOT_ACTION, onSelect = {}),
									MenuItem.Submenu(
										label = OUTER_SUBMENU,
										items =
											listOf(
												MenuItem.Action(label = OUTER_ACTION, onSelect = {}),
												MenuItem.Submenu(
													label = INNER_SUBMENU,
													items = listOf(MenuItem.Action(label = INNER_ACTION, onSelect = {})),
												),
											),
									),
								),
							onDismissRequest = {},
							positionProvider = AtPointPositionProvider(IntOffset(20, 20)),
							focusable = true,
						)
					}
				}
			}
			waitForIdle()

			hoverPopupText(OUTER_SUBMENU)
			hoverPopupText(INNER_SUBMENU)
			hoverPopupText(INNER_ACTION)
			hoverPopupText(ROOT_ACTION)
			assertTrue(!popupShows(INNER_ACTION), "the second-level flyout outlived the pointer leaving the chain")
			assertTrue(!popupShows(OUTER_ACTION), "the first-level flyout outlived the pointer leaving the chain")
			assertTrue(popupShows(ROOT_ACTION), "the root menu stays open")
		}
	}

	/**
	 * Rests the pointer on the popup text [label] and lets any flyout's close grace run out.
	 *
	 * @param String label The row text.
	 */
	private fun ComposeUiTest.hoverPopupText(label: String) {
		onNode(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).performMouseInput {
			moveTo(center)
		}
		mainClock.advanceTimeBy(SETTLE_MILLIS)
		waitForIdle()
	}

	/**
	 * Whether popup text [label] is on screen.
	 *
	 * @param String label The row text.
	 * @return Boolean True when it shows.
	 */
	private fun ComposeUiTest.popupShows(label: String): Boolean =
		onAllNodes(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	private companion object {
		/** Well past the flyout close grace. */
		const val SETTLE_MILLIS = 1_000L

		const val ROOT_ACTION = "Duplicate"
		const val OUTER_SUBMENU = "Select"
		const val OUTER_ACTION = "Select All"
		const val INNER_SUBMENU = "Select All of Type"
		const val INNER_ACTION = "Drawables"
	}
}