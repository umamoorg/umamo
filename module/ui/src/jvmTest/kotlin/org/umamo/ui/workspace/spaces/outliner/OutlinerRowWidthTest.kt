package org.umamo.ui.workspace.spaces.outliner

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.workspace.spaces.parameters.PANEL_OUTLINER_HEIGHT
import org.umamo.ui.workspace.spaces.parameters.ParametersPanelHarness
import org.umamo.ui.workspace.spaces.parameters.mountParametersPanel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Pins the one width every row is fixed to against the row it is measured for.  The width is a sum of the
 * row's slots, gaps, and padding, kept beside the row's layout rather than in it, so a slot the sum leaves
 * out takes its width from the name: the name is laid out narrower than its text and loses its end.
 */
@OptIn(ExperimentalTestApi::class)
class OutlinerRowWidthTest {
	/**
	 * The outliner rig with a part and a drawable named far longer than a narrow panel is wide.  The two
	 * draw their names in different styles and carry different trailing slots.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun longNamedModel(): PuppetModel {
		val model = outlinerFixtureModel()

		return model.copy(
			parts = model.parts.map { part -> if (part.id == OutlinerIds.head) part.copy(name = LONG_PART_NAME) else part },
			drawables = model.drawables.map { drawable -> if (drawable.id == OutlinerIds.loose) drawable.copy(name = LONG_DRAWABLE_NAME) else drawable },
		)
	}

	/** A name longer than the panel is laid out as wide as it is with room to spare: the rows widen to it. */
	@Test
	fun aNameLongerThanThePanelKeepsItsFullWidth() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showOutliner = true, model = longNamedModel())
			harness.outlinerSize = DpSize(NARROW_WIDTH, PANEL_OUTLINER_HEIGHT)
			mountParametersPanel(harness)
			val squeezedPart = outlinerBoundsOfText(LONG_PART_NAME).width
			val squeezedDrawable = outlinerBoundsOfText(LONG_DRAWABLE_NAME).width

			runOnIdle { harness.outlinerSize = DpSize(ROOMY_WIDTH, PANEL_OUTLINER_HEIGHT) }
			waitForIdle()

			val roomyPart = outlinerBoundsOfText(LONG_PART_NAME).width
			val roomyDrawable = outlinerBoundsOfText(LONG_DRAWABLE_NAME).width
			assertTrue(abs(squeezedPart - roomyPart) <= 1f, "the part's name is $roomyPart wide with room and was laid out $squeezedPart wide without")
			assertTrue(
				abs(squeezedDrawable - roomyDrawable) <= 1f,
				"the drawable's name is $roomyDrawable wide with room and was laid out $squeezedDrawable wide without",
			)
		}

	/** With the restriction columns switched off the rows give their slots up, and a long name is still whole. */
	@Test
	fun aLongNameKeepsItsFullWidthWithTheColumnsOff() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(showOutliner = true, model = longNamedModel())
			harness.outlinerSize = DpSize(NARROW_WIDTH, PANEL_OUTLINER_HEIGHT)
			mountParametersPanel(harness)
			runOnIdle {
				harness.outlinerViewState.showSelectableColumn = false
				harness.outlinerViewState.showVisibilityColumn = false
			}
			waitForIdle()
			val squeezedPart = outlinerBoundsOfText(LONG_PART_NAME).width

			runOnIdle { harness.outlinerSize = DpSize(ROOMY_WIDTH, PANEL_OUTLINER_HEIGHT) }
			waitForIdle()

			val roomyPart = outlinerBoundsOfText(LONG_PART_NAME).width
			assertTrue(abs(squeezedPart - roomyPart) <= 1f, "the part's name is $roomyPart wide with room and was laid out $squeezedPart wide without")
		}

	private companion object {
		const val LONG_PART_NAME = "A part with a name a good deal longer than the panel"
		const val LONG_DRAWABLE_NAME = "A drawable with a name a good deal longer than the panel"

		/** Far narrower than either long name. */
		val NARROW_WIDTH = 140.dp

		/** Wider than either long name with everything beside it. */
		val ROOMY_WIDTH = 620.dp
	}
}