package org.umamo.ui.kit.container

import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertIsNotDisplayed
import androidx.compose.ui.test.assertIsNotSelected
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.UmamoTheme
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The test tag on the box the rail is mounted in. */
private const val RAIL_TAG = "rail"

/** The three tabs most cases mount. */
private val THREE_TABS =
	listOf(
		VerticalTabRailItem("item", "Item"),
		VerticalTabRailItem("tool", "Tool"),
		VerticalTabRailItem("view", "View"),
	)

/** Comfortably past the tooltip's dwell delay. */
private const val TOOLTIP_WAIT_MILLIS = 1_000L

/**
 * The vertical tab rail: clicking a tab selects it without taking the keyboard, a long rail scrolls, a cut
 * label shows whole in a tooltip, and the selected tab meets the content on the side the content is on.
 */
class VerticalTabRailTest {
	/**
	 * Mounts a rail whose selection follows its clicks, at density 1, in a box of [boxHeight].
	 *
	 * @param List tabs The tabs.
	 * @param String initialSelection The tab selected at first.
	 * @param Dp boxHeight The height the rail is given.
	 * @param Dp maxLabelLength The longest a label runs.
	 * @param Function onSelect Also called with each selected id.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.mountRail(
		tabs: List<VerticalTabRailItem> = THREE_TABS,
		initialSelection: String = "item",
		boxHeight: Dp = 300.dp,
		maxLabelLength: Dp = 160.dp,
		onSelect: (String) -> Unit = {},
	) {
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					var selectedId by remember { mutableStateOf(initialSelection) }
					Box(modifier = Modifier.height(boxHeight).testTag(RAIL_TAG)) {
						VerticalTabRail(
							tabs = tabs,
							selectedId = selectedId,
							onSelect = { id ->
								selectedId = id
								onSelect(id)
							},
							contentSide = RailContentSide.Start,
							maxLabelLength = maxLabelLength,
						)
					}
				}
			}
		}
		waitForIdle()
	}

	/** Clicking a tab hands back its id and selects it, and only it. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun clickingATabSelectsIt() =
		runComposeUiTest {
			val selections = ArrayList<String>()
			mountRail(onSelect = { id -> selections.add(id) })
			onNodeWithText("Item").assertIsSelected()
			onNodeWithText("Tool").performClick()
			waitForIdle()
			assertEquals(listOf("tool"), selections)
			onNodeWithText("Tool").assertIsSelected()
			onNodeWithText("Item").assertIsNotSelected()
			onNodeWithText("View").assertIsNotSelected()
		}

	/** Each tab says it is a tab, so assistive technology reads the rail as a tab list. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun eachTabHasTheTabRole() =
		runComposeUiTest {
			mountRail()

			for (tab in THREE_TABS) {
				onNodeWithText(tab.label).assert(SemanticsMatcher.expectValue(SemanticsProperties.Role, Role.Tab))
			}
		}

	/** A tab click leaves the keyboard where it was. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aTabClickLeavesFocusAlone() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					val sentinel = remember { FocusRequester() }
					var selectedId by remember { mutableStateOf("item") }
					Column {
						Box(modifier = Modifier.size(10.dp).focusRequester(sentinel).focusable().testTag("sentinel"))
						VerticalTabRail(
							tabs = THREE_TABS,
							selectedId = selectedId,
							onSelect = { id -> selectedId = id },
							contentSide = RailContentSide.Start,
						)
					}
					LaunchedEffect(Unit) { sentinel.requestFocus() }
				}
			}
			waitForIdle()
			onNodeWithTag("sentinel").assertIsFocused()
			onNodeWithText("View").performClick()
			waitForIdle()
			onNodeWithText("View").assertIsSelected()
			onNodeWithTag("sentinel").assertIsFocused()
		}

	/** A rail longer than its box scrolls under the wheel to reach the tabs past its end. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aLongRailScrolls() =
		runComposeUiTest {
			val sixTabs = listOf("First", "Second", "Third", "Fourth", "Fifth", "Sixth").map { label -> VerticalTabRailItem(label.lowercase(), label) }
			mountRail(tabs = sixTabs, initialSelection = "first", boxHeight = 120.dp)
			onNodeWithText("Sixth").assertIsNotDisplayed()
			onNodeWithTag(RAIL_TAG).performMouseInput {
				moveTo(center)
				scroll(20f)
			}
			waitForIdle()
			onNodeWithText("Sixth").assertIsDisplayed()
		}

	/** A label cut short shows whole in a tooltip; a label that fits shows none. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aCutLabelShowsWholeInATooltip() {
		runComposeUiTest {
			mountRail(tabs = listOf(VerticalTabRailItem("long", "Bone Physics Settings")), initialSelection = "long", maxLabelLength = 40.dp)
			hoverRailCenter()
			onAllNodes(isPopup()).assertCountEquals(1)
		}
		runComposeUiTest {
			mountRail(tabs = listOf(VerticalTabRailItem("view", "View")), initialSelection = "view", maxLabelLength = 40.dp)
			hoverRailCenter()
			onAllNodes(isPopup()).assertCountEquals(0)
		}
	}

	/**
	 * Rests the pointer on the rail's first tab and lets the tooltip delay run out.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.hoverRailCenter() {
		onNodeWithTag(RAIL_TAG).performMouseInput { moveTo(Offset(12f, 20f)) }
		mainClock.advanceTimeBy(TOOLTIP_WAIT_MILLIS)
		waitForIdle()
	}

	/** The selected tab is square where it meets the content and rounded on the outer side, either way round. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theSelectedTabMeetsTheContentSide() {
		for (contentSide in RailContentSide.entries) {
			runComposeUiTest {
				setContent {
					UmamoTheme {
						CompositionLocalProvider(LocalDensity provides Density(1f)) {
							Box(modifier = Modifier.background(Color.Blue).testTag(RAIL_TAG)) {
								VerticalTabRail(
									tabs = THREE_TABS,
									selectedId = "item",
									onSelect = {},
									contentSide = contentSide,
									selectedFill = Color.Red,
								)
							}
						}
					}
				}
				waitForIdle()
				val rail = onNodeWithTag(RAIL_TAG).getUnclippedBoundsInRoot()
				val tab = onNodeWithText("Item").getUnclippedBoundsInRoot()
				val image = onNodeWithTag(RAIL_TAG).captureToImage()
				val pixels = IntArray(image.width * image.height)
				image.readPixels(pixels)

				/**
				 * Whether the pixel at a point in the rail's own px is the selected fill.
				 *
				 * @param Float x The point's x.
				 * @param Float y The point's y.
				 * @return Boolean True for a red pixel.
				 */
				fun isSelectedFill(x: Float, y: Float): Boolean {
					val pixel = pixels[y.toInt() * image.width + x.toInt()]
					val red = (pixel shr 16) and 0xFF
					val blue = pixel and 0xFF

					return red > 200 && blue < 60
				}

				val top = (tab.top - rail.top).value
				val left = (tab.left - rail.left).value
				val right = (tab.right - rail.left).value - 1f
				val contentCornerX = if (contentSide == RailContentSide.Start) left else right
				val outerCornerX = if (contentSide == RailContentSide.Start) right else left
				assertTrue(isSelectedFill(contentCornerX, top), "$contentSide: the corner on the content's side is square")
				assertTrue(!isSelectedFill(outerCornerX, top), "$contentSide: the outer corner is rounded")
			}
		}
	}
}