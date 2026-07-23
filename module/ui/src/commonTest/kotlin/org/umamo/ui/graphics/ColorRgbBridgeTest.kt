package org.umamo.ui.graphics

import androidx.compose.ui.graphics.Color
import org.umamo.runtime.model.ColorRgb
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the [ColorRgb] <-> Compose [Color] bridge the composite color pickers ride on: the identities map
 * to opaque white / black (and their canonical hex), an arbitrary color round-trips within float
 * tolerance, and the Color -> ColorRgb direction drops alpha.
 */
class ColorRgbBridgeTest {
	@Test
	fun identitiesMapToOpaqueWhiteAndBlack() {
		assertEquals(Color(1f, 1f, 1f, 1f), ColorRgb.MultiplyIdentity.toComposeColor())
		assertEquals(Color(0f, 0f, 0f, 1f), ColorRgb.ScreenIdentity.toComposeColor())
		// Via the existing hex helpers - the canonical #AARRGGBB the field displays.
		assertEquals("#FFFFFFFF", formatHexColor(ColorRgb.MultiplyIdentity.toComposeColor()))
		assertEquals("#FF000000", formatHexColor(ColorRgb.ScreenIdentity.toComposeColor()))
	}

	@Test
	fun composeColorDropsAlphaBackToColorRgb() {
		// A half-transparent red still yields the opaque-channel ColorRgb (alpha is not modeled).
		assertEquals(ColorRgb(1f, 0f, 0f), Color(1f, 0f, 0f, 0.5f).toColorRgb())
		// Parsed hex round-trips exactly at 8-bit boundaries.
		assertEquals(ColorRgb(1f, 0f, 0f), parseHexColor("#FF0000")!!.toColorRgb())
	}

	@Test
	fun arbitraryColorRoundTripsWithinTolerance() {
		val original = ColorRgb(0.25f, 0.5f, 0.75f)
		val roundTripped = original.toComposeColor().toColorRgb()
		assertEquals(original.red, roundTripped.red, 0.005f)
		assertEquals(original.green, roundTripped.green, 0.005f)
		assertEquals(original.blue, roundTripped.blue, 0.005f)
	}
}
