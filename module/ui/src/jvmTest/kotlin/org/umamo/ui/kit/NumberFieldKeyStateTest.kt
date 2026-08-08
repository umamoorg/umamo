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
 * That a keyed field state actually TINTS an enabled number field.
 *
 * A pixel test on purpose: the state once reached only the disabled-plain display branch, so every real
 * (enabled) numeric field silently dropped the work-loss warning the tint exists to carry - a wiring gap
 * no assertion over the state enum could see.  The tint is a background fill rather than an outline now,
 * which does not change what this checks: that the state reaches the pixels at all.
 */
class NumberFieldKeyStateTest {
	/** The keyed tint changes the enabled resting face's pixels; None leaves them untouched. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theKeyedTintRendersOnTheEnabledRestingFace() {
		val none = renderedPixels(KeyedFieldState.None)
		val onKey = renderedPixels(KeyedFieldState.OnKey)
		val modified = renderedPixels(KeyedFieldState.ModifiedUnkeyed)

		assertTrue(differing(none, onKey) > 0, "the OnKey tint must change the enabled face")
		assertTrue(differing(none, modified) > 0, "the ModifiedUnkeyed warning tint must change the enabled face")
	}

	/**
	 * A fill covers far more of the field than the outline did, so the tint must survive the field's own
	 * fills rather than being painted under them.
	 *
	 * Guards the ordering: the magnitude fill and the resting background are both drawn by the same control,
	 * and a tint layered beneath either of them is invisible in exactly the half of the field the value
	 * occupies - which reads as "the tint is broken for some values" rather than as a stacking mistake.
	 */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun theTintCoversTheFieldRatherThanItsEdge() {
		val none = renderedPixels(KeyedFieldState.None)
		val onKey = renderedPixels(KeyedFieldState.OnKey)

		// A 1px outline on a 150x30 field is under 10% of it; a fill is nearly all of it.
		assertTrue(differing(none, onKey) > none.size / 2, "the tint must fill the field, not outline it")
	}

	/** The type-in face carries the same tint, so clicking a keyed field to type does not flash it away. */
	@OptIn(ExperimentalTestApi::class)
	@Test
	fun thePlainTypeInFaceIsTintedToo() {
		val none = renderedPixels(KeyedFieldState.None, plain = true)
		val onKey = renderedPixels(KeyedFieldState.OnKey, plain = true)

		assertTrue(differing(none, onKey) > 0, "the type-in face must carry the keyed tint as well")
	}

	/** Renders one enabled number field with [keyState] and returns its pixels. */
	@OptIn(ExperimentalTestApi::class)
	private fun renderedPixels(keyState: KeyedFieldState, plain: Boolean = false): IntArray {
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
						plain = plain,
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