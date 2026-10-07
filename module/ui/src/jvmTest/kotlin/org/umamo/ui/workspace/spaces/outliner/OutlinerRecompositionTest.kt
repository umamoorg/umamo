package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.parameter.renameParameter
import org.umamo.edit.structure.rename
import org.umamo.ui.workspace.spaces.keyformsheet.shiftClickAt
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.clickAt
import org.umamo.ui.workspace.spaces.parameters.moveOn
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.pressKey
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what recomposes the outliner.  A row reads its own hover, so resting on it runs that row's body
 * and nothing around it; and everything the space hands a row compares equal when nothing about that row
 * changed, so a selection made elsewhere runs only the rows whose flags it changed.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class OutlinerRecompositionTest {
	/**
	 * Runs [body] with the outliner's body runs counted, and takes the counter off again whatever happens:
	 * the tracer is one per process.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
		val counter = ComposableRunCounter(OUTLINER_PACKAGE_PREFIX)
		Composer.setTracer(counter)
		try {
			runComposeUiTest { body(counter) }
		} finally {
			Composer.setTracer(null)
		}
	}

	/** The counter has to see the outliner compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheOutlinerCompose() =
		counting { counter ->
			mountOutliner()

			assertTrue(counter.runsOf("OutlinerSpace") >= 1)
			assertEquals(OPEN_ROWS, counter.runsOf("OutlinerRowView"), "one per row the list shows")
			assertEquals(OPEN_ROWS, counter.runsOf("OutlinerRowBody"))
		}

	/** Resting on a row runs that row's body once, and neither its frame, its slots, nor the space. */
	@Test
	fun aHoverRunsOnlyTheHoveredRowsBody() =
		counting { counter ->
			mountOutliner()
			counter.reset()

			hoverAt(rowBox(OutlinerNames.HEAD).center)

			assertEquals(1, counter.runsOf("OutlinerRowBody"), "the hovered row must really have recomposed")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
			assertEquals(0, counter.runsOf("OutlinerSpace"))
			assertEquals(0, counter.runsOf("ChevronSlot"))
			assertEquals(0, counter.runsOf("OutlinerIconSlot"))
		}

	/**
	 * A selection made elsewhere runs the selected row and the part folder that now holds the selection, and
	 * no other.  The reveal that follows opens only what is closed, so with the row's branches open it writes
	 * no fold and the rows are not built again: what runs is what the selection changed.
	 */
	@Test
	fun anOutsideSelectionRunsOnlyTheRowsWhoseFlagsChanged() =
		counting { counter ->
			val harness = mountOutliner()
			clickAt(slotPoint(harness.text.expand, OutlinerNames.HEAD))
			counter.reset()

			runOnIdle {
				val eye = SelectionTarget.Drawable(OutlinerIds.eye)
				harness.session.setSelection(Selection(setOf(eye), eye))
			}
			waitForIdle()

			assertTrue(counter.runsOf("OutlinerSpace") >= 1, "the space must really have recomposed")
			assertEquals(2, counter.runsOf("OutlinerRowView"), "the selected row and its folder")
			assertEquals(2, counter.runsOf("OutlinerRowBody"))
			assertEquals(mapOf(OutlinerRowKeys.HEAD to true), harness.outlinerViewState.expanded.toMap(), "the reveal wrote no fold")
		}

	/**
	 * Opening a branch runs the branch's own row and the rows it brings in, and none of the rows above it.
	 * The rows are built again for every fold, and a row built again for the same node equals the one
	 * before it.
	 */
	@Test
	fun aFoldRunsTheFoldedRowAndTheRowsItShows() =
		counting { counter ->
			val harness = mountOutliner()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.LIMBS) }
			waitForIdle()

			assertTrue(outlinerShows(OutlinerNames.CHEST), "the branch must really have opened")
			assertEquals(3, counter.runsOf("OutlinerRowView"), "the last branch's row and the two rows it shows")
			assertEquals(3, counter.runsOf("OutlinerRowBody"))
		}

	/**
	 * The rows under an opened branch move down the list and are not composed again for it.  A row reads
	 * its place while it draws its guides and when it is clicked, so a new place is a new drawing and
	 * nothing more.
	 */
	@Test
	fun aFoldRunsNoRowItOnlyMoves() =
		counting { counter ->
			val harness = mountOutliner()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.HEAD) }
			waitForIdle()

			assertTrue(outlinerShows(OutlinerNames.EYE), "the branch must really have opened")
			assertEquals(3, counter.runsOf("OutlinerRowView"), "the branch's row and the two rows it shows; the two it moved down skip")
			assertEquals(3, counter.runsOf("OutlinerRowBody"))
		}

	/** Closing a branch in the middle of the list runs that branch's row alone: the rows it moves up skip. */
	@Test
	fun closingABranchInTheMiddleRunsItsRowAlone() =
		counting { counter ->
			val harness = mountOutliner()
			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.HEAD) }
			waitForIdle()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.HEAD) }
			waitForIdle()

			assertEquals(1, counter.runsOf("OutlinerRowView"))
		}

	/** A row a fold moved selects by its new place: a Shift click ranges over the rows as they now stand. */
	@Test
	fun aMovedRowSelectsByItsNewPlace() =
		counting { _ ->
			val harness = mountOutliner()
			clickAt(rowBox(OutlinerNames.HEAD).center)
			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.HEAD) }
			waitForIdle()

			shiftClickAt(rowBox(OutlinerNames.LIMBS).center)

			assertEquals(
				setOf(
					SelectionTarget.Part(OutlinerIds.head),
					SelectionTarget.Drawable(OutlinerIds.eye),
					SelectionTarget.Part(OutlinerIds.hair),
					SelectionTarget.Drawable(OutlinerIds.loose),
					SelectionTarget.Part(OutlinerIds.limbs),
				),
				harness.session.selection.value.targets,
			)
		}

	/** Closing a branch at the end of the list runs that branch's row alone. */
	@Test
	fun closingTheLastBranchRunsItsRowAlone() =
		counting { counter ->
			val harness = mountOutliner()
			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.LIMBS) }
			waitForIdle()
			counter.reset()

			runOnIdle { harness.outlinerViewState.toggleFold(OutlinerRowKeys.LIMBS) }
			waitForIdle()

			assertEquals(1, counter.runsOf("OutlinerRowView"))
		}

	/** A rested hover pops the row's art preview through the space, and runs no other row for it. */
	@Test
	fun aRestedHoverPopsThePreviewWithoutRunningOtherRows() =
		counting { counter ->
			mountOutliner(thumbnails = StubThumbnails)
			counter.reset()

			hoverAt(rowBox(OutlinerNames.HEAD).center)

			assertTrue(popupShows(OutlinerNames.HEAD), "the preview must really have popped")
			assertTrue(counter.runsOf("OutlinerSpace") >= 1, "the space draws the preview")
			assertEquals(1, counter.runsOf("OutlinerRowBody"), "only the hovered row")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
		}

	/** An edit that changes nothing the outliner shows runs the space, which is handed the new model, and no row. */
	@Test
	fun anEditTheOutlinerDoesNotShowRunsNoRow() =
		counting { counter ->
			val harness = mountOutliner()
			counter.reset()

			runOnIdle { harness.session.renameParameter(PanelIds.breath, RENAMED) }
			waitForIdle()

			assertTrue(counter.runsOf("OutlinerSpace") >= 1, "the space must really have been handed the model")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
			assertEquals(0, counter.runsOf("OutlinerRowBody"))
		}

	/**
	 * An edit to one row runs it and the root over it, which holds it, and no row beside it.  The new name
	 * is no longer than the old, so the rows are no wider for it.
	 */
	@Test
	fun anEditToOneRowRunsItAndTheRowOverIt() =
		counting { counter ->
			val harness = mountOutliner()
			counter.reset()

			runOnIdle { harness.session.rename(SelectionTarget.Drawable(OutlinerIds.loose), SHORTER_NAME) }
			waitForIdle()

			assertTrue(outlinerShows(SHORTER_NAME), "the edit must really have reached the outliner")
			assertEquals(2, counter.runsOf("OutlinerRowView"), "the renamed row and the root over it")
			assertEquals(2, counter.runsOf("OutlinerRowBody"))
		}

	/**
	 * Picks the Loose row up and carries it to [target], leaving the button held.  The row is selected
	 * first, as a press would, and the drag has started by the time it arrives: the press's own hover
	 * and the pick-up are over before anything is counted.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Offset target Where the drag rests, in the panel body's pixels.
	 */
	private fun carryLooseTo(test: ComposeUiTest, target: Offset) {
		val loose = test.rowBox(OutlinerNames.LOOSE).center
		test.clickAt(loose)
		test.longPressAndMove(loose, listOf(Offset(loose.x + 4f, loose.y + 6f), target))
		assertTrue(test.popupShows(OutlinerNames.LOOSE), "the drag must really be in flight")
	}

	/** A pointer moving inside one band of one row changes no row's part in the drag, and runs nothing at all. */
	@Test
	fun aDragMovingInsideOneBandRunsNothing() =
		counting { counter ->
			mountOutliner()
			carryLooseTo(this, rowBandPoint(OutlinerNames.HEAD, 0.45f))
			counter.reset()

			moveOn(listOf(rowBandPoint(OutlinerNames.HEAD, 0.5f), rowBandPoint(OutlinerNames.HEAD, 0.55f), rowBandPoint(OutlinerNames.HEAD, 0.6f)))

			assertEquals(emptyMap(), counter.namedRuns())
			assertEquals(0, counter.lambdaRuns())
			pressKey(Key.Escape)
			releasePress()
		}

	/** A pointer crossing from one band of a row to another runs that row's body, which draws the band. */
	@Test
	fun aDragCrossingABandRunsTheTargetRowAlone() =
		counting { counter ->
			mountOutliner()
			carryLooseTo(this, rowBandPoint(OutlinerNames.HEAD, 0.5f))
			counter.reset()

			moveOn(listOf(rowBandPoint(OutlinerNames.HEAD, 0.15f)))

			assertEquals(1, counter.runsOf("OutlinerRowBody"), "the nest ring gives way to the line above")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
			assertEquals(0, counter.runsOf("OutlinerSpace"))
			pressKey(Key.Escape)
			releasePress()
		}

	/** A pointer crossing from one row to another runs the row it left and the row it reached. */
	@Test
	fun aDragCrossingToAnotherRowRunsTheTwoRows() =
		counting { counter ->
			mountOutliner()
			carryLooseTo(this, rowBandPoint(OutlinerNames.HEAD, 0.5f))
			counter.reset()

			moveOn(listOf(rowBandPoint(OutlinerNames.LIMBS, 0.5f)))

			assertEquals(2, counter.runsOf("OutlinerRowBody"), "the row the target left and the row it reached")
			assertEquals(0, counter.runsOf("OutlinerRowView"))
			assertEquals(0, counter.runsOf("OutlinerSpace"))
			pressKey(Key.Escape)
			releasePress()
		}

	private companion object {
		const val OUTLINER_PACKAGE_PREFIX = "org.umamo.ui.workspace.spaces.outliner."

		/** A name no row of the rig carries. */
		const val RENAMED = "Renamed"

		/** A name for the Loose row no longer than its own. */
		const val SHORTER_NAME = "Lost"

		/** The rows the list shows as the fixture opens: the root and its four children. */
		const val OPEN_ROWS = 5
	}
}