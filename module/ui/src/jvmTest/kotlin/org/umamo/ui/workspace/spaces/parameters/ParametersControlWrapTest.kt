package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.field.CONTROL_WRAP_DETENT
import org.umamo.ui.kit.field.SLIDER_THUMB_RADIUS
import org.umamo.ui.settings.ParametersSettings
import org.umamo.ui.settings.RepeatControlSetting
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the wrap of a repeating parameter's slider and pad under the slider preference: past an end by more than the
 * detent the value comes in at the other end and the cursor is asked to the thumb, the drag goes on from the landing,
 * inside the detent the value holds at max, a platform that cannot warp still wraps, a held-Ctrl snap stays inside the
 * lap, and a pad wraps only the axis that repeats.  The fixture's warp records every request and lands it exactly.
 *
 * Body X runs -10..10 and starts at 2.  Angle X and Angle Y run -30..30 and start at 3 and -4.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersControlWrapTest {
	/** Past max by more than the detent, the value comes in at min, the cursor goes to the thumb, and the drag goes on. */
	@Test
	fun aDragPastMaxComesInAtMinAndTheCursorGoesToTheThumb() =
		runComposeUiTest {
			val harness = repeatingBodyX()
			mountParametersPanel(harness)
			val band = sliderBand(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X))
			val cursorBefore = harness.historyCursor

			pressAndMove(band.at(0.5f), listOf(band.pastMax(PAST)))

			assertNear(band.valueAt(band.start + PAST), harness.live(PanelIds.bodyX), "the value comes in at min, as far on as the push went")
			assertEquals(1, harness.cursorWarps.requests.size, "one crossing asks for one warp")
			val landing = panelPointOfScreen(harness.cursorWarps.requests.single())
			assertEquals(band.start + PAST, landing.x, 0.5f, "the cursor is asked to the thumb")
			assertEquals(band.centerY, landing.y, 0.5f, "at the height it was")

			moveOn(listOf(landing + Offset(MORE, 0f)))

			assertNear(band.valueAt(band.start + PAST + MORE), harness.live(PanelIds.bodyX), "the drag goes on from the landing")
			assertEquals(1, harness.cursorWarps.requests.size)

			releasePress()

			assertNear(band.valueAt(band.start + PAST + MORE), harness.committed(PanelIds.bodyX), "the release commits where it got to")
			assertEquals(cursorBefore + 1, harness.historyCursor, "the whole wrapping drag is one step")
		}

	/** On a platform that cannot warp, the value wraps under the pointer, which keeps going. */
	@Test
	fun withNoWarpTheValueStillWrapsUnderThePointer() =
		runComposeUiTest {
			val harness = repeatingBodyX()
			harness.cursorWarps.lands = false
			mountParametersPanel(harness)
			val band = sliderBand(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X))

			pressAndMove(band.at(0.5f), listOf(band.pastMax(PAST)))
			assertNear(band.valueAt(band.start + PAST), harness.live(PanelIds.bodyX), "it comes in at min all the same")

			moveOn(listOf(band.pastMax(PAST + MORE)))
			releasePress()

			assertNear(band.valueAt(band.start + PAST + MORE), harness.committed(PanelIds.bodyX), "and follows the pointer on round")
		}

	/** Pushed past max but not past the detent, the value holds at exactly max (D7). */
	@Test
	fun insideTheDetentTheValueHoldsAtMax() =
		runComposeUiTest {
			val harness = repeatingBodyX()
			mountParametersPanel(harness)
			val band = sliderBand(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X))

			drag(band.at(0.5f), listOf(Offset(band.end + band.detent - 1f, band.centerY)))

			assertEquals(10f, harness.committed(PanelIds.bodyX), "a hand resting past the end keeps max")
			assertTrue(harness.cursorWarps.requests.isEmpty(), "and nothing warps")
		}

	/** With Ctrl held just past a crossing, a key at min catches the value. */
	@Test
	fun aCtrlDragJustPastACrossingLandsOnAKeyAtMin() =
		runComposeUiTest {
			val harness = repeatingBodyX(keys = floatArrayOf(-10f, 10f))
			mountParametersPanel(harness)
			val band = sliderBand(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X))

			setCtrlHeld(true)
			drag(band.at(0.5f), listOf(band.pastMax(NEAR_THE_SEAM)))
			setCtrlHeld(false)

			assertEquals(-10f, harness.committed(PanelIds.bodyX))
		}

	/** A key at max does not pull a value just past the crossing back over the seam: the snap stays inside the lap. */
	@Test
	fun aKeyAtMaxDoesNotPullACrossingBack() =
		runComposeUiTest {
			val harness = repeatingBodyX(keys = floatArrayOf(10f))
			mountParametersPanel(harness)
			val band = sliderBand(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X))

			setCtrlHeld(true)
			drag(band.at(0.5f), listOf(band.pastMax(NEAR_THE_SEAM)))
			setCtrlHeld(false)

			assertNear(band.valueAt(band.start + NEAR_THE_SEAM), harness.committed(PanelIds.bodyX), "the value stays just past min")
		}

	/** A pad whose upper axis repeats wraps that axis past its edge and holds the other at its own. */
	@Test
	fun aHalfRepeatPadWrapsOneAxisAndClampsTheOther() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.angleX)))
			harness.preferSliders()
			mountParametersPanel(harness)
			val pad = padBox(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val detent = with(density) { CONTROL_WRAP_DETENT.toPx() }
			val above = pad.top - 40f

			pressAndMove(padPointOf(pad, 0f, 0f), listOf(Offset(pad.right + detent + PAST, above)))

			assertNear(-30f + 60f * PAST / pad.width, harness.live(PanelIds.angleX), "Angle X comes in at its left edge")
			assertEquals(30f, harness.live(PanelIds.angleY), "Angle Y stops at the top")
			val landing = panelPointOfScreen(harness.cursorWarps.requests.single())
			assertEquals(pad.left + PAST, landing.x, 1f, "the cursor goes to the handle along x")
			assertEquals(above, landing.y, 0.5f, "and stays where it was along y")

			releasePress()
		}

	/** A slider that does not repeat stops at its end and never asks for a warp. */
	@Test
	fun aSliderThatDoesNotRepeatNeverWarps() =
		runComposeUiTest {
			val harness = ParametersPanelHarness()
			harness.preferSliders()
			mountParametersPanel(harness)
			val band = sliderBand(sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X))

			drag(band.at(0.5f), listOf(band.pastMax(PAST)))

			assertEquals(10f, harness.committed(PanelIds.bodyX))
			assertTrue(harness.cursorWarps.requests.isEmpty())
		}

	/**
	 * The fixture with Body X repeating, under the slider preference.
	 *
	 * @param FloatArray keys Body X's keys.
	 * @return ParametersPanelHarness The harness, not yet mounted.
	 */
	private fun repeatingBodyX(keys: FloatArray = floatArrayOf(0f)): ParametersPanelHarness {
		val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX), bodyXKeys = keys))
		harness.preferSliders()

		return harness
	}

	/** Writes the slider preference into the harness's settings before the panel mounts. */
	private fun ParametersPanelHarness.preferSliders() {
		settings.setString(ParametersSettings.REPEAT_CONTROL_KEY, RepeatControlSetting.Slider.key)
	}

	/**
	 * The band a slider's thumb travels, for Body X's -10..10.
	 *
	 * @param ControlBox slider The slider's box.
	 * @return SliderBand The band.
	 */
	private fun ComposeUiTest.sliderBand(slider: ControlBox): SliderBand =
		with(density) {
			val inset = SLIDER_THUMB_RADIUS.toPx()
			SliderBand(start = slider.left + inset, end = slider.right - inset, centerY = slider.center.y, detent = CONTROL_WRAP_DETENT.toPx())
		}

	/**
	 * The band a slider's thumb travels, in the panel body's pixels.
	 *
	 * @property Float start Where min sits.
	 * @property Float end Where max sits.
	 * @property Float centerY The slider's middle.
	 * @property Float detent The wrap detent.
	 */
	private class SliderBand(val start: Float, val end: Float, val centerY: Float, val detent: Float) {
		/**
		 * A point a part of the way along the band.
		 *
		 * @param Float fraction The part, 0 at min.
		 * @return Offset The point.
		 */
		fun at(fraction: Float): Offset = Offset(start + (end - start) * fraction, centerY)

		/**
		 * A point past max by the detent and [beyond] more, where the pointer has crossed by [beyond].
		 *
		 * @param Float beyond How far past the detent.
		 * @return Offset The point.
		 */
		fun pastMax(beyond: Float): Offset = Offset(end + detent + beyond, centerY)

		/**
		 * Body X's value at [x] along the band.
		 *
		 * @param Float x The point's x.
		 * @return Float The value.
		 */
		fun valueAt(x: Float): Float = -10f + 20f * (x - start) / (end - start)
	}

	private companion object {
		/** How far past the detent a crossing push goes, in pixels. */
		const val PAST = 30f

		/** How much farther the drag goes after the landing, in pixels. */
		const val MORE = 20f

		/** A crossing that stops a hair past min, inside a key's snap reach. */
		const val NEAR_THE_SEAM = 3f
	}
}