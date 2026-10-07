package org.umamo.ui.tracks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.MouseButton
import androidx.compose.ui.test.hasAnyAncestor
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isPopup
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.menu.MenuItem
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * A sheet's row views under real input: the chevron's toggle, which rows a fold shows, the label cell's
 * context menu, and the detail line.
 */
@OptIn(ExperimentalTestApi::class)
class TrackSheetRowViewsTest {
	/**
	 * A secondary click at [point] on the sheet, which is what opens a context menu on the desktop.
	 *
	 * @param Offset point Where to click.
	 */
	private fun ComposeUiTest.secondaryClickAt(point: Offset) {
		onNodeWithTag(SHEET_TAG).performMouseInput {
			moveTo(point)
			press(MouseButton.Secondary)
			release(MouseButton.Secondary)
		}
		waitForIdle()
	}

	/**
	 * Whether an open popup shows [label].
	 *
	 * @param String label The text.
	 * @return Boolean True when a popup node shows it.
	 */
	private fun ComposeUiTest.popupShows(label: String): Boolean =
		onAllNodes(hasText(label) and hasAnyAncestor(isPopup()), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

	/** Clicking a group row's chevron reports that row through the toggle callback. */
	@Test
	fun aChevronClickReportsTheRow() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness)
			onNodeWithTag(SHEET_TAG).performMouseInput {
				moveTo(Offset(CHEVRON_X, rowCenterY(0)))
				press()
				release()
			}
			waitForIdle()
			assertEquals(listOf(FixtureRows.OWNER), harness.toggles.map { row -> row.key })
		}

	/** A group's children show only while its key is expanded. */
	@Test
	fun childrenShowOnlyWhileExpanded() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness)
			onNodeWithText("Opacity").assertExists()
			onNodeWithText("Angle").assertExists()

			runOnIdle { harness.expandedKeys = emptySet() }
			waitForIdle()
			onNodeWithText("Owner").assertExists()
			onNodeWithText("Opacity").assertDoesNotExist()
			onNodeWithText("Angle").assertDoesNotExist()
		}

	/** A right-click on a label cell opens the menu built for THAT row. */
	@Test
	fun aLabelRightClickOpensThatRowsMenu() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(harness, labelMenuItems = { row -> listOf(MenuItem.Action(label = "Rename ${row.label}", onSelect = {})) })
			secondaryClickAt(Offset(90f, rowCenterY(1)))
			assertTrue(popupShows("Rename Opacity"), "the Opacity row's menu opens")
			assertTrue(!popupShows("Rename Owner") && !popupShows("Rename Angle"), "and no other row's")
			assertTrue(harness.labelMenuRows.map { row -> row.key }.containsAll(listOf(FixtureRows.OWNER, FixtureRows.OPACITY, FixtureRows.ANGLE)), "the items are built for every row at composition")
		}

	/** A row whose builder offers nothing opens no menu at all. */
	@Test
	fun aRowWithNoLabelItemsOpensNoMenu() =
		runComposeUiTest {
			val harness = TrackSheetHarness()
			mountTrackSheet(
				harness,
				labelMenuItems = { row ->
					if (row.key == FixtureRows.OWNER) emptyList() else listOf(MenuItem.Action(label = "Rename ${row.label}", onSelect = {}))
				},
			)
			secondaryClickAt(Offset(90f, rowCenterY(0)))
			assertTrue(onAllNodes(isPopup()).fetchSemanticsNodes().isEmpty(), "no popup for a row with nothing to offer")
		}

	/** A row's detail line renders under its name. */
	@Test
	fun theDetailLineRenders() =
		runComposeUiTest {
			mountTrackSheet(TrackSheetHarness())
			onNode(hasText("Opacity channel"), useUnmergedTree = true).assertExists()
		}
}