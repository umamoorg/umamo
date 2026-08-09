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
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.width
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The clear affordance takes its width out of the text's rather than sitting on top of it.
 *
 * The bug this pins had the X overlaid at the field's trailing edge, so a long enough value ran straight
 * underneath the glyph - and because a single-line BasicTextField scrolls the caret into view, the caret
 * went under it too, leaving the user typing blind at the end of a long query.  The fix is a reserved
 * trailing slot in the decoration Row, so the assertion is on the geometry the reservation produces.
 */
class SearchFieldTest {
	/**
	 * A trailing control shrinks the text viewport by at least its own width.
	 *
	 * Measured off the placeholder, which is the one node that fills the text viewport and is addressable
	 * from a test.  An overlaid control would leave the viewport untouched, which is exactly the bug.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aTrailingControlReservesItsWidthFromTheText() {
		val withoutTrailing = placeholderWidth(trailingSize = null)
		val withTrailing = placeholderWidth(trailingSize = TRAILING_SIZE)
		assertTrue(
			withoutTrailing - withTrailing >= TRAILING_SIZE,
			"the trailing slot must take its width out of the text viewport ($withoutTrailing -> $withTrailing)",
		)
	}

	/** An empty field shows no clear affordance at all. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun anEmptyFieldShowsNoClearButton() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					SearchField(value = "", onValueChange = {})
				}
			}
			waitForIdle()
			onNodeWithContentDescription(CLEAR_LABEL, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** Clicking the X empties the query and takes the button away with it. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun clickingClearEmptiesTheQuery() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					var query by remember { mutableStateOf(LONG_QUERY) }
					SearchField(value = query, onValueChange = { edited -> query = edited })
				}
			}
			waitForIdle()
			onNodeWithContentDescription(CLEAR_LABEL, useUnmergedTree = true).assertIsDisplayed()
			onNodeWithContentDescription(CLEAR_LABEL, useUnmergedTree = true).performClick()
			waitForIdle()
			onNodeWithContentDescription(CLEAR_LABEL, useUnmergedTree = true).assertDoesNotExist()
		}
	}

	/** The clear button stays inside the field it belongs to. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theClearButtonSitsInsideTheField() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.testTag(FIELD_TAG)) {
						SearchField(value = LONG_QUERY, onValueChange = {})
					}
				}
			}
			waitForIdle()
			val fieldBounds = onNodeWithTag(FIELD_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot()
			val clearBounds = onNodeWithContentDescription(CLEAR_LABEL, useUnmergedTree = true).getUnclippedBoundsInRoot()
			assertTrue(clearBounds.left >= fieldBounds.left, "the clear button escaped the field's left edge")
			assertTrue(clearBounds.right <= fieldBounds.right, "the clear button escaped the field's right edge")
		}
	}

	/** The field keeps the kit's standard header width unless a caller overrides it. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theFieldTakesTheStandardHeaderWidth() {
		runComposeUiTest {
			setContent {
				UmamoTheme {
					Box(modifier = Modifier.testTag(FIELD_TAG)) {
						SearchField(value = "", onValueChange = {})
					}
				}
			}
			waitForIdle()
			assertEquals(SEARCH_FIELD_WIDTH, onNodeWithTag(FIELD_TAG, useUnmergedTree = true).getUnclippedBoundsInRoot().width)
		}
	}

	/**
	 * Composes one fixed-width [TextField] and returns how wide its placeholder was allowed to be - the
	 * width of the text viewport, since the placeholder fills the weighted box the editable text uses.
	 *
	 * @param Dp? trailingSize The trailing control's square size, or null for no trailing slot.
	 * @return Dp The placeholder's measured width.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun placeholderWidth(trailingSize: Dp?): Dp {
		var measured = 0.dp
		runComposeUiTest {
			setContent {
				UmamoTheme {
					TextField(
						value = "",
						onValueChange = {},
						modifier = Modifier.size(width = SEARCH_FIELD_WIDTH, height = 24.dp),
						// A single unbroken run, so it cannot wrap at a space and the line fills the viewport.
						placeholder = LONG_QUERY,
						trailing =
							if (trailingSize == null) {
								null
							} else {
								{ Box(modifier = Modifier.size(trailingSize)) }
							},
					)
				}
			}
			waitForIdle()
			measured = onNodeWithText(LONG_QUERY, useUnmergedTree = true).getUnclippedBoundsInRoot().width
		}
		return measured
	}

	private companion object {
		/** Longer than the 160.dp field, so the value genuinely reaches the trailing edge. */
		const val LONG_QUERY = "asdasdasdasdasdasdasdasdasdasdasd"

		/** The clear button's English name; it doubles as its accessible label. */
		const val CLEAR_LABEL = "Clear search"

		const val FIELD_TAG = "search"

		/** Wide enough that the reservation is unmistakable against layout rounding. */
		val TRAILING_SIZE = 40.dp
	}
}