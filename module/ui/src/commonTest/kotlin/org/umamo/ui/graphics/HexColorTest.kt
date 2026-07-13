package org.umamo.ui.graphics

import androidx.compose.ui.graphics.Color
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies hex color parsing (8-digit AARRGGBB, 6-digit opaque RRGGBB, tolerant of '#' and whitespace,
 * strict against everything else) and that formatting round-trips to the canonical form.
 */
class HexColorTest {
	/** An 8-digit string parses as AARRGGBB, with or without the leading '#'. */
	@Test
	fun parsesEightDigitArgb() {
		assertEquals(Color(0xCCFF9800), parseHexColor("#CCFF9800"))
		assertEquals(Color(0xCCFF9800), parseHexColor("CCFF9800"))
		assertEquals(Color(0xCCFF9800), parseHexColor(" #ccff9800 "), "case- and whitespace-insensitive")
	}

	/** A 6-digit string parses as opaque RRGGBB. */
	@Test
	fun parsesSixDigitRgbAsOpaque() {
		assertEquals(Color(0xFF2962FF), parseHexColor("#2962FF"))
		assertEquals(Color(0xFF2962FF), parseHexColor("2962ff"))
	}

	/** Wrong lengths, non-hex characters, signs, and empty input are rejected. */
	@Test
	fun rejectsInvalidInput() {
		assertNull(parseHexColor(""))
		assertNull(parseHexColor("#"))
		assertNull(parseHexColor("#12345"), "five digits")
		assertNull(parseHexColor("#1234567"), "seven digits")
		assertNull(parseHexColor("#123456789"), "nine digits")
		assertNull(parseHexColor("#GGHHIIJJ"), "non-hex characters")
		assertNull(parseHexColor("-2962FF0"), "a sign is not a hex digit")
		assertNull(parseHexColor("#2962 FF"), "interior whitespace")
	}

	/** Formatting produces the canonical uppercase "#AARRGGBB", and a parse-format cycle round-trips. */
	@Test
	fun formatsCanonicallyAndRoundTrips() {
		assertEquals("#CCFF9800", formatHexColor(Color(0xCCFF9800)))
		assertEquals("#FF2962FF", formatHexColor(parseHexColor("2962ff")!!))
		assertEquals("#66888888", formatHexColor(parseHexColor("#66888888")!!))
	}
}
