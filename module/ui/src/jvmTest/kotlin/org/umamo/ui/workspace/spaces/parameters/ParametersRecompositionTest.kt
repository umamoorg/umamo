package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.runtime.Composer
import androidx.compose.runtime.CompositionTracer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.parameter.renameParameter
import org.umamo.edit.structure.rename
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Counts how many times each of the panel's composables runs its body, by the name the compiler traces
 * it under.
 *
 * The compiler emits the trace call after the skip check, so a composable that skipped is not counted:
 * the count is how many times the body really ran.  A lambda is traced under its enclosing function's
 * name followed by one "anonymous" marker per level, so the two are told apart by that marker.
 *
 * @property String packagePrefix The package whose composables are counted, with its trailing dot; the
 *   panel's unless a case says otherwise.
 * @property String fixtureFunction The function that mounts the rig from that package, whose own body and
 *   lambdas are not counted; the panel harness's unless a case says otherwise.
 */
@OptIn(InternalComposeTracingApi::class)
internal class ComposableRunCounter(
	private val packagePrefix: String = PANEL_PACKAGE_PREFIX,
	private val fixtureFunction: String = FIXTURE_FUNCTION,
) : CompositionTracer {
	private val runsByName = HashMap<String, Int>()

	/**
	 * How many times each named composable of the panel ran its body since the last [reset].
	 *
	 * @return Map<String, Int> Simple name to number of runs, holding only the ones that ran.
	 */
	fun namedRuns(): Map<String, Int> = runsByName.filterKeys { name -> LAMBDA_MARKER !in name }

	/**
	 * How many lambda bodies of the panel ran since the last [reset], all lambdas together.
	 *
	 * @return Int The number of runs.
	 */
	fun lambdaRuns(): Int = runsByName.filterKeys { name -> LAMBDA_MARKER in name }.values.sum()

	/**
	 * How many times one named composable ran its body since the last [reset].
	 *
	 * @param String functionName The composable's simple name.
	 * @return Int The number of runs.
	 */
	fun runsOf(functionName: String): Int = runsByName[functionName] ?: 0

	/**
	 * Forgets every count.
	 */
	fun reset() {
		runsByName.clear()
	}

	/**
	 * Says tracing is on, which is what makes the compiled code report at all.
	 *
	 * @return Boolean Always true.
	 */
	override fun isTraceInProgress(): Boolean = true

	/**
	 * Counts one body run, when the body belongs to the counted package.
	 *
	 * @param Int key The composable's group key.
	 * @param Int dirty1 The first changed-parameter mask.
	 * @param Int dirty2 The second changed-parameter mask.
	 * @param String info The composable's qualified name, followed by its file and line in parentheses.
	 */
	override fun traceEventStart(key: Int, dirty1: Int, dirty2: Int, info: String) {
		// The name only: the file and the line move with every edit, and the name is what is being pinned.
		val qualifiedName = info.substringBefore(" (")
		if (!qualifiedName.startsWith(packagePrefix)) {
			return
		}
		val name = qualifiedName.removePrefix(packagePrefix)
		// The fixture mounts the panel from this same package; its own lambdas are not the panel's.
		if (name.startsWith(fixtureFunction)) {
			return
		}
		runsByName[name] = (runsByName[name] ?: 0) + 1
	}

	/**
	 * Ends a traced body, which counts nothing.
	 */
	override fun traceEventEnd() {
	}

	private companion object {
		const val PANEL_PACKAGE_PREFIX = "org.umamo.ui.workspace.spaces.parameters."
		const val FIXTURE_FUNCTION = "mountParametersPanel"
		const val LAMBDA_MARKER = "<anonymous>"
	}
}

/**
 * Pins what a scrub recomposes.  Every pointer move of a scrub writes the pose, so whatever reads the
 * pose runs again per move; what keeps a scrub cheap is that nothing else does.
 *
 * The rows read the pose from one shared map, so a write to it reaches every row that shows a value.
 * Each of those rows re-runs the small lambda that reads it, and only the row whose value moved goes on
 * to run its controls: the others skip, because nothing handed to them changed.  That skip is what these
 * cases hold on to.
 *
 * Counted through Compose's own tracing hook, which is internal to the runtime.  It is the only way to
 * see a skip without putting test hooks in the panel itself.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class ParametersRecompositionTest {
	/**
	 * Runs [body] with body runs counted, and takes the counter off again whatever happens: the tracer is
	 * one per process, so a counter left installed would count every test that follows.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
		val counter = ComposableRunCounter()
		Composer.setTracer(counter)
		try {
			runComposeUiTest { body(counter) }
		} finally {
			Composer.setTracer(null)
		}
	}

	/** The counter has to see the panel compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesThePanelCompose() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)

			assertEquals(1, counter.runsOf("ParametersSpace"))
			assertEquals(SLIDER_ROWS, counter.runsOf("ParameterSlider"), "one per slider row the list shows")
			assertEquals(1, counter.runsOf("ParameterPad2D"))
			assertEquals(PanelRows.COUNT, counter.runsOf("ParameterGripHandle"), "one per row the list shows")
			assertTrue(counter.lambdaRuns() > 0)
		}

	/** A scrub runs the slider it moves and that slider's value row, once per move, and nothing else by name. */
	@Test
	fun aScrubRunsOnlyTheSliderItMoves() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val moves = listOf(slider.at(0.62f), slider.at(0.66f), slider.at(0.7f), slider.at(0.74f), slider.at(0.78f))
			pressAndMove(slider.at(0.5f), listOf(slider.at(0.58f)))
			counter.reset()

			moveOn(moves)

			val named = counter.namedRuns()
			assertEquals(setOf("ParameterSlider", "ParameterValueRow"), named.keys, "a scrub must run the moved row's controls and no other composable")
			assertTrue(named.getValue("ParameterSlider") <= moves.size, "the slider runs at most once per move, and ran ${named["ParameterSlider"]} times")
			assertTrue(named.getValue("ParameterValueRow") <= moves.size, "and so does its value row, which ran ${named["ParameterValueRow"]} times")
			assertTrue(
				counter.lambdaRuns() <= moves.size,
				"only the moved row re-reads its value, once per move; ${counter.lambdaRuns()} lambdas ran for ${moves.size} moves",
			)
			releasePress()
		}

	/** A pad scrub is held to the same: the pad, its two value rows, and nothing else by name. */
	@Test
	fun aPadScrubRunsOnlyThePadItMoves() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val moves = listOf(pad.at(0.6f, 0.4f), pad.at(0.65f, 0.35f), pad.at(0.7f, 0.3f))
			pressAndMove(pad.center, listOf(pad.at(0.55f, 0.45f)))
			counter.reset()

			moveOn(moves)

			val named = counter.namedRuns()
			assertEquals(setOf("ParameterPad2D", "ParameterValueRow"), named.keys, "a pad scrub must run the pad's controls and no other composable")
			assertTrue(named.getValue("ParameterPad2D") <= PAD_WRITES_PER_MOVE * moves.size, "the pad ran ${named["ParameterPad2D"]} times for ${moves.size} moves")
			assertTrue(
				counter.lambdaRuns() <= PAD_LAMBDAS_PER_MOVE * moves.size,
				"only the pad re-reads its values; ${counter.lambdaRuns()} lambdas ran for ${moves.size} moves",
			)
			releasePress()
		}

	/** A preview made elsewhere moves one slider, and runs that one. */
	@Test
	fun anOutsidePreviewRunsOnlyTheSliderItMoves() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			counter.reset()

			runOnIdle { harness.liveParamsHandle.preview(PanelIds.bodyX, 7f) }
			waitForIdle()

			assertEquals(mapOf("ParameterSlider" to 1, "ParameterValueRow" to 1), counter.namedRuns())
			assertTrue(counter.lambdaRuns() <= 1, "only the moved row re-reads its value; ${counter.lambdaRuns()} lambdas ran for one write")
		}

	/** The release commits the pose, which the panel reads, so the panel runs; it must not run away. */
	@Test
	fun aReleaseRunsThePanelAtMostTwice() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			pressAndMove(slider.at(0.5f), listOf(slider.at(0.6f), Offset(slider.right + 40f, slider.center.y)))
			counter.reset()

			releasePress()

			val panelRuns = counter.runsOf("ParametersSpace")
			assertTrue(panelRuns <= 2, "a commit may rebuild the panel once or twice, and rebuilt it $panelRuns times")
		}

	/**
	 * A change to one row's view state runs every row's frame, since the rows read it from one shared map,
	 * and goes on to run the controls of the one row it changed.  The others are handed holders equal to
	 * the ones they had, and skip.
	 */
	@Test
	fun openingOneRangeEditorRunsOnlyThatRowsControls() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			counter.reset()

			runOnIdle { harness.viewState.openRangeEditors[PanelIds.bodyX] = true }
			waitForIdle()

			assertEquals(1, counter.runsOf("RangeFieldsRow"), "the editor must really have opened")
			assertEquals(1, counter.runsOf("ParameterSlider"), "a row whose holders are rebuilt equal must skip its slider")
			assertEquals(1, counter.runsOf("ParameterValueRow"))
			assertEquals(0, counter.runsOf("ParameterPad2D"), "and its pad")
			assertEquals(0, counter.runsOf("ParametersSpace"), "view state of a row is not the panel's to read")
		}

	/**
	 * A rename target is read by every row and by the panel, which scrolls the row into view, so this one
	 * rebuilds the list as well.  A row built again for the same parameter still hands its controls what
	 * they had.
	 */
	@Test
	fun startingARenameRunsOnlyThatRowsControls() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			counter.reset()

			runOnIdle { harness.viewState.renamingParameterId = PanelIds.bodyX }
			waitForIdle()

			assertTrue(renameFieldOpen(), "the field must really have opened")
			assertEquals(1, counter.runsOf("ParameterSlider"), "a row whose rename slot is rebuilt equal must skip its slider")
			assertEquals(1, counter.runsOf("ParameterValueRow"))
			assertEquals(0, counter.runsOf("ParameterPad2D"), "and its pad")
			assertTrue(counter.runsOf("ParameterIsland") <= 1, "an island built again for the same parameter skips; ${counter.runsOf("ParameterIsland")} ran")
			assertEquals(0, counter.runsOf("ParameterGripHandle"), "and so does a grip")
		}

	/**
	 * A change the panel reads and no row depends on rebuilds the rows, each for the same parameters, and
	 * runs none of their controls.  An object selection is one: the panel reads it for its filter, which is
	 * off here.
	 */
	@Test
	fun aChangeNoRowDependsOnRunsNoRowControl() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			counter.reset()

			runOnIdle {
				val target = SelectionTarget.Drawable(PanelIds.drawable)
				harness.session.setSelection(Selection(setOf(target), target))
			}
			waitForIdle()

			assertTrue(counter.runsOf("ParametersSpace") >= 1, "the panel must really have recomposed")
			assertEquals(0, counter.runsOf("ParameterGripHandle"))
			assertEquals(0, counter.runsOf("ParameterIsland"))
			assertEquals(0, counter.runsOf("ParameterSlider"))
			assertEquals(0, counter.runsOf("ParameterPad2D"))
		}

	/** An edit that changes nothing the panel shows runs the panel, which is handed the new model, and no control. */
	@Test
	fun anEditThePanelDoesNotShowRunsNoControl() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			counter.reset()

			runOnIdle { harness.session.rename(SelectionTarget.Drawable(PanelIds.drawable), RENAMED) }
			waitForIdle()

			assertTrue(counter.runsOf("ParametersSpace") >= 1, "the panel must really have been handed the model")
			assertEquals(0, counter.runsOf("ParameterGripHandle"))
			assertEquals(0, counter.runsOf("ParameterIsland"))
			assertEquals(0, counter.runsOf("ParameterSlider"))
			assertEquals(0, counter.runsOf("ParameterPad2D"))
			assertEquals(0, counter.runsOf("ParameterValueRow"))
		}

	/**
	 * An edit to one parameter runs that parameter's controls, and no other row's.  Its island is handed
	 * what it was handed before, so what runs is the island's content and not the island.
	 */
	@Test
	fun anEditToOneParameterRunsItsControlsAlone() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			counter.reset()

			runOnIdle { harness.session.renameParameter(PanelIds.breath, RENAMED) }
			waitForIdle()

			assertTrue(showsText(RENAMED), "the edit must really have reached the panel")
			assertEquals(0, counter.runsOf("ParameterIsland"))
			assertEquals(1, counter.runsOf("ParameterSlider"))
			assertEquals(1, counter.runsOf("ParameterValueRow"))
			assertEquals(0, counter.runsOf("ParameterPad2D"))
			assertEquals(0, counter.runsOf("ParameterGripHandle"))
		}

	/**
	 * Picks the Breath row up by its grip and carries it to [target], leaving the button held.  The first
	 * move goes past the touch slop, so the drag has started by the time it arrives: the pick-up is over
	 * before anything is counted.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Offset target Where the drag rests, in the panel body's pixels.
	 */
	private fun carryBreathTo(test: ComposeUiTest, harness: ParametersPanelHarness, target: Offset) {
		val grip = test.gripBounds(harness, PanelRows.BREATH).center
		test.pressAndMove(grip, listOf(Offset(grip.x + 4f, grip.y + PAST_THE_TOUCH_SLOP), target))
		assertTrue(test.popupShows(PanelNames.BREATH), "the drag must really be in flight")
	}

	/**
	 * A point over a row, by how far its grip's center it sits above (negative) or below.  A grip sits at
	 * its row's middle, which is where a slider row's upper band ends and its lower one begins.
	 *
	 * @param ComposeUiTest test The running UI test.
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param Int rowIndex The row, 0 for the first composed row.
	 * @param Float below How far under the grip's center, in pixels.
	 * @return Offset The point, in the panel body's pixels.
	 */
	private fun byGrip(test: ComposeUiTest, harness: ParametersPanelHarness, rowIndex: Int, below: Float): Offset {
		val grip = test.gripBounds(harness, rowIndex).center
		return Offset(grip.x, grip.y + below)
	}

	/** A row drag moves the drop line, which is the frame's business and no control's. */
	@Test
	fun aRowDragRunsNoControl() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			val target = gripBounds(harness, PanelRows.BODY_X)
			carryBreathTo(this, harness, Offset(target.center.x, target.bottom - 2f))
			counter.reset()

			moveOn(listOf(Offset(target.center.x, target.center.y), Offset(target.center.x, target.top + 2f)))

			assertTrue(counter.runsOf("ParameterRowView") > 0, "the drag must really have moved the drop line")
			assertEquals(0, counter.runsOf("ParameterGripHandle"))
			assertEquals(0, counter.runsOf("ParameterSlider"))
			assertEquals(0, counter.runsOf("ParameterPad2D"))
			assertEquals(0, counter.runsOf("ParameterValueRow"))
			assertEquals(0, counter.runsOf("ParametersSpace"))
			pressKey(Key.Escape)
			releasePress()
		}

	/** A pointer moving inside one band of one row changes no row's part in the drag, and runs nothing at all. */
	@Test
	fun aDragMovingInsideOneBandRunsNothing() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			carryBreathTo(this, harness, byGrip(this, harness, PanelRows.BODY_X, below = -9f))
			counter.reset()

			moveOn(listOf(byGrip(this, harness, PanelRows.BODY_X, below = -7f), byGrip(this, harness, PanelRows.BODY_X, below = -5f), byGrip(this, harness, PanelRows.BODY_X, below = -3f)))

			assertEquals(emptyMap(), counter.namedRuns())
			assertEquals(0, counter.lambdaRuns())
			pressKey(Key.Escape)
			releasePress()
		}

	/** A pointer crossing a row's middle runs that row's frame, which draws the line, and none of its controls. */
	@Test
	fun aDragCrossingABandRunsTheTargetRowsFrameAlone() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			carryBreathTo(this, harness, byGrip(this, harness, PanelRows.BODY_X, below = -4f))
			counter.reset()

			moveOn(listOf(byGrip(this, harness, PanelRows.BODY_X, below = 4f)))

			assertEquals(mapOf("ParameterRowView" to 1), counter.namedRuns(), "the line above gives way to the line below")
			pressKey(Key.Escape)
			releasePress()
		}

	/** A pointer crossing from one row to another runs the frame of the row it left and of the row it reached. */
	@Test
	fun aDragCrossingToAnotherRowRunsTheTwoFrames() =
		counting { counter ->
			val harness = ParametersPanelHarness()
			mountParametersPanel(harness)
			carryBreathTo(this, harness, byGrip(this, harness, PanelRows.BODY_X, below = -4f))
			counter.reset()

			moveOn(listOf(byGrip(this, harness, PanelRows.SMILE, below = 4f)))

			assertEquals(mapOf("ParameterRowView" to 2), counter.namedRuns(), "the row the line left and the row it reached")
			pressKey(Key.Escape)
			releasePress()
		}

	private companion object {
		/** A name nothing in the rig carries. */
		const val RENAMED = "Renamed"

		/** A first move longer than the touch slop (18 pixels), which is what starts a grip's drag. */
		const val PAST_THE_TOUCH_SLOP = 24f

		/** The slider rows the fixture's list shows: Eye Open, Smile Shape, Smile, Body X, and Breath. */
		const val SLIDER_ROWS = 5

		/** A pad move writes both its axes, each of which reaches the pad. */
		const val PAD_WRITES_PER_MOVE = 2

		/**
		 * The lambdas a pad move runs: the island content that reads the pad's two values, and the pad's two
		 * axis rows, which show them.  None belongs to another row.
		 */
		const val PAD_LAMBDAS_PER_MOVE = 3
	}
}