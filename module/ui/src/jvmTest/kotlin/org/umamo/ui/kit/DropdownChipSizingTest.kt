package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A Header chip holds its size when its parent runs out of room.
 *
 * The failure this guards is silent on screen and was live: Modifier.size coerces to the incoming
 * constraints, so a starved parent measured the chip's 16.dp glyph and 12.dp chevron at zero and the chip
 * rendered as an empty padding box - present, clickable, and completely unreadable.  Being pushed off the
 * edge is legible; shrinking to nothing is not.
 */
class DropdownChipSizingTest {
	/** A chip in a roomy parent and one in a 24.dp parent measure the same. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aHeaderChipKeepsItsWidthInAStarvedParent() {
		assertEquals(chipWidth(parentWidth = 400.dp), chipWidth(parentWidth = 24.dp))
	}

	/** The squeeze also has to survive a parent with literally no room at all. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aHeaderChipKeepsItsWidthWithNoRoomAtAll() {
		assertEquals(chipWidth(parentWidth = 400.dp), chipWidth(parentWidth = 0.dp))
	}

	/**
	 * Composes one labelled Header chip inside a fixed-width parent and returns the width of its painted
	 * face.
	 *
	 * Read off the chip's semantics node, which rides the same modifier chain as its border and fill -
	 * that is the node whose width IS what the user sees.  The chip's outer anchor box reports the
	 * parent's clamp instead, which is the very measurement the fix stops the face from inheriting.
	 *
	 * @param Dp parentWidth The width of the box the chip is squeezed into.
	 * @return Dp The chip face's measured width.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun chipWidth(parentWidth: Dp): Dp {
		var measured = 0.dp
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.width(parentWidth)) {
						DropdownChip(
							expanded = false,
							onExpandRequest = {},
							contentDescription = CHIP_LABEL,
							icon = LocalUmamoIcons.transformPivot,
							label = "Median Point",
						) {}
					}
				}
			}
			waitForIdle()
			measured = onNodeWithContentDescription(CHIP_LABEL, useUnmergedTree = true).getUnclippedBoundsInRoot().width
		}
		return measured
	}

	private companion object {
		/** The chip's accessible name, which is also how the test addresses its painted face. */
		const val CHIP_LABEL = "Pivot"
	}
}