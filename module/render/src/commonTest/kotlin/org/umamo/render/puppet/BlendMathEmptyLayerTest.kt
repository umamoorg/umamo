package org.umamo.render.puppet

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * An offscreen composite runs over the FULL viewport, so where its layer has no coverage the layer's
 * source alpha is 0 and the composite must leave the destination untouched.  This was NOT true while
 * Out was misread as Porter-Duff SRC-out (destination factor 0): an empty Out layer wrote transparent
 * over every pixel and blanked the whole frame - which is what a deformer-hidden Out mesh (modelA's
 * hologram overlay at ParamHologram=0) did to the entire character.  The corrected DST-flavored Out
 * (Fa = 0, Fb = 1-as) preserves an untouched destination inherently, as do the other four modes.
 *
 * This pins the invariant at the reference level for every alpha mode; `CompositeSweepTest` then pins
 * the composite shader to this reference, so the two cannot drift.
 */
class BlendMathEmptyLayerTest {
	private val destinations =
		listOf(
			floatArrayOf(0.4f, 0.2f, 0.7f, 1.0f), // opaque backdrop (the grid / an opaque character pixel)
			floatArrayOf(0.1f, 0.1f, 0.1f, 0.5f), // semi-transparent
			floatArrayOf(0.0f, 0.0f, 0.0f, 0.0f), // fully transparent
		)

	@Test
	fun anEmptyLayerPreservesTheDestinationForEveryAlphaMode() {
		val emptySource = floatArrayOf(0f, 0f, 0f, 0f)
		for (alphaMode in AlphaBlendMode.entries) {
			for (colorMode in listOf(BlendMode.Normal, BlendMode.Multiply, BlendMode.Overlay, BlendMode.Color)) {
				for (destination in destinations) {
					val result = compositeReference(emptySource, destination, colorMode, alphaMode)
					for (channel in 0 until 4) {
						assertTrue(
							kotlin.math.abs(result[channel] - destination[channel]) < 1e-6f,
							"empty layer under $colorMode / $alphaMode must preserve the destination " +
								"(channel $channel: got ${result[channel]}, expected ${destination[channel]})",
						)
					}
				}
			}
		}
	}
}
