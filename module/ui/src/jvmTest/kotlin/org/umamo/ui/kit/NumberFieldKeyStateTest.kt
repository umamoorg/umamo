package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import org.umamo.ui.model.KeyedFieldState
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * That a keyed field state actually TINTS an enabled number field's border.
 *
 * A pixel test on purpose: the state once reached only the disabled-plain display branch, so every real
 * (enabled) numeric field silently dropped the work-loss warning the tint exists to carry - a wiring gap
 * no assertion over the state enum could see.
 */
class NumberFieldKeyStateTest {
	/** The keyed tint changes the enabled resting face's pixels; None leaves them untouched. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theKeyedTintRendersOnTheEnabledRestingFace() {
		val none = renderedPixels(KeyedFieldState.None)
		val onKey = renderedPixels(KeyedFieldState.OnKey)
		val modified = renderedPixels(KeyedFieldState.ModifiedUnkeyed)

		assertTrue(differing(none, onKey) > 0, "the OnKey border tint must change the enabled face")
		assertTrue(differing(none, modified) > 0, "the ModifiedUnkeyed warning tint must change the enabled face")
	}

	/** Renders one enabled number field with [keyState] and returns its pixels. */
	@OptIn(ExperimentalTestApi::class)
	private fun renderedPixels(keyState: KeyedFieldState): IntArray {
		var pixels: IntArray? = null
		runComposeUiTest {
			setContent {
				Box(modifier = Modifier.size(width = 160.dp, height = 40.dp).testTag("field")) {
					NumberField(
						value = 0.5f,
						onValueChange = {},
						range = 0f..1f,
						modifier = Modifier.size(width = 150.dp, height = 30.dp),
						keyState = keyState,
					)
				}
			}
			val image = onNodeWithTag("field").captureToImage()
			val captured = IntArray(image.width * image.height)
			image.readPixels(captured)
			pixels = captured
		}
		return requireNotNull(pixels)
	}

	/** The number of pixels two same-size renders differ in. */
	private fun differing(left: IntArray, right: IntArray): Int =
		left.indices.count { pixelIndex -> left[pixelIndex] != right[pixelIndex] }
}
