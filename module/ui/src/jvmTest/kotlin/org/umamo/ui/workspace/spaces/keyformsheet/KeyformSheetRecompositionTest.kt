package org.umamo.ui.workspace.spaces.keyformsheet

import androidx.compose.runtime.Composer
import androidx.compose.runtime.InternalComposeTracingApi
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.edit.SelectionTarget
import org.umamo.edit.structure.rename
import org.umamo.ui.workspace.spaces.parameters.ComposableRunCounter
import org.umamo.ui.workspace.spaces.parameters.PanelIds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins what recomposes the sheet.  A section reads the playhead through its own state, so a scrub runs the
 * section of the parameter being moved and not the sheet around it; and everything the sheet hands a
 * section compares equal when nothing about that section changed, so a sheet that recomposes for its own
 * reasons leaves its sections skipped.
 */
@OptIn(ExperimentalTestApi::class, InternalComposeTracingApi::class)
class KeyformSheetRecompositionTest {
	/**
	 * Runs [body] with the sheet's body runs counted, and takes the counter off again whatever happens: the
	 * tracer is one per process.
	 *
	 * @param Function body The case, handed the counter.
	 */
	private fun counting(body: ComposeUiTest.(ComposableRunCounter) -> Unit) {
		val counter = ComposableRunCounter(SHEET_PACKAGE_PREFIX)
		Composer.setTracer(counter)
		try {
			runComposeUiTest { body(counter) }
		} finally {
			Composer.setTracer(null)
		}
	}

	/** The counter has to see the sheet compose at all, or every absence below would pass by seeing nothing. */
	@Test
	fun theCounterSeesTheSheetCompose() =
		counting { counter ->
			mountSheet(listOf(PanelIds.bodyX))

			assertTrue(counter.runsOf("KeyformSheetSpace") >= 1)
			assertTrue(counter.runsOf("KeyformSheetSection") >= 1)
		}

	/**
	 * A scrub of a targeted parameter, from the panel or from anywhere else that previews the pose, runs the
	 * moved parameter's section once per frame and never the sheet or the other section.
	 */
	@Test
	fun aScrubRunsOnlyTheMovedParametersSection() =
		counting { counter ->
			val harness = mountSheet(listOf(PanelIds.angleX, PanelIds.angleY))
			val values = listOf(4f, 5f, 6f, 7f)
			counter.reset()

			for (value in values) {
				runOnIdle { harness.liveParamsHandle.preview(PanelIds.angleX, value) }
				waitForIdle()
			}

			assertEquals(0, counter.runsOf("KeyformSheetSpace"), "the playhead is the section's own state")
			assertEquals(values.size, counter.runsOf("KeyformSheetSection"), "the Angle X section follows the playhead, and the Angle Y section skips")
		}

	/** A sheet recomposition that changes nothing a section is handed leaves every section skipped. */
	@Test
	fun aSheetRecompositionLeavesItsSectionsSkipped() =
		counting { counter ->
			val harness = mountSheet(listOf(PanelIds.angleX, PanelIds.angleY))
			counter.reset()

			// The marquee's arm is read by the sheet and handed to no section.
			runOnIdle { harness.sheetViewState.boxSelectArmed = true }
			waitForIdle()

			assertTrue(counter.runsOf("KeyformSheetSpace") >= 1, "the sheet must really have recomposed")
			assertEquals(0, counter.runsOf("KeyformSheetSection"))
		}

	/** An edit to an object no section lists runs the sheet, which is handed the new model, and no section. */
	@Test
	fun anEditNoSectionShowsRunsNoSection() =
		counting { counter ->
			val harness = mountSheet(listOf(PanelIds.bodyX))
			counter.reset()

			// Keyed on the pad's two parameters and on nothing else, so Body X's section does not list it.
			runOnIdle { harness.session.rename(SelectionTarget.Drawable(SheetIds.padDrawable), RENAMED) }
			waitForIdle()

			assertTrue(counter.runsOf("KeyformSheetSpace") >= 1, "the sheet must really have been handed the model")
			assertEquals(0, counter.runsOf("KeyformSheetSection"))
		}

	/** An edit to an object one section lists runs that section, and not the section beside it. */
	@Test
	fun anEditToOneSectionsObjectRunsThatSectionAlone() =
		counting { counter ->
			val harness = mountSheet(listOf(PanelIds.angleX, PanelIds.bodyX))
			counter.reset()

			// Keyed on Body X alone.
			runOnIdle { harness.session.rename(SelectionTarget.Drawable(PanelIds.drawable), RENAMED) }
			waitForIdle()

			assertTrue(sheetCountOfText(RENAMED) >= 1, "the edit must really have reached the sheet")
			assertEquals(1, counter.runsOf("KeyformSheetSection"), "Body X's section, and not Angle X's")
		}

	private companion object {
		/** A name nothing in the rig carries. */
		const val RENAMED = "Renamed"

		const val SHEET_PACKAGE_PREFIX = "org.umamo.ui.workspace.spaces.keyformsheet."
	}
}