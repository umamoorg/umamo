package org.umamo.ui.kit.chip

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getBoundsInRoot
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
 * the panel, the chevron half opens the panel and never flips the toggle, and a disabled chip does neither.  A
 * value glyph rides the chevron half.
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

	/** A value glyph sits in the chevron half: a click there opens the panel and leaves the toggle alone. */
	@Test
	fun theValueGlyphRidesTheChevronHalf() =
		runComposeUiTest {
			var lit by mutableStateOf(true)
			setContent {
				UmamoTheme {
					PopupChip(
						contentDescription = OPEN,
						icon = LocalUmamoIcons.overlays,
						iconToggle = ChipToggle(active = lit, onToggle = { lit = !lit }, contentDescription = TOGGLE),
						valueIcon = LocalUmamoIcons.filterFiltered,
					) {
						Text(text = ROW)
					}
				}
			}
			waitForIdle()

			onNodeWithContentDescription(OPEN, useUnmergedTree = true).performClick()
			waitForIdle()

			assertTrue(panelShows(ROW), "the chevron half, glyph and all, opened the panel")
			assertTrue(lit, "and left the toggle alone")
		}

	/** The value glyph widens the chevron half by exactly its own size, on both the split and the plain face. */
	@Test
	fun theValueGlyphWidensTheChevronHalfByItsSize() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Column {
						PopupChip(
							contentDescription = SPLIT_BARE,
							icon = LocalUmamoIcons.overlays,
							iconToggle = ChipToggle(active = true, onToggle = {}, contentDescription = TOGGLE),
						) {}
						PopupChip(
							contentDescription = SPLIT_VALUED,
							icon = LocalUmamoIcons.overlays,
							iconToggle = ChipToggle(active = true, onToggle = {}, contentDescription = TOGGLE),
							valueIcon = LocalUmamoIcons.filterFiltered,
						) {}
						PopupChip(contentDescription = PLAIN_BARE, icon = LocalUmamoIcons.overlays) {}
						PopupChip(contentDescription = PLAIN_VALUED, icon = LocalUmamoIcons.overlays, valueIcon = LocalUmamoIcons.filterFiltered) {}
					}
				}
			}
			waitForIdle()

			val splitGrowth = widthOf(SPLIT_VALUED) - widthOf(SPLIT_BARE)
			val plainGrowth = widthOf(PLAIN_VALUED) - widthOf(PLAIN_BARE)
			assertEquals(HEADER_GLYPH_SIZE_DP, splitGrowth, 0.5f, "the split face's chevron half grows by the glyph")
			assertEquals(HEADER_GLYPH_SIZE_DP, plainGrowth, 0.5f, "the plain face grows by the glyph")
		}

	/**
	 * The width of the node carrying an accessible name, in dp.
	 *
	 * @param String description The node's content description.
	 * @return Float The node's width in dp.
	 */
	private fun androidx.compose.ui.test.ComposeUiTest.widthOf(description: String): Float {
		val bounds = onNodeWithContentDescription(description, useUnmergedTree = true).getBoundsInRoot()
		return (bounds.right - bounds.left).value
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

		/** A split chip without a value glyph. */
		const val SPLIT_BARE = "Split bare"

		/** A split chip with a value glyph. */
		const val SPLIT_VALUED = "Split valued"

		/** A plain chip without a value glyph. */
		const val PLAIN_BARE = "Plain bare"

		/** A plain chip with a value glyph. */
		const val PLAIN_VALUED = "Plain valued"

		/** A Header chip's glyph size, in dp. */
		const val HEADER_GLYPH_SIZE_DP = 16f
	}
}