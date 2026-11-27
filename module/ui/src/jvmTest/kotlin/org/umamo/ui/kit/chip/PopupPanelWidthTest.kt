package org.umamo.ui.kit.chip

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * A panel with a width floor opens at the floor while its rows fit inside it, and widens past it to fit a
 * row that needs more: a two-column row whose half is wider than half the floor widens the panel to twice
 * that half, as a longer locale's label must, rather than being cut.
 */
@OptIn(ExperimentalTestApi::class)
class PopupPanelWidthTest {
	/** Rows narrower than the floor leave the panel at the floor. */
	@Test
	fun narrowRowsOpenThePanelAtItsFloor() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					PopupPanel(onDismissRequest = {}, minContentWidth = FLOOR) {
						Text(text = "Row")
						Box(modifier = Modifier.fillMaxWidth().height(1.dp).testTag(SPAN))
					}
				}
			}
			waitForIdle()

			assertEquals(pixels(FLOOR), spanWidth(), "the panel is the floor's width")
		}

	/** A two-column row whose half needs more than half the floor widens the panel to twice that half. */
	@Test
	fun aRowWiderThanTheFloorWidensThePanel() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					PopupPanel(onDismissRequest = {}, minContentWidth = FLOOR) {
						Row(modifier = Modifier.fillMaxWidth()) {
							Box(modifier = Modifier.weight(1f))
							Box(modifier = Modifier.weight(1f)) {
								Box(modifier = Modifier.width(WIDE_HALF).height(1.dp))
							}
						}
						Box(modifier = Modifier.fillMaxWidth().height(1.dp).testTag(SPAN))
					}
				}
			}
			waitForIdle()

			assertEquals(pixels(WIDE_HALF * 2), spanWidth(), "the panel is twice the demanding half, past the floor")
		}

	/**
	 * The width of the full-width marker row, which spans the panel's content.
	 *
	 * @return Int The width in pixels.
	 */
	private fun ComposeUiTest.spanWidth(): Int = onNode(hasTestTag(SPAN), useUnmergedTree = true).fetchSemanticsNode().size.width

	/**
	 * A width in this test's pixels.
	 *
	 * @param Dp width The width.
	 * @return Int The width in pixels.
	 */
	private fun ComposeUiTest.pixels(width: Dp): Int = with(density) { width.roundToPx() }

	private companion object {
		/** The panel's width floor. */
		val FLOOR = 200.dp

		/** A row half wider than half the floor. */
		val WIDE_HALF = 150.dp

		/** The test tag of the full-width marker row. */
		const val SPAN = "span"
	}
}