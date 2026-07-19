package org.umamo.runtime.ingest

import org.umamo.runtime.model.AlphaBlendMode
import org.umamo.runtime.model.BlendMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the blend-mode bijections against the extraction tables in docs/plan/offscreen-support.md:
 * every CMO3 token and every MOC3 packed int maps to its distinct runtime mode, the two encodings
 * agree pairwise, and unknown values fall back to Normal/Over.
 */
class BlendModeMappingTest {
	/** One row of the extraction table: CMO3 token, MOC3 colorMode int, runtime mode. */
	private data class ColorRow(val token: String, val packedColor: Int, val mode: BlendMode)

	private val colorRows =
		listOf(
			ColorRow("NORMAL", 0, BlendMode.Normal),
			ColorRow("ADD", 1, BlendMode.Additive),
			ColorRow("MULTIPLY", 2, BlendMode.Multiply),
			ColorRow("ADD_R2_TSL", 3, BlendMode.AdditiveModern),
			ColorRow("ADD_R2", 4, BlendMode.AdditiveGlow),
			ColorRow("DARKEN", 5, BlendMode.Darken),
			ColorRow("MULTIPLY_R2", 6, BlendMode.MultiplyModern),
			ColorRow("COLORBURN_TSL", 7, BlendMode.ColorBurn),
			ColorRow("LINEARBURN_TSL", 8, BlendMode.LinearBurn),
			ColorRow("LIGHTEN", 9, BlendMode.Lighten),
			ColorRow("SCREEN", 10, BlendMode.Screen),
			ColorRow("COLORDODGE_TSL", 11, BlendMode.ColorDodge),
			ColorRow("OVERLAY", 12, BlendMode.Overlay),
			ColorRow("SOFTLIGHT", 13, BlendMode.SoftLight),
			ColorRow("HARDLIGHT", 14, BlendMode.HardLight),
			ColorRow("LINEARLIGHT_TSL", 15, BlendMode.LinearLight),
			ColorRow("HSL_HUE", 16, BlendMode.Hue),
			ColorRow("HSL_COLOR", 17, BlendMode.Color),
		)

	/** One row of the alpha table: CMO3 token, MOC3 alphaMode int, runtime mode. */
	private data class AlphaRow(val token: String, val packedAlpha: Int, val mode: AlphaBlendMode)

	private val alphaRows =
		listOf(
			AlphaRow("OVER", 0, AlphaBlendMode.Over),
			AlphaRow("ATOP", 1, AlphaBlendMode.Atop),
			AlphaRow("OUT", 2, AlphaBlendMode.Out),
			AlphaRow("CONJOINT", 3, AlphaBlendMode.Conjoint),
			AlphaRow("DISJOINT", 4, AlphaBlendMode.Disjoint),
		)

	@Test
	fun colorTableIsACompleteBijection() {
		assertEquals(BlendMode.entries.size, colorRows.size, "one row per BlendMode constant")
		assertEquals(colorRows.size, colorRows.map { it.mode }.toSet().size, "modes are distinct")
		for (row in colorRows) {
			assertEquals(row.mode, colorBlendOfToken(row.token), "token ${row.token}")
			assertEquals(row.mode, colorBlendOfPacked(row.packedColor), "packed colorMode ${row.packedColor}")
		}
	}

	@Test
	fun alphaTableIsACompleteBijection() {
		assertEquals(AlphaBlendMode.entries.size, alphaRows.size, "one row per AlphaBlendMode constant")
		assertEquals(alphaRows.size, alphaRows.map { it.mode }.toSet().size, "modes are distinct")
		for (row in alphaRows) {
			assertEquals(row.mode, alphaBlendOfToken(row.token), "token ${row.token}")
			assertEquals(row.mode, alphaBlendOfPacked(row.packedAlpha shl 8), "packed alphaMode ${row.packedAlpha}")
		}
	}

	@Test
	fun packedHalvesAreIndependent() {
		// The corpus-observed combinations: 522 = Screen or (Out shl 8) (the PartClipping drawable),
		// 262 = MultiplyModern or (Atop shl 8), 256 = Normal or (Atop shl 8) (Model A offscreens).
		assertEquals(BlendMode.Screen, colorBlendOfPacked(522))
		assertEquals(AlphaBlendMode.Out, alphaBlendOfPacked(522))
		assertEquals(BlendMode.MultiplyModern, colorBlendOfPacked(262))
		assertEquals(AlphaBlendMode.Atop, alphaBlendOfPacked(262))
		assertEquals(BlendMode.Normal, colorBlendOfPacked(256))
		assertEquals(AlphaBlendMode.Atop, alphaBlendOfPacked(256))
		assertEquals(BlendMode.Normal, colorBlendOfPacked(0))
		assertEquals(AlphaBlendMode.Over, alphaBlendOfPacked(0))
	}

	@Test
	fun unknownValuesFallBackToDefaults() {
		assertEquals(BlendMode.Normal, colorBlendOfToken(null))
		assertEquals(BlendMode.Normal, colorBlendOfToken("SOME_FUTURE_MODE"))
		assertEquals(AlphaBlendMode.Over, alphaBlendOfToken(null))
		assertEquals(AlphaBlendMode.Over, alphaBlendOfToken("SOME_FUTURE_MODE"))
		assertEquals(BlendMode.Normal, colorBlendOfPacked(99))
		assertEquals(AlphaBlendMode.Over, alphaBlendOfPacked(99 shl 8))
	}

	@Test
	fun legacyPredicateCoversExactlyThePreFiveThreeModes() {
		val legacyModes = BlendMode.entries.filter { it.isLegacy }
		assertEquals(listOf(BlendMode.Normal, BlendMode.Additive, BlendMode.Multiply), legacyModes)
		assertTrue(BlendMode.entries.filterNot { it.isLegacy }.size == 15, "fifteen 5.3 modes")
	}
}
