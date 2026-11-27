package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.sp
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.theme.rememberCjkFontFamily
import kotlin.test.Test
import kotlin.test.assertTrue

/** The test tag on the box the glyph is drawn in. */
private const val GLYPH_TAG = "glyph"

/** The font size the glyphs are drawn at, large enough that their ink is measured to the pixel. */
private const val GLYPH_FONT_SIZE = 96f

/**
 * Where a glyph's ink lies within the box it was drawn in.
 *
 * @property Float boxWidth The box's width in px (the glyph's advance).
 * @property Float baseline The baseline's distance from the box's top, in px.
 * @property Int inkWidth The width of the ink's bounding box, in px.
 * @property Int inkHeight The height of the ink's bounding box, in px.
 * @property Float centroidX The ink's mean x, in px from the box's left.
 * @property Float centroidY The ink's mean y, in px from the box's top.
 */
private class GlyphInk(
	val boxWidth: Float,
	val baseline: Float,
	val inkWidth: Int,
	val inkHeight: Int,
	val centroidX: Float,
	val centroidY: Float,
)

/**
 * The OpenType vertical-forms feature reaches the bundled CJK font through the desktop text stack.
 *
 * Vertical labels keep CJK upright and stack it one glyph per cell, which only reads right when ー, the small
 * kana, and the CJK punctuation take the vertical forms the font carries: shaped horizontally they are the
 * horizontal forms, a dash across the column and a stop in the wrong corner.  These cases draw one glyph
 * with and without the feature and look at where its ink lands, so a toolkit upgrade that stops passing the
 * feature on fails here rather than in a label nobody looks at.
 *
 * The family is the CJK cut alone: through the UI family, where these glyphs are reached by falling back from
 * Inter, the feature turns them into unrelated ideographs.
 */
class VerticalFormsFeatureTest {
	/**
	 * Draws [glyph] black on white at [GLYPH_FONT_SIZE] in the CJK family vertical labels shape their upright
	 * cells in, and measures its ink.
	 *
	 * @param String glyph The text to draw.
	 * @param String? fontFeatureSettings The OpenType features to shape it with, or null for none.
	 * @return GlyphInk Where the ink lies.
	 */
	@OptIn(ExperimentalTestApi::class)
	private fun ComposeUiTest.inkOf(glyph: String, fontFeatureSettings: String?): GlyphInk {
		var baseline = 0f
		setContent {
			UmamoTheme {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					val style =
						LocalUmamoTypography.current.labelMedium.copy(
							fontSize = GLYPH_FONT_SIZE.sp,
							lineHeight = (GLYPH_FONT_SIZE * 1.25f).sp,
							color = Color.Black,
							fontFamily = rememberCjkFontFamily(),
							fontFeatureSettings = fontFeatureSettings,
						)
					Box(modifier = Modifier.background(Color.White).testTag(GLYPH_TAG)) {
						BasicText(text = glyph, style = style, onTextLayout = { layout -> baseline = layout.firstBaseline })
					}
				}
			}
		}
		waitForIdle()
		val image = onNodeWithTag(GLYPH_TAG).captureToImage()
		val pixels = IntArray(image.width * image.height)
		image.readPixels(pixels)
		var left = image.width
		var right = -1
		var top = image.height
		var bottom = -1
		var sumX = 0.0
		var sumY = 0.0
		var count = 0

		for (rowIndex in 0 until image.height) {
			for (columnIndex in 0 until image.width) {
				val pixel = pixels[rowIndex * image.width + columnIndex]
				val green = (pixel shr 8) and 0xFF

				if (green < 128) {
					left = minOf(left, columnIndex)
					right = maxOf(right, columnIndex)
					top = minOf(top, rowIndex)
					bottom = maxOf(bottom, rowIndex)
					sumX += columnIndex
					sumY += rowIndex
					count++
				}
			}
		}

		assertTrue(count > 0, "\"$glyph\" drew no ink")

		return GlyphInk(
			boxWidth = image.width.toFloat(),
			baseline = baseline,
			inkWidth = right - left + 1,
			inkHeight = bottom - top + 1,
			centroidX = (sumX / count).toFloat(),
			centroidY = (sumY / count).toFloat(),
		)
	}

	/** The prolonged sound mark is a vertical stroke with the feature and a horizontal one without it. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theProlongedSoundMarkTurnsVertical() {
		runComposeUiTest {
			val vertical = inkOf("ー", "vert")
			assertTrue(
				vertical.inkHeight >= 2 * vertical.inkWidth,
				"with vert, ー is a vertical stroke (ink ${vertical.inkWidth} x ${vertical.inkHeight})",
			)
		}
		runComposeUiTest {
			val horizontal = inkOf("ー", null)
			assertTrue(
				horizontal.inkWidth >= 2 * horizontal.inkHeight,
				"without vert, ー is a horizontal stroke (ink ${horizontal.inkWidth} x ${horizontal.inkHeight})",
			)
		}
	}

	/**
	 * The ideographic full stop moves to the top-right of its em box with the feature.  A rotated stop would
	 * land top-left and the horizontal one sits bottom-left, so only the vertical form passes both halves.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theFullStopMovesToTheTopRight() {
		runComposeUiTest {
			val stop = inkOf("。", "vert")
			// The em box spans 0.88 em above the baseline to 0.12 em below it (Noto Sans CJK's typo metrics).
			val emBoxCenterY = stop.baseline - 0.38f * GLYPH_FONT_SIZE
			assertTrue(stop.centroidX > stop.boxWidth / 2f, "with vert, 。 sits right of center (x ${stop.centroidX} of ${stop.boxWidth})")
			assertTrue(stop.centroidY < emBoxCenterY, "with vert, 。 sits above the em box's center (y ${stop.centroidY}, center $emBoxCenterY)")
		}
	}
}