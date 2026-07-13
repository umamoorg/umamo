package org.umamo.ui.graphics

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb

/**
 * Parses a hex color string into a Compose color: 8 hex digits are AARRGGBB, 6 are RRGGBB with an opaque
 * alpha.  A leading '#' and surrounding whitespace are tolerated.  Returns null for anything else (wrong
 * length, non-hex characters, empty), so callers can fall back to a default rather than render garbage.
 *
 * @param String text The hex string to parse (e.g. "#CCFF9800" or "2962FF").
 * @return Color? The parsed color, or null when [text] is not a valid hex color.
 */
fun parseHexColor(text: String): Color? {
	val cleaned = text.trim().removePrefix("#")
	// Reject signs and whitespace that toLongOrNull would tolerate: hex digits only.
	if (!cleaned.all { character -> character.isDigit() || character in 'a'..'f' || character in 'A'..'F' }) {
		return null
	}
	val packed = cleaned.toLongOrNull(16) ?: return null
	return when (cleaned.length) {
		8 -> Color(packed and 0xFFFFFFFFL)
		6 -> Color(packed or 0xFF000000L)
		else -> null
	}
}

/**
 * Formats a color as the canonical uppercase "#AARRGGBB" hex string [parseHexColor] accepts, so a parsed
 * and re-formatted value round-trips exactly (the settings layer stores this canonical form).
 *
 * @param Color color The color to format.
 * @return String The canonical "#AARRGGBB" string.
 */
fun formatHexColor(color: Color): String {
	val packed = color.toArgb().toLong() and 0xFFFFFFFFL
	return "#" + packed.toString(16).uppercase().padStart(8, '0')
}
