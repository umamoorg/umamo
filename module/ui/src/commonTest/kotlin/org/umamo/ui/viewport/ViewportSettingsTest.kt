package org.umamo.ui.viewport

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * The selection-highlight parser: both hex widths must land on the same RGB because
 * defaultSettings.json seeds #RRGGBB while the preferences HexColorField commits canonical
 * #AARRGGBB - a 6-digit-only parser would silently ignore every edit made in the window.
 */
class ViewportSettingsTest {
	private val defaultComponents = parseSelectionHighlightColor(ViewportSettings.SELECTION_HIGHLIGHT_DEFAULT)

	@Test
	fun sixDigitHexParses() {
		val (red, green, blue) = parseSelectionHighlightColor("#00FF00")
		assertEquals(0f, red)
		assertEquals(1f, green)
		assertEquals(0f, blue)
	}

	@Test
	fun eightDigitHexParsesWithAlphaIgnored() {
		assertEquals(
			parseSelectionHighlightColor("#00FF00"),
			parseSelectionHighlightColor("#8000FF00"),
			"the alpha byte is masked off",
		)
		assertEquals(defaultComponents, parseSelectionHighlightColor("#FF338CFF"), "the canonical form of the default")
	}

	@Test
	fun leadingHashIsOptional() {
		assertEquals(parseSelectionHighlightColor("#00FF00"), parseSelectionHighlightColor("00FF00"))
	}

	@Test
	fun malformedOrAbsentValuesFallBackToTheDefault() {
		assertEquals(defaultComponents, parseSelectionHighlightColor(null))
		assertEquals(defaultComponents, parseSelectionHighlightColor(""))
		assertEquals(defaultComponents, parseSelectionHighlightColor("#12345"), "wrong digit count")
		assertEquals(defaultComponents, parseSelectionHighlightColor("#GGGGGG"), "non-hex digits")
	}
}
