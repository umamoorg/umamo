package org.umamo.ui.workspace.spaces.uv

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import org.umamo.render.ViewportCamera
import org.umamo.ui.graphics.RgbaAlphaType
import org.umamo.ui.graphics.rgbaToImageBitmap
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.UmamoTheme
import org.umamo.ui.viewport.RenderedFrame
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins what the UV editor's underlay shows on the pixels: the engine's whole frame across the whole area,
 * nothing clipped, since the engine paints the surround, the border, and the wireframe itself; and before
 * the first frame, the backdrop color.  The frame here is one flat color, so every pixel of the area must
 * read it, the corners far outside a page included.
 */
@OptIn(ExperimentalTestApi::class)
class UvPageUnderlayTest {
	private val side = 100

	// Opaque magenta, unlike every theme color.
	private val frameArgb = 0xFFFF00FF.toInt()

	@Test
	fun theWholeFrameShowsOutsideThePage() =
		runComposeUiTest {
			// A camera that would put a 16-texel page in the middle 32 pixels: the corners are well outside it.
			val rendered = RenderedFrame(solidFrame(), ViewportCamera(8f, 8f, 2f))
			setContent {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					UmamoTheme {
						Box(modifier = Modifier.size(side.dp).testTag("underlay")) {
							UvPageUnderlay(rendered = rendered)
						}
					}
				}
			}

			val pixels = capture()

			assertEquals(frameArgb, pixels[5 * side + 5], "a corner far outside the page shows the engine's frame")
			assertEquals(frameArgb, pixels[(side / 2) * side + side / 2], "and so does the middle")
		}

	@Test
	fun beforeTheFirstFrameTheAreaIsTheBackdrop() =
		runComposeUiTest {
			var backdropArgb = 0
			setContent {
				CompositionLocalProvider(LocalDensity provides Density(1f)) {
					UmamoTheme {
						backdropArgb = LocalUmamoColors.current.viewportGridBackground.toArgb()
						Box(modifier = Modifier.size(side.dp).testTag("underlay")) {
							UvPageUnderlay(rendered = null)
						}
					}
				}
			}

			val pixels = capture()

			assertEquals(backdropArgb, pixels[(side / 2) * side + side / 2], "the theme's viewport backdrop")
		}

	/**
	 * The underlay's pixels, row by row.
	 *
	 * @return IntArray ARGB per pixel.
	 */
	private fun ComposeUiTest.capture(): IntArray {
		val image = onNodeWithTag("underlay").captureToImage()
		val pixels = IntArray(image.width * image.height)
		image.readPixels(pixels)
		return pixels
	}

	/**
	 * A frame of one flat color at the area's size.
	 *
	 * @return ImageBitmap The frame.
	 */
	private fun solidFrame(): ImageBitmap =
		rgbaToImageBitmap(
			ByteArray(side * side * 4) { byteIndex ->
				when (byteIndex % 4) {
					1 -> 0
					else -> 0xFF.toByte()
				}
			},
			side,
			side,
			RgbaAlphaType.Opaque,
		)
}