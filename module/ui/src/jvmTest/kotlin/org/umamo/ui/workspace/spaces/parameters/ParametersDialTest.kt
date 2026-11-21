package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import org.umamo.edit.ParameterSelection
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the dial a repeating parameter shows in place of its slider: a press jumps to its angle, a drag follows the
 * angle round and wraps at the seam, Ctrl snaps across the seam, and the pose it writes stays in range.  A pair with
 * a repeating member shows a dial per axis, and a member that does not repeat stops at its seam.
 *
 * Body X runs -10..10, so a lap is 20 and 3 o'clock is -5.  Angle X and Angle Y run -30..30.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersDialTest {
	/** A press previews the value at its angle before the release, and the release commits it as one step. */
	@Test
	fun aPressJumpsToItsAngle() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			pressAndMove(dial.atClock(0.25f), emptyList())

			assertNear(-5f, harness.live(PanelIds.bodyX), "a press at 3 o'clock is a quarter lap round from min")
			assertEquals(2f, harness.committed(PanelIds.bodyX), "the press previews; only the release commits")

			releasePress()

			assertNear(-5f, harness.committed(PanelIds.bodyX), "the release commits where the press jumped")
			assertEquals(cursorBefore + 1, harness.historyCursor, "a press is one step")
		}

	/** Clockwise across 12 o'clock the value comes round from max to just past min, and the drag is one step. */
	@Test
	fun aClockwiseDragAcrossTheSeamWraps() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val cursorBefore = harness.historyCursor

			pressAndMove(dial.atClock(0.85f), listOf(dial.atClock(0.95f), dial.atClock(0.05f)))
			assertNear(-9f, harness.live(PanelIds.bodyX), "past the seam the value is just past min, not past max")

			moveOn(listOf(dial.atClock(0.15f)))
			releasePress()

			assertNear(-7f, harness.committed(PanelIds.bodyX), "the drag goes on round from min")
			assertEquals(cursorBefore + 1, harness.historyCursor, "the whole drag is one step")
		}

	/** Counterclockwise across 12 o'clock the value comes round from min to just under max. */
	@Test
	fun aCounterclockwiseDragAcrossTheSeamWraps() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			drag(dial.atClock(0.15f), listOf(dial.atClock(0.05f), dial.atClock(0.95f), dial.atClock(0.85f)))

			assertNear(7f, harness.committed(PanelIds.bodyX), "back across the seam the value comes down from max")
		}

	/** With Ctrl held, a key at max catches a press just past the seam, on the min side, and lands on max itself. */
	@Test
	fun ctrlJustPastTheSeamSnapsToAKeyAtMax() =
		runComposeUiTest {
			val model = panelFixtureModel(repeating = setOf(PanelIds.bodyX), bodyXKeys = floatArrayOf(0f, 10f))
			val harness = ParametersPanelHarness(model = model)
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			setCtrlHeld(true)
			clickAt(dial.atClock(0.005f))
			setCtrlHeld(false)

			assertEquals(10f, harness.committed(PanelIds.bodyX), "a key at max reaches across the seam")
		}

	/** With Ctrl held, a key at min catches a press just before the seam, on the max side. */
	@Test
	fun ctrlJustBeforeTheSeamSnapsToAKeyAtMin() =
		runComposeUiTest {
			val model = panelFixtureModel(repeating = setOf(PanelIds.bodyX), bodyXKeys = floatArrayOf(-10f, 0f))
			val harness = ParametersPanelHarness(model = model)
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			setCtrlHeld(true)
			clickAt(dial.atClock(0.995f))
			setCtrlHeld(false)

			assertEquals(-10f, harness.committed(PanelIds.bodyX), "a key at min reaches across the seam")
		}

	/** Without Ctrl, the same press lands where the pointer is, whatever key sits across the seam. */
	@Test
	fun withoutCtrlThePressIgnoresTheKeys() =
		runComposeUiTest {
			val model = panelFixtureModel(repeating = setOf(PanelIds.bodyX), bodyXKeys = floatArrayOf(0f, 10f))
			val harness = ParametersPanelHarness(model = model)
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			clickAt(dial.atClock(0.005f))

			assertNear(-10f, harness.committed(PanelIds.bodyX), "a hair past the seam is a hair past min")
		}

	/** A dial gesture is a pose gesture: neither a press nor a drag retargets the keyform sheet. */
	@Test
	fun aDialGestureDoesNotRetarget() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)

			clickAt(dial.atClock(0.25f))
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value, "a press must not reach the island")

			drag(dial.atClock(0.3f), listOf(dial.atClock(0.4f), dial.atClock(0.5f)))
			assertEquals(ParameterSelection(), harness.session.parameterSelection.value, "nor must a drag")
		}

	/** A pair whose members both repeat shows a dial per axis, each driving its own axis as one step, each wrapping. */
	@Test
	fun aRepeatingPairShowsADialPerAxis() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.angleX, PanelIds.angleY)))
			mountParametersPanel(harness)
			val (left, right) = pairDialBoxes(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val cursorBefore = harness.historyCursor

			drag(left.atClock(0.9f), listOf(left.atClock(0.05f), left.atClock(0.1f)))

			assertNear(-24f, harness.committed(PanelIds.angleX), "the left dial is the upper axis, and it wraps")
			assertEquals(-4f, harness.committed(PanelIds.angleY), "a left-dial drag leaves the lower axis alone")
			assertEquals(cursorBefore + 1, harness.historyCursor)

			drag(right.atClock(0.1f), listOf(right.atClock(0.95f), right.atClock(0.9f)))

			assertNear(24f, harness.committed(PanelIds.angleY), "the right dial is the lower axis, and it wraps")
			assertNear(-24f, harness.committed(PanelIds.angleX), "a right-dial drag leaves the upper axis alone")
			assertEquals(cursorBefore + 2, harness.historyCursor)
		}

	/**
	 * Where only one member repeats, the other's dial stops at its seam: it waits at max while the pointer is past
	 * the seam, however far round, and follows again once the pointer comes back across.  The repeating member wraps.
	 */
	@Test
	fun aMemberThatDoesNotRepeatStopsAtItsSeam() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.angleY)))
			mountParametersPanel(harness)
			val (left, right) = pairDialBoxes(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)

			pressAndMove(left.atClock(0.85f), listOf(left.atClock(0.95f), left.atClock(0.05f)))
			assertEquals(30f, harness.live(PanelIds.angleX), "across the seam a clamping axis waits at max")

			moveOn(listOf(left.atClock(0.25f), left.atClock(0.5f)))
			assertEquals(30f, harness.live(PanelIds.angleX), "and keeps waiting however far round the pointer goes")

			moveOn(listOf(left.atClock(0.25f), left.atClock(0.05f), left.atClock(0.95f), left.atClock(0.9f)))
			releasePress()
			assertNear(24f, harness.committed(PanelIds.angleX), "back across the seam it follows the pointer again")

			drag(right.atClock(0.9f), listOf(right.atClock(0.05f), right.atClock(0.1f)))
			assertNear(-24f, harness.committed(PanelIds.angleY), "the repeating member still wraps")
		}

	/**
	 * A member that does not repeat follows the pointer again on the first crossing back, however many turns the
	 * pointer made past its seam, past max and below min alike.  Each step is under half a lap, so it reads the short
	 * way round.
	 */
	@Test
	fun aMemberThatDoesNotRepeatFollowsOnTheFirstCrossingBack() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.angleY)))
			mountParametersPanel(harness)
			val (left, _) = pairDialBoxes(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val clockwiseLap = listOf(0.3f, 0.55f, 0.8f, 0.05f)
			val counterclockwiseLap = listOf(0.7f, 0.45f, 0.2f, 0.95f)

			pressAndMove(left.atClock(0.85f), (listOf(0.95f, 0.05f) + clockwiseLap + clockwiseLap).map { fraction -> left.atClock(fraction) })
			assertEquals(30f, harness.live(PanelIds.angleX), "across the seam and two laps on, a clamping axis waits at max")

			moveOn(listOf(left.atClock(0.95f), left.atClock(0.9f)))
			releasePress()
			assertNear(24f, harness.committed(PanelIds.angleX), "one crossing back and it follows the pointer again")

			pressAndMove(left.atClock(0.15f), (listOf(0.05f, 0.95f) + counterclockwiseLap + counterclockwiseLap).map { fraction -> left.atClock(fraction) })
			assertEquals(-30f, harness.live(PanelIds.angleX), "below the seam and two laps on, it waits at min")

			moveOn(listOf(left.atClock(0.05f), left.atClock(0.1f)))
			releasePress()
			assertNear(-24f, harness.committed(PanelIds.angleX), "one crossing back and it follows from min")
		}

	/** On a repeating axis a typed value past the range wraps into it rather than landing on an end. */
	@Test
	fun aTypedValueWrapsOnARepeatingAxis() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val cursorBefore = harness.historyCursor

			typeIntoNumberField(PanelValues.BODY_X, "12")

			assertEquals(-8f, harness.committed(PanelIds.bodyX), "12 on a -10..10 loop is -8")
			assertEquals(cursorBefore + 1, harness.historyCursor)
			assertTrue(showsText("-8.00"))
		}

	/** Turning Repeat on swaps the slider for the taller dial, and turning it off swaps it back. */
	@Test
	fun theRepeatCheckboxSwapsTheSliderForADial() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.panelSize = DpSize(PANEL_WIDTH, PANEL_HEIGHT_WITH_SPACE)
			mountParametersPanel(harness)
			// Folding the group above lifts Body X and Breath, so both stay well inside the panel.
			clickAt(panelBoundsOfText(PanelNames.FACE).center)
			clickAt(panelBoundsOfText(PanelNames.BODY_X).center)
			val breathTopWithSlider = numberFieldBounds(PanelValues.BREATH).top

			clickAt(panelBoundsOfText(harness.text.repeat).center)
			val breathTopWithDial = numberFieldBounds(PanelValues.BREATH).top
			val grown = with(density) { (PARAMETER_DIAL_DIAMETER + PARAMETER_DIAL_SPACING * 2 - SLIDER_HEIGHT).toPx() }
			assertTrue(
				abs(breathTopWithDial - breathTopWithSlider - grown) < 2f,
				"the row below moves down by the dial's extra height, $grown px; it moved ${breathTopWithDial - breathTopWithSlider}",
			)

			clickAt(panelBoundsOfText(harness.text.repeat).center)
			assertTrue(abs(numberFieldBounds(PanelValues.BREATH).top - breathTopWithSlider) < 2f, "turned off, the slider is back")
		}

	private companion object {
		/** The slider's fixed height, which the dial's square and its spacing replace. */
		val SLIDER_HEIGHT = 18.dp
	}
}