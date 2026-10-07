package org.umamo.ui.workspace.spaces.sources

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import kotlinx.coroutines.CompletableDeferred
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.parameter.renameParameter
import org.umamo.edit.structure.rename
import org.umamo.reimport.LayerMatch
import org.umamo.reimport.MatchSignals
import org.umamo.ui.workspace.spaces.outliner.hoverAt
import org.umamo.ui.workspace.spaces.outliner.longPressAndMove
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import org.umamo.ui.workspace.spaces.parameters.moveOn
import org.umamo.ui.workspace.spaces.parameters.popupShows
import org.umamo.ui.workspace.spaces.parameters.pressKey
import org.umamo.ui.workspace.spaces.parameters.releasePress
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what recomposes the Sources table.  A change that touches one row runs that row and no other, and
 * a change the table reads but no row shows runs the space and no row at all.  An edit to the model is
 * such a change like any other: the space is handed the new model, builds its tree again, and hands each
 * row a node equal to the last wherever the edit changed nothing the row shows.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class SourcesRecompositionTest {
	/**
	 * Runs [body] with the table's body runs counted, and takes the counter off again whatever happens:
	 * the tracer is one per process.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
		val counter = ComposableRunCounter(SOURCES_PACKAGE_PREFIX)
		Composer.setTracer(counter)
		try {
			runComposeUiTest { body(counter) }
		} finally {
			Composer.setTracer(null)
		}
	}

	/** The counter has to see the table compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheTableCompose() =
		counting { counter ->
			mountSources()

			assertTrue(counter.runsOf("SourcesSpace") >= 1)
			assertEquals(SOURCES_OPEN_ROWS, counter.runsOf("SourcesRowView"), "one per row the table shows")
			assertEquals(SOURCES_OPEN_ROWS, counter.runsOf("SourcesRowBody"))
		}

	/** Resting on a row runs that row's body once, and neither its frame, another row, nor the space. */
	@Test
	fun aHoverRunsOnlyTheHoveredRowsBody() =
		counting { counter ->
			mountSources()
			counter.reset()

			hoverAt(sourcesRowBox(SourcesNames.SKETCH).center)

			assertEquals(1, counter.runsOf("SourcesRowBody"), "the hovered row must really have recomposed")
			assertEquals(0, counter.runsOf("SourcesRowView"), "the frame holds the hover and does not read it")
			assertEquals(0, counter.runsOf("SourcesSpace"), "nothing can preview, so the space hears nothing")
		}

	/**
	 * Opening a row runs that row and the rows it brings in, and none of the rows around it.  The rows are
	 * built again for every fold, and a row built again for the same node equals the one before it.
	 */
	@Test
	fun aFoldRunsTheFoldedRowAndTheRowsItShows() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			openRow(harness, SourcesRowKeys.HAIR)

			assertTrue(sourcesShows(SourcesNames.HAIR_ART), "the row must really have opened")
			assertEquals(2, counter.runsOf("SourcesRowView"), "the layer and the one tile under it")
			assertEquals(2, counter.runsOf("SourcesRowBody"))
		}

	/** Closing a row runs that row alone: the rows it moves up skip. */
	@Test
	fun closingARowRunsItAlone() =
		counting { counter ->
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			counter.reset()

			runOnIdle { harness.sourcesViewState.expanded[SourcesRowKeys.HAIR] = false }
			waitForIdle()

			assertTrue(!sourcesShows(SourcesNames.HAIR_ART), "the row must really have closed")
			assertEquals(1, counter.runsOf("SourcesRowView"))
			assertEquals(1, counter.runsOf("SourcesRowBody"))
		}

	/**
	 * A proposal published for one lost layer runs that layer's row and the file's row over it, which holds
	 * it, and no other: a tree built again is equal to the last wherever nothing changed.
	 */
	@Test
	fun aPublishedProposalRunsTheRowItChangesAndTheFileOverIt() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			runOnIdle {
				harness.publishedSuggestions.value =
					mapOf((SourcesIds.body to SourcesKeys.BROW_OLD) to LayerMatch(SourcesKeys.BROW, WEAKER_SCORE, MatchSignals(1f, 1f, 1f, 1f, null, hashEqual = false)))
			}
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have recomposed")
			assertEquals(2, counter.runsOf("SourcesRowView"))
			assertEquals(2, counter.runsOf("SourcesRowBody"))
		}

	/** A rested hover pops the row's art preview through the space, and runs no other row for it. */
	@Test
	fun aRestedHoverPopsThePreviewWithoutRunningOtherRows() =
		counting { counter ->
			mountSources(sourceArt = sourcesFixtureArt())
			counter.reset()

			restAt(sourcesRowBox(SourcesNames.LOOSE_ART).center)

			assertTrue(popupShows(SourcesNames.LOOSE_ART), "the preview must really have popped")
			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space draws the preview")
			assertEquals(1, counter.runsOf("SourcesRowBody"), "only the hovered row")
			assertEquals(0, counter.runsOf("SourcesRowView"))
		}

	/** A selection made elsewhere runs the row whose drawable it selected, and no other. */
	@Test
	fun anOutsideSelectionRunsOnlyTheRowItSelected() =
		counting { counter ->
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			openRow(harness, SourcesRowKeys.HAIR_ART)
			counter.reset()

			runOnIdle {
				val shadow = SelectionTarget.Drawable(SourcesIds.hairShadow)
				harness.session.setSelection(Selection(setOf(shadow), shadow))
			}
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have recomposed")
			assertEquals(1, counter.runsOf("SourcesRowView"))
			assertEquals(1, counter.runsOf("SourcesRowBody"))
		}

	/**
	 * The answers landing run the rows of the two files, whose status they are, and no row under either.
	 * The rig's own probe answers before the list first measures, so its table composes once; a probe that
	 * takes its time, as the disk does, is what shows the second pass.
	 */
	@Test
	fun theAnswersLandingRunOnlyTheFilesRows() =
		counting { counter ->
			val gate = CompletableDeferred<Unit>()
			val harness = mountSources(presenceGate = gate)
			counter.reset()

			runOnIdle { gate.complete(Unit) }
			waitForIdle()

			assertTrue(sourcesRowHasSlot(harness.text.sourcesMissing, SourcesNames.FACE), "the answers must really have landed")
			assertEquals(2, counter.runsOf("SourcesRowView"), "Body.psd and Face.clip")
			assertEquals(2, counter.runsOf("SourcesRowBody"))
		}

	/** An edit that changes nothing the table shows runs the space, which is handed the new model, and no row. */
	@Test
	fun anEditTheTableDoesNotShowRunsNoRow() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			runOnIdle { harness.session.renameParameter(PanelIds.breath, RENAMED) }
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have been handed the model")
			assertEquals(0, counter.runsOf("SourcesRowView"))
			assertEquals(0, counter.runsOf("SourcesRowBody"))
		}

	/** An edit to one drawable runs its row and the rows over it, each of which holds the row under it. */
	@Test
	fun anEditToOneDrawableRunsItsRowAndTheRowsOverIt() =
		counting { counter ->
			val harness = mountSources()
			openRow(harness, SourcesRowKeys.HAIR)
			openRow(harness, SourcesRowKeys.HAIR_ART)
			counter.reset()

			runOnIdle { harness.session.rename(SelectionTarget.Drawable(SourcesIds.hairMesh), RENAMED) }
			waitForIdle()

			assertTrue(sourcesShows(RENAMED), "the edit must really have reached the table")
			assertEquals(4, counter.runsOf("SourcesRowView"), "the drawable, its tile, its layer, and its file")
			assertEquals(4, counter.runsOf("SourcesRowBody"))
		}

	/** The watcher's serial makes the space ask about every file again; answers that did not change run no row. */
	@Test
	fun aProbeThatChangesNothingRunsNoRow() =
		counting { counter ->
			val harness = mountSources()
			counter.reset()

			runOnIdle { harness.sourceWatchSerial.value += 1 }
			waitForIdle()

			assertTrue(counter.runsOf("SourcesSpace") >= 1, "the space must really have recomposed")
			assertEquals(0, counter.runsOf("SourcesRowView"))
			assertEquals(0, counter.runsOf("SourcesRowBody"))
		}

	/**
	 * Picks the loose tile's row up and carries it to [target], leaving the button held.  The drag has
	 * started by the time it arrives: the press's own hover and the pick-up are over before anything is
	 * counted.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param Offset target Where the drag rests, in the panel body's pixels.
	 */
	private fun carryLooseArtTo(test: ComposeUiTest, target: Offset) {
		val loose = test.sourcesRowBox(SourcesNames.LOOSE_ART).center
		test.longPressAndMove(loose, listOf(Offset(loose.x + 4f, loose.y + 6f), target))
		assertTrue(test.popupShows(SourcesNames.LOOSE_ART), "the drag must really be in flight")
	}

	/** A pointer moving over one row changes no row's part in the drag, and runs nothing at all. */
	@Test
	fun aDragMovingOverOneRowRunsNothing() =
		counting { counter ->
			mountSources()
			val sketch = sourcesRowBox(SourcesNames.SKETCH)
			carryLooseArtTo(this, sketch.at(0.5f, 0.3f))
			counter.reset()

			moveOn(listOf(sketch.at(0.5f, 0.5f), sketch.at(0.6f, 0.7f), sketch.at(0.4f, 0.4f)))

			assertEquals(emptyMap(), counter.namedRuns())
			assertEquals(0, counter.lambdaRuns())
			pressKey(Key.Escape)
			releasePress()
		}

	/** A pointer crossing from one layer to another runs the row it left and the row it reached. */
	@Test
	fun aDragCrossingToAnotherRowRunsTheTwoRows() =
		counting { counter ->
			mountSources()
			carryLooseArtTo(this, sourcesRowBox(SourcesNames.SKETCH).center)
			counter.reset()

			moveOn(listOf(sourcesRowBox(SourcesNames.EYE).center))

			assertEquals(2, counter.runsOf("SourcesRowBody"), "the row the ring left and the row it reached")
			assertEquals(0, counter.runsOf("SourcesRowView"))
			assertEquals(0, counter.runsOf("SourcesSpace"))
			pressKey(Key.Escape)
			releasePress()
		}

	/** A row that takes no drop plays no part in the drag: reaching it runs only the row the ring left. */
	@Test
	fun aDragReachingARowThatTakesNoDropRunsTheRowItLeft() =
		counting { counter ->
			mountSources()
			carryLooseArtTo(this, sourcesRowBox(SourcesNames.SKETCH).center)
			counter.reset()

			moveOn(listOf(sourcesRowBox(SourcesNames.NOTES).center))

			assertEquals(1, counter.runsOf("SourcesRowBody"), "Sketch loses the ring, and the ignored row gains nothing")
			assertEquals(0, counter.runsOf("SourcesRowView"))
			assertEquals(0, counter.runsOf("SourcesSpace"))
			pressKey(Key.Escape)
			releasePress()
		}

	private companion object {
		const val SOURCES_PACKAGE_PREFIX = "org.umamo.ui.workspace.spaces.sources."

		/** A name no row of the rig carries. */
		const val RENAMED = "Renamed"

		/** A confidence other than the one the fixture publishes, so the proposal reads as changed. */
		const val WEAKER_SCORE = 0.75f
	}
}