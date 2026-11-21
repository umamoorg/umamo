package org.umamo.ui.workspace.spaces.parameters

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import org.umamo.ui.kit.field.SLIDER_THUMB_RADIUS
import org.umamo.ui.theme.umamoDarkColors
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue

/** How far a sampled channel may sit from the expected one, in 8-bit steps. */
private const val CHANNEL_TOLERANCE = 3

/**
 * Pins the accent each parameter control draws in: a repeating parameter's dial in the repeat accent, every other
 * control in the plain one, and in a pair where one member repeats, each dial in its own member's.
 *
 * A pixel test on purpose: the row hands the color down to the kit control, and a call site that drops it draws in
 * the plain accent with nothing else to show for it.  Every sample sits on a stretch of fill that no key mark, hand,
 * or thumb covers.  The fixture renders the dark scheme.
 */
@OptIn(ExperimentalTestApi::class)
class ParametersRepeatAccentTest {
	private val colors = umamoDarkColors

	/** A repeating parameter's dial draws its arc in the repeat accent. */
	@Test
	fun aRepeatingDialDrawsInTheRepeatAccent() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.bodyX)))
			mountParametersPanel(harness)
			val dial = dialBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val panel = capturePanel()

			// Body X sits at 2 on -10..10, so the arc runs from 12 o'clock to past 6, where its one key and its hand are.
			assertColorNear(colors.repeatAccent, panel.colorAt(arcPoint(dial)), "a repeating dial's arc")
		}

	/** A slider that does not repeat keeps the plain accent. */
	@Test
	fun aSliderThatDoesNotRepeatDrawsInTheAccent() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel())
			mountParametersPanel(harness)
			val slider = sliderBox(harness, PanelRows.BODY_X, PanelValues.BODY_X)
			val panel = capturePanel()

			// The fill runs to the thumb at 0.6 of the track, and the key at 0 sits at the middle.
			assertColorNear(colors.accent, panel.colorAt(slider.at(0.25f)), "a slider's fill")
		}

	/** In a pair where only the upper member repeats, its dial takes the repeat accent and the other's keeps the plain one. */
	@Test
	fun aHalfRepeatPairColorsEachDialByItsOwnMember() =
		runComposeUiTest {
			val harness = ParametersPanelHarness(model = panelFixtureModel(repeating = setOf(PanelIds.angleX)))
			mountParametersPanel(harness)
			val (left, right) = pairDialBoxes(harness, PanelRows.ANGLE_PAD, PanelValues.ANGLE_X, PanelValues.ANGLE_Y)
			val panel = capturePanel()

			// Angle X sits at 3 and Angle Y at -4 on -30..30, so both arcs pass 3 o'clock, and neither axis has a key.
			assertColorNear(colors.repeatAccent, panel.colorAt(arcPoint(left)), "the repeating member's dial")
			assertColorNear(colors.accent, panel.colorAt(arcPoint(right)), "the member that does not repeat")
		}

	/**
	 * The point on a dial's ring at 3 o'clock, on the middle of its stroke.
	 *
	 * @param ControlBox dial The dial's square.
	 * @return Offset The point, in the panel body's pixels.
	 */
	private fun ComposeUiTest.arcPoint(dial: ControlBox): Offset {
		val halfWidth = dial.width / 2f
		val ringRadius = halfWidth - with(density) { SLIDER_THUMB_RADIUS.toPx() }

		return dial.atClock(0.25f, reach = ringRadius / halfWidth)
	}

	/**
	 * The panel body as it is drawn now.
	 *
	 * @return ImageBitmap The capture, in the panel body's pixels.
	 */
	private fun ComposeUiTest.capturePanel(): ImageBitmap = onNodeWithTag(PANEL_BODY_TAG).captureToImage()

	/**
	 * The color of one pixel of a capture.
	 *
	 * @param Offset point The pixel, in the capture's own pixels.
	 * @return Color The pixel's color.
	 */
	private fun ImageBitmap.colorAt(point: Offset): Color {
		val pixel = IntArray(1)
		readPixels(pixel, startX = point.x.roundToInt(), startY = point.y.roundToInt(), width = 1, height = 1)

		return Color(pixel[0])
	}

	/**
	 * Asserts that [actual] is [expected] within [CHANNEL_TOLERANCE] on each channel.
	 *
	 * @param Color expected The color the control should draw.
	 * @param Color actual The color sampled.
	 * @param String what What was sampled, for the failure message.
	 */
	private fun assertColorNear(expected: Color, actual: Color, what: String) {
		val channels = listOf(expected.red to actual.red, expected.green to actual.green, expected.blue to actual.blue)
		val near = channels.all { (want, got) -> abs(want - got) * 255f <= CHANNEL_TOLERANCE }
		assertTrue(near, "$what: expected $expected, sampled $actual")
	}
}