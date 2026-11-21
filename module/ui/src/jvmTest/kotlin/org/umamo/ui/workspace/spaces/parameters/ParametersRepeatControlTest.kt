package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.ui.settings.ParametersSettings
import org.umamo.ui.settings.RepeatControlSetting
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the control preference: under the slider choice a repeating parameter shows its slider and a pair with a
 * repeating member its pad, and changing the choice swaps the controls in the open panel.  The panel's other suites
 * run at the default, the dial.
 *
 * Body X runs -10..10.  Angle X and Angle Y run -30..30.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersRepeatControlTest {
	/** Under the slider choice a repeating parameter's row is a slider: a press lands where the track says. */
	@Test
	fun theSliderChoiceGivesARepeatingParameterItsSlider() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			harness.preferRepeatControl(RepeatControlSetting.Slider)
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			clickAt(slider.at(0.75f))

			assertNear(5f, harness.committed(PanelIds.bodyX), "three quarters along -10..10, where the dial would leave it at 2")
			assertEquals(cursorBefore + 1, harness.historyCursor)
		}

	/** The slider on a repeating parameter stops at its end, while the value field above it still wraps (D10). */
	@Test
	fun aSliderOnARepeatingParameterStopsAtItsEnds() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			harness.preferRepeatControl(RepeatControlSetting.Slider)
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))

			assertEquals(10f, harness.committed(PanelIds.bodyX), "a drag past the end lands on max, and does not wrap")

			typeIntoNumberField("10.00", "12")

			assertEquals(-8f, harness.committed(PanelIds.bodyX), "12 on a -10..10 loop is -8")
		}

	/** Under the slider choice a pair with a repeating member keeps its pad: a press lands both axes as one step. */
	@Test
	fun theSliderChoiceGivesARepeatingPairItsPad() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.angleX, PanelIds.angleY)))
			harness.preferRepeatControl(RepeatControlSetting.Slider)
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val cursorBefore = harness.historyCursor

			clickAt(padPointOf(pad, 12f, -6f))

			assertNear(12f, harness.committed(PanelIds.angleX), "the pad drives the upper axis")
			assertNear(-6f, harness.committed(PanelIds.angleY), "and the lower one")
			assertEquals(cursorBefore + 1, harness.historyCursor, "a press is one step for both axes")
		}

	/** Changing the choice swaps the dial for the shorter slider in the open panel, and back. */
	@Test
	fun changingTheChoiceSwapsTheControlLive() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			// Folding the group above lifts Body X and Breath, so both stay well inside the panel with the dial showing.
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			val breathTopWithDial = numberFieldBounds(PanelValues.BREATH).top
			val shrunk = with(density) { (PARAMETER_DIAL_DIAMETER + PARAMETER_DIAL_SPACING * 2 - SLIDER_HEIGHT).toPx() }

			preferLive(harness, RepeatControlSetting.Slider)
			val breathTopWithSlider = numberFieldBounds(PanelValues.BREATH).top
			assertTrue(
				abs(breathTopWithDial - breathTopWithSlider - shrunk) < 2f,
				"the row below moves up by the dial's extra height, $shrunk px; it moved ${breathTopWithDial - breathTopWithSlider}",
			)

			preferLive(harness, RepeatControlSetting.Dial)
			assertTrue(abs(numberFieldBounds(PanelValues.BREATH).top - breathTopWithDial) < 2f, "back on the dial choice, the dial is back")
		}

	/**
	 * Writes the control preference into the harness's settings before the panel mounts.
	 *
	 * @param RepeatControlSetting choice The control a repeating parameter shows.
	 */
	private fun ParametersPanelHarness.preferRepeatControl(choice: RepeatControlSetting) {
		settings.setString(ParametersSettings.REPEAT_CONTROL_KEY, choice.key)
	}

	/**
	 * Writes the control preference into the mounted panel's settings and lets the panel catch up.
	 *
	 * @param ParametersPanelHarness harness The mounted harness.
	 * @param RepeatControlSetting choice The control a repeating parameter shows.
	 */
	private fun ComposeUiTest.preferLive(harness: ParametersPanelHarness, choice: RepeatControlSetting) {
		runOnIdle { harness.preferRepeatControl(choice) }
		waitForIdle()
	}

	private companion object {
		/** The slider's fixed height, which the dial's square and its spacing replace. */
		val SLIDER_HEIGHT = 18.dp
	}
}