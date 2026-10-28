package org.umamo.ui.action

/**
 * Renders [chord] as a human accelerator hint for a menu row (e.g. "Ctrl+Shift+P", "⌘O").  The primary
 * modifier resolves per platform via [primaryModifierLabel] (⌘ on macOS, Ctrl elsewhere); the rest is
 * pure and position-based.
 *
 * chord をメニュー行のアクセラレータ表記に整形する。主修飾キーはプラットフォーム依存で解決する。
 *
 * @param KeyChord chord The chord to format.
 * @return String The display hint.
 */
fun formatAccelerator(chord: KeyChord): String = formatAccelerator(chord, primaryModifierLabel())

/**
 * The pure core of [formatAccelerator]: assembles the modifier labels (literal Control, primary, Alt, Shift)
 * and the key glyph into a "+"-joined hint, taking the platform-specific [primaryLabel] as a parameter so it
 * can be unit-tested deterministically (the public overload supplies the host's label).  Modifiers are
 * ordered Control, primary, Alt, Shift to match the common platform convention.
 *
 * A literal Control only ever appears on macOS, where Control is not the primary modifier, so it always
 * reads as the Control glyph "⌃".
 *
 * @param KeyChord chord The chord to format.
 * @param String primaryLabel The platform label for the primary modifier ("Ctrl" or "⌘").
 * @return String The display hint.
 */
internal fun formatAccelerator(chord: KeyChord, primaryLabel: String): String {
	val parts =
		buildList {
			if (chord.controlKey) {
				add("⌃")
			}

			if (chord.primaryModifier) {
				add(primaryLabel)
			}

			if (chord.alt) {
				add("Alt")
			}

			if (chord.shift) {
				add("Shift")
			}

			add(keyGlyph(chord.keyName))
		}

	return parts.joinToString(separator = "+")
}

/**
 * Maps a position-based key token to its display glyph: "KeyO" to "O", "Digit0" to "0", and the few
 * named keys to short symbols.  An unrecognized token falls back to itself so a newly bound key still
 * shows something readable rather than vanishing.
 *
 * 位置ベースのキートークンを表示用の字形に変換する。未知のトークンはそのまま返す。
 *
 * @param String keyName The position-based key token (e.g. "KeyO").
 * @return String The display glyph.
 */
internal fun keyGlyph(keyName: String): String =
	when (keyName) {
		"Space" -> {
			"Space"
		}

		"Escape" -> {
			"Esc"
		}

		"Equals" -> {
			"="
		}

		"Minus" -> {
			"−"
		}

		"PageUp" -> {
			"Page Up"
		}

		"PageDown" -> {
			"Page Down"
		}

		"NumpadAdd" -> {
			"Num +"
		}

		"NumpadSubtract" -> {
			"Num −"
		}

		"NumpadMultiply" -> {
			"Num *"
		}

		"NumpadDivide" -> {
			"Num /"
		}

		"NumpadDecimal" -> {
			"Num ."
		}

		"NumpadEnter" -> {
			"Num Enter"
		}

		else -> {
			when {
				keyName.startsWith("Key") -> keyName.removePrefix("Key")

				keyName.startsWith("Digit") -> keyName.removePrefix("Digit")

				// The remaining numpad tokens are the digits: "Numpad7" reads as "Num 7".
				keyName.startsWith("Numpad") -> "Num " + keyName.removePrefix("Numpad")

				else -> keyName
			}
		}
	}

/**
 * The platform's label for the primary accelerator modifier: "⌘" on macOS, "Ctrl" elsewhere.  This is
 * the one platform-dependent piece of accelerator formatting (the key glyph and modifier order are
 * shared).  Localizing the modifier labels is a deferred follow-up.
 *
 * 主修飾キーのプラットフォーム別ラベル。macOS は "⌘"、それ以外は "Ctrl"。
 *
 * @return String The primary modifier label.
 */
expect fun primaryModifierLabel(): String

/**
 * Whether the Control key is a modifier of its own on this host rather than the primary one: true on macOS,
 * where Command is primary, and false everywhere else, where Control IS primary.  Decides how a held Control
 * key and a "ctrl" chord spec are read (see [KeyChord.controlKey]).
 *
 * @return Boolean True when Control is distinct from the primary modifier.
 */
expect fun controlIsDistinctFromPrimary(): Boolean