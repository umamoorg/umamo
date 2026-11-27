package org.umamo.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpRect
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoTheme
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** The test tag on the vertical label. */
private const val LABEL_TAG = "verticalLabel"

/**
 * The vertical label as drawn: its column is the line height wide and as long as the label set vertically,
 * Latin turns clockwise, and a label cut short still carries its whole text for tests and screen readers.
 */
class VerticalTextRenderTest {
	/**
	 * The width and height of a label's bounds.
	 *
	 * @param DpRect bounds The bounds.
	 * @return Pair The width and the height.
	 */
	private fun sizeOf(bounds: DpRect): Pair<Dp, Dp> = (bounds.right - bounds.left) to (bounds.bottom - bounds.top)

	/** Japanese stands upright, one em per character down a column the label style's line height wide. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun japaneseStacksOneEmPerCharacter() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalDensity provides Density(1f)) {
						VerticalText(text = "ツール", modifier = Modifier.testTag(LABEL_TAG))
					}
				}
			}
			waitForIdle()
			val (width, height) = sizeOf(onNodeWithTag(LABEL_TAG).getUnclippedBoundsInRoot())
			// labelMedium: 12 sp on a 16 sp line, at density 1.
			assertEquals(16.dp, width, "the column is the line height wide")
			assertEquals(36.dp, height, "three characters, one 12 px em each")
		}

	/** Latin lies on its side: the column is the line height wide and as long as the word is wide when set flat. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun latinSwapsItsWidthAndHeight() =
		runComposeUiTest {
			var flatWidth = 0
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalDensity provides Density(1f)) {
						Column {
							VerticalText(text = "Item", modifier = Modifier.testTag(LABEL_TAG))
							BasicText(text = "Item", style = LocalUmamoTypography.current.labelMedium, onTextLayout = { layout -> flatWidth = layout.size.width })
						}
					}
				}
			}
			waitForIdle()
			val (width, height) = sizeOf(onNodeWithTag(LABEL_TAG).getUnclippedBoundsInRoot())
			assertEquals(16.dp, width, "the column is the line height wide, the same as a Japanese label's")
			assertTrue(abs(height.value - flatWidth) <= 1f, "the column is as long as the word is wide set flat ($height vs $flatWidth)")
		}

	/**
	 * Latin turns clockwise: an L set on its side reads as a Γ, its ink gathered top-left.  Turned the other
	 * way it would gather bottom-right.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun latinTurnsClockwise() =
		runComposeUiTest {
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalDensity provides Density(1f)) {
						val style = LocalUmamoTypography.current.labelMedium.copy(fontSize = 96.sp, lineHeight = 120.sp)
						Box(modifier = Modifier.background(Color.White).testTag(LABEL_TAG)) {
							VerticalText(text = "L", style = style, color = Color.Black)
						}
					}
				}
			}
			waitForIdle()
			val image = onNodeWithTag(LABEL_TAG).captureToImage()
			val pixels = IntArray(image.width * image.height)
			image.readPixels(pixels)
			var sumX = 0.0
			var sumY = 0.0
			var count = 0
			var left = image.width
			var right = -1
			var top = image.height
			var bottom = -1

			for (rowIndex in 0 until image.height) {
				for (columnIndex in 0 until image.width) {
					val green = (pixels[rowIndex * image.width + columnIndex] shr 8) and 0xFF

					if (green < 128) {
						sumX += columnIndex
						sumY += rowIndex
						count++
						left = minOf(left, columnIndex)
						right = maxOf(right, columnIndex)
						top = minOf(top, rowIndex)
						bottom = maxOf(bottom, rowIndex)
					}
				}
			}

			assertTrue(count > 0, "the L drew no ink")
			val inkCenterX = (left + right) / 2f
			val inkCenterY = (top + bottom) / 2f
			assertTrue((sumX / count) < inkCenterX, "the turned L's ink gathers on the left (a Γ)")
			assertTrue((sumY / count) < inkCenterY, "the turned L's ink gathers at the top (a Γ)")
		}

	/** A label cut short still carries its whole text, and reports the cut. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun aCutLabelKeepsItsWholeTextAndReportsTheCut() =
		runComposeUiTest {
			var longCut: Boolean? = null
			var shortCut: Boolean? = null
			setContent {
				UmamoTheme {
					CompositionLocalProvider(LocalDensity provides Density(1f)) {
						Column {
							VerticalText(
								text = "Toolbox Settings",
								modifier = Modifier.testTag(LABEL_TAG),
								maxLength = 40.dp,
								onTruncation = { truncated -> longCut = truncated },
							)
							VerticalText(text = "View", maxLength = 40.dp, onTruncation = { truncated -> shortCut = truncated })
						}
					}
				}
			}
			waitForIdle()
			onNodeWithText("Toolbox Settings").assertExists()
			val (_, height) = sizeOf(onNodeWithTag(LABEL_TAG).getUnclippedBoundsInRoot())
			assertTrue(height <= 40.dp, "the cut label fits its maximum ($height)")
			assertEquals(true, longCut, "the long label reports the cut")
			assertEquals(false, shortCut, "the short label reports none")
		}
}