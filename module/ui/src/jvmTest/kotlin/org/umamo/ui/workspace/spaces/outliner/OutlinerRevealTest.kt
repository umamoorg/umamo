package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.ui.workspace.PickKind
import org.umamo.ui.workspace.spaces.parameters.PANEL_OUTLINER_HEIGHT_SCROLLING
import org.umamo.ui.workspace.spaces.parameters.clickAt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins reveal-on-select: a selection made elsewhere opens the row's ancestors and scrolls to it, a click
 * inside the outliner never scrolls the list, and clearing a search reveals the active row again so a row
 * picked out of the results is not stranded behind a branch that closes.
 *
 * Every case mounts the outliner short enough that revealing a row deep in the tree has to scroll, or the
 * scroll half of the reveal would pass for nothing.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerRevealTest {
	private val bang = SelectionTarget.Drawable(OutlinerIds.bang)

	/** A selection from outside opens every ancestor of the row and brings it into view. */
	@Test
	fun aSelectionFromOutsideRevealsItsRow() =
		runComposeUiTest {
			val harness = mountOutliner(height = PANEL_OUTLINER_HEIGHT_SCROLLING)
			assertFalse(outlinerShows(OutlinerNames.BANG), "the row starts under two closed branches")

			runOnIdle { harness.session.setSelection(Selection(setOf(bang), bang)) }
			waitForIdle()

			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD))
			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HAIR))
			assertTrue(outlinerDisplays(OutlinerNames.BANG))
			assertFalse(outlinerDisplays(harness.text.outlinerRoot), "the list scrolled to reach the row")
			assertEquals(
				mapOf(OutlinerRowKeys.HEAD to true, OutlinerRowKeys.HAIR to true),
				harness.outlinerViewState.expanded.toMap(),
				"only the closed branches had a fold written",
			)
		}

	/**
	 * A relation pick resolved from a row changes no selection, so it leaves the reveal as it was: the
	 * next selection made elsewhere is revealed.
	 */
	@Test
	fun aSelectionFromOutsideIsRevealedAfterAResolvedPick() =
		runComposeUiTest {
			val harness = mountOutliner(height = PANEL_OUTLINER_HEIGHT_SCROLLING)
			runOnIdle { harness.relationPick.arm(setOf(PickKind.Part)) {} }
			clickAt(rowBox(OutlinerNames.HEAD).center)
			assertTrue(harness.session.selection.value.isEmpty, "the pick took the click")

			runOnIdle { harness.session.setSelection(Selection(setOf(bang), bang)) }
			waitForIdle()

			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HAIR))
			assertTrue(outlinerDisplays(OutlinerNames.BANG))
		}

	/**
	 * A click on the row that is active already changes no selection either, and the next selection made
	 * elsewhere is revealed.
	 */
	@Test
	fun aSelectionFromOutsideIsRevealedAfterAClickOnTheActiveRow() =
		runComposeUiTest {
			val harness = mountOutliner(height = PANEL_OUTLINER_HEIGHT_SCROLLING)
			clickAt(rowBox(OutlinerNames.HEAD).center)
			clickAt(rowBox(OutlinerNames.HEAD).center)

			runOnIdle { harness.session.setSelection(Selection(setOf(bang), bang)) }
			waitForIdle()

			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HAIR))
			assertTrue(outlinerDisplays(OutlinerNames.BANG))
		}

	/** A reveal of a row whose branches are all open records no fold: the root is open by default. */
	@Test
	fun aRevealRecordsNoFoldForAnOpenBranch() =
		runComposeUiTest {
			val harness = mountOutliner(height = PANEL_OUTLINER_HEIGHT_SCROLLING)
			val loose = SelectionTarget.Drawable(OutlinerIds.loose)

			runOnIdle { harness.session.setSelection(Selection(setOf(loose), loose)) }
			waitForIdle()

			assertTrue(harness.outlinerViewState.expanded.isEmpty())
			assertTrue(outlinerDisplays(OutlinerNames.LOOSE))
		}

	/** A click inside the outliner selects without scrolling, so the list does not jump under the pointer. */
	@Test
	fun aClickInsideDoesNotScroll() =
		runComposeUiTest {
			val harness = mountOutliner(height = PANEL_OUTLINER_HEIGHT_SCROLLING)
			runOnIdle {
				harness.outlinerViewState.expanded[OutlinerRowKeys.HEAD] = true
				harness.outlinerViewState.expanded[OutlinerRowKeys.HAIR] = true
			}
			waitForIdle()
			assertTrue(outlinerDisplays(OutlinerNames.HAIR), "the clicked row is the last one on screen")

			clickAt(rowBox(OutlinerNames.HAIR).center)

			assertTrue(SelectionTarget.Part(OutlinerIds.hair) in harness.session.selection.value.targets)
			assertTrue(outlinerDisplays(harness.text.outlinerRoot), "the list held its place")
		}

	/** Clearing the search reveals the active row, whose branches would otherwise close over it. */
	@Test
	fun clearingTheSearchRevealsTheActiveRow() =
		runComposeUiTest {
			val harness = mountOutliner(height = PANEL_OUTLINER_HEIGHT_SCROLLING)
			runOnIdle { harness.outlinerViewState.query = OutlinerNames.BANG }
			waitForIdle()
			clickAt(rowBox(OutlinerNames.BANG).center)
			assertFalse(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD), "a search opens branches without writing folds")

			runOnIdle { harness.outlinerViewState.query = "" }
			waitForIdle()

			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HEAD))
			assertTrue(harness.outlinerViewState.isOpen(OutlinerRowKeys.HAIR))
			assertTrue(outlinerDisplays(OutlinerNames.BANG))
		}
}