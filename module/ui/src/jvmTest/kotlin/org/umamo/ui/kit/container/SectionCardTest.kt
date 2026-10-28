package org.umamo.ui.kit.container

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals

/** The section heading every case mounts. */
private const val TITLE = "Transform"

/** The test tag on the card. */
private const val CARD_TAG = "card"

/** The test tag on the first row. */
private const val FIRST_ROW_TAG = "firstRow"

/** The test tag on the second row. */
private const val SECOND_ROW_TAG = "secondRow"

/**
 * The kit section card: its header folds the rows away, open rows sit at the section indent the
 * Properties panel, the sidebar, and the operation strip draw them at, and a caller can size it itself.
 */
class SectionCardTest {
	/**
	 * Mounts one 300 dp wide card at density 1 holding two 10 dp rows, opening and folding through its header.
	 *
	 * @param Boolean initiallyExpanded Whether the card starts open.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountCard(initiallyExpanded: Boolean) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					var expanded by remember { mutableStateOf(initiallyExpanded) }
					Box(modifier = Modifier.width(300.dp)) {
						SectionCard(
							title = TITLE,
							expanded = expanded,
							onToggle = { expanded = !expanded },
							modifier = Modifier.testTag(CARD_TAG),
						) {
							Box(modifier = Modifier.fillMaxWidth().height(10.dp).testTag(FIRST_ROW_TAG))
							Box(modifier = Modifier.fillMaxWidth().height(10.dp).testTag(SECOND_ROW_TAG))
						}
					}
				}
			}
		}
		waitForIdle()
	}

	/** A folded card shows its heading and nothing else, at the header's own height. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aFoldedCardShowsOnlyItsHeader() =
		runComposeUiTest {
			mountCard(initiallyExpanded = false)
			onNodeWithText(TITLE).assertExists()
			onNodeWithTag(FIRST_ROW_TAG).assertDoesNotExist()
			val card = onNodeWithTag(CARD_TAG).getUnclippedBoundsInRoot()
			assertEquals(22.dp, card.bottom - card.top, "a folded card is its header")
		}

	/** Clicking the header opens a folded card and folds it again. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theHeaderOpensAndFoldsTheCard() =
		runComposeUiTest {
			mountCard(initiallyExpanded = false)
			onNodeWithText(TITLE).performClick()
			waitForIdle()
			onNodeWithTag(FIRST_ROW_TAG).assertExists()
			onNodeWithText(TITLE).performClick()
			waitForIdle()
			onNodeWithTag(FIRST_ROW_TAG).assertDoesNotExist()
		}

	/** Open rows sit below the header at the section indent, inset on both sides, a small gap apart. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun openRowsSitAtTheSectionIndent() =
		runComposeUiTest {
			mountCard(initiallyExpanded = true)
			val first = onNodeWithTag(FIRST_ROW_TAG).getUnclippedBoundsInRoot()
			val second = onNodeWithTag(SECOND_ROW_TAG).getUnclippedBoundsInRoot()
			assertEquals(12.dp, first.left, "rows start at the section indent")
			assertEquals(24.dp, first.top, "rows start just below the 22 dp header")
			assertEquals(280.dp, first.right - first.left, "rows keep 12 dp on the left and 8 dp on the right")
			assertEquals(4.dp, second.top - first.bottom, "rows are 4 dp apart")
			val card = onNodeWithTag(CARD_TAG).getUnclippedBoundsInRoot()
			assertEquals(56.dp, card.bottom - card.top, "header 22, 2 above the rows, two 10 dp rows 4 apart, 8 below")
		}

	/**
	 * A card its caller sizes keeps that size instead of filling what it is offered: sized to its widest row,
	 * as the operation strip sizes it, it is that row plus the section indent on either side.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aCardSizedByItsCallerKeepsThatSize() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalDensity provides Density(1f)) {
						Box(modifier = Modifier.width(600.dp)) {
							SectionCard(
								title = TITLE,
								expanded = true,
								onToggle = {},
								modifier = Modifier.widthIn(min = 100.dp).width(IntrinsicSize.Max).testTag(CARD_TAG),
							) {
								Box(modifier = Modifier.width(200.dp).height(10.dp).testTag(FIRST_ROW_TAG))
							}
						}
					}
				}
			}
			waitForIdle()
			val card = onNodeWithTag(CARD_TAG).getUnclippedBoundsInRoot()
			assertEquals(220.dp, card.right - card.left, "a 200 dp row, 12 dp of indent before it and 8 dp after")
		}
}