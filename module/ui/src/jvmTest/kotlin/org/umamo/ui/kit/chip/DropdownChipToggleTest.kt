package org.umamo.ui.kit.chip

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * A chip carrying a [ChipToggle] is two controls in one face: the glyph half flips the toggle and never opens
 * the panel, the chevron half opens the panel and never flips the toggle, and a disabled chip does neither.
 */
@OptIn(ExperimentalTestApi::class)
class DropdownChipToggleTest {
	/** The glyph half flips the toggle without opening the panel. */
	@Test
	fun theGlyphHalfFlipsTheToggleAndLeavesThePanelShut() =
		runComposeUiTest {
			var lit by mutableStateOf(true)
			setContent {
				UmamoTheme {
					PopupChip(
						contentDescription = OPEN,
						icon = LocalUmamoIcons.overlays,
						iconToggle = ChipToggle(active = lit, onToggle = { lit = !lit }, contentDescription = TOGGLE),
					) {
						Text(text = ROW)
					}
				}
			}
			waitForIdle()

			onNodeWithContentDescription(TOGGLE, useUnmergedTree = true).performClick()
			waitForIdle()

			assertFalse(lit, "the glyph half flipped the toggle")
			assertFalse(panelShows(ROW), "and opened nothing")
		}

	/** The chevron half opens the panel and leaves the toggle as it was. */
	@Test
	fun theChevronHalfOpensThePanelAndLeavesTheToggleAlone() =
		runComposeUiTest {
			var lit by mutableStateOf(true)
			setContent {
				UmamoTheme {
					PopupChip(
						contentDescription = OPEN,
						icon = LocalUmamoIcons.overlays,
						iconToggle = ChipToggle(active = lit, onToggle = { lit = !lit }, contentDescription = TOGGLE),
					) {
						Text(text = ROW)
					}
				}
			}
			waitForIdle()

			onNodeWithContentDescription(OPEN, useUnmergedTree = true).performClick()
			waitForIdle()

			assertTrue(panelShows(ROW), "the chevron half opened the panel")
			assertTrue(lit, "and left the toggle alone")
		}

	/** A disabled chip takes neither click. */
	@Test
	fun aDisabledChipTakesNeitherClick() =
		runComposeUiTest {
			var flips = 0
			setContent {
				UmamoTheme {
					PopupChip(
						contentDescription = OPEN,
						icon = LocalUmamoIcons.overlays,
						iconToggle = ChipToggle(active = true, onToggle = { flips++ }, contentDescription = TOGGLE),
						enabled = false,
					) {
						Text(text = ROW)
					}
				}
			}
			waitForIdle()

			onNodeWithContentDescription(TOGGLE, useUnmergedTree = true).performClick()
			onNodeWithContentDescription(OPEN, useUnmergedTree = true).performClick()
			waitForIdle()

			assertEquals(0, flips)
			assertFalse(panelShows(ROW))
		}

	/**
	 * Whether the panel is open, read as its row being on screen inside a popup.
	 *
	 * @param String label The row's text.
	 * @return Boolean True while the panel shows the row.
	 */
	private fun androidx.compose.ui.test.ComposeUiTest.panelShows(label: String): Boolean =
		onAllNodes(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	private companion object {
		/** The chevron half's accessible name. */
		const val OPEN = "Open Panel"

		/** The glyph half's accessible name. */
		const val TOGGLE = "Toggle"

		/** The one row the panel holds. */
		const val ROW = "A row"
	}
}