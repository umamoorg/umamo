package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.edit.ParameterControl
import org.umamo.edit.parameter.setParameterLink
import org.umamo.edit.parameter.setParameterRepeat
import org.umamo.ui.settings.ParametersSettings
import org.umamo.ui.settings.RepeatControlSetting
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the control preference: under the slider choice a repeating parameter shows its slider, which wraps, and a pair
 * with a repeating member its pad, and changing the choice swaps the controls in the open panel.  A parameter's or a
 * pair's own pick beats the preference, outlives Repeat, and keys a pair on its upper member.  The panel's other suites
 * run at the default, the dial, except where a case sets the slider choice.
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

	/** The slider on a repeating parameter wraps past its end, as the value field above it does. */
	@Test
	fun aSliderOnARepeatingParameterWrapsPastItsEnd() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			harness.preferRepeatControl(RepeatControlSetting.Slider)
			mountParametersPanel(harness)

			typeIntoNumberField(PanelValues.BODY_X, "12")

			assertEquals(-8f, harness.committed(PanelIds.bodyX), "12 on a -10..10 loop is -8")

			val slider = sliderBox(harness, PanelRows.BODY_X, "-8.00")
			drag(slider.at(0.6f), listOf(slider.at(0.8f), Offset(slider.right + 40f, slider.center.y)))

			val committed = harness.committed(PanelIds.bodyX)
			assertTrue(committed != null && committed < 0f, "a drag well past max comes in at min, got $committed")
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

	/** A parameter's own control beats the preference, live: a Dial pick under the slider choice shows the dial. */
	@Test
	fun aParametersOwnControlBeatsThePreference() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			harness.preferRepeatControl(RepeatControlSetting.Slider)
			mountParametersPanel(harness)

			runOnIdle { harness.session.setParameterControl(PanelIds.bodyX, ParameterControl.Dial) }
			waitForIdle()
			clickAt(dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X).atClock(0.25f))

			assertNear(-5f, harness.committed(PanelIds.bodyX), "a quarter turn round -10..10, where the slider would leave it at 2")
		}

	/** A Dial pick outlives Repeat: turned off, the parameter keeps its dial, which now stops at its seam. */
	@Test
	fun aDialPickOutlivesRepeat() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			harness.session.setParameterControl(PanelIds.bodyX, ParameterControl.Dial)
			mountParametersPanel(harness)

			runOnIdle { harness.session.setParameterRepeat(PanelIds.bodyX, false) }
			waitForIdle()
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			drag(dial.atClock(0.85f), listOf(dial.atClock(0.95f), dial.atClock(0.05f), dial.atClock(0.1f)))

			assertEquals(10f, harness.committed(PanelIds.bodyX), "across the seam a dial that no longer repeats waits at max")
		}

	/**
	 * A dial picked for a parameter that does not repeat counts its laps as one that clamps: it waits at max however
	 * far the pointer turns, and follows again on the first crossing back.
	 */
	@Test
	fun aDialPickOnAParameterThatDoesNotRepeatFollowsOnTheFirstCrossingBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.session.setParameterControl(PanelIds.bodyX, ParameterControl.Dial)
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val clockwiseLap = listOf(0.3f, 0.55f, 0.8f, 0.05f)

			pressAndMove(dial.atClock(0.85f), (listOf(0.95f, 0.05f) + clockwiseLap + clockwiseLap).map { fraction -> dial.atClock(fraction) })
			assertEquals(10f, harness.live(PanelIds.bodyX), "across the seam and two laps on, a dial that does not repeat waits at max")

			moveOn(listOf(dial.atClock(0.95f), dial.atClock(0.9f)))
			releasePress()
			assertNear(8f, harness.committed(PanelIds.bodyX), "one crossing back and it follows the pointer again")
		}

	/**
	 * With Ctrl held, a dial picked for a parameter that does not repeat snaps along its range alone: a key at min
	 * never catches a press just before the seam, on the max side.
	 */
	@Test
	fun ctrlOnADialThatDoesNotRepeatNeverReachesAcrossTheSeam() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(bodyXKeys = floatArrayOf(-10f, 0f)))
			harness.session.setParameterControl(PanelIds.bodyX, ParameterControl.Dial)
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			setCtrlHeld(true)
			clickAt(dial.atClock(0.995f))
			setCtrlHeld(false)

			assertNear(9.9f, harness.committed(PanelIds.bodyX), "the key at min is the whole range away, so the press lands where the pointer is")
		}

	/** A pair's pick is its upper member's: unlinked, the upper member keeps it and the lower member follows the preference. */
	@Test
	fun aPairsPickStaysWithItsUpperMemberAcrossAnUnlink() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			harness.session.setParameterControl(PanelIds.angleX, ParameterControl.Dial)
			mountParametersPanel(harness)
			val (upperDial, _) = pairDialBoxes(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)

			clickAt(upperDial.atClock(0.25f))
			assertNear(-15f, harness.committed(PanelIds.angleX), "a pair that does not repeat shows its two dials")

			// Back to the fixture's values, which the controls below are found by, then unlinked.
			runOnIdle {
				harness.session.undo()
				harness.session.setParameterLink(PanelIds.angleX, PanelIds.angleY, linked = false)
			}
			waitForIdle()
			clickAt(dialBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X).atClock(0.5f))
			assertNear(0f, harness.committed(PanelIds.angleX), "unlinked, the upper member keeps the dial")
			clickAt(sliderBox(harness, PanelRows.ANGLE_PAD + 1, PanelValues.ANGLE_Y).at(0.75f))
			assertNear(15f, harness.committed(PanelIds.angleY), "and the lower member shows the slider")
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