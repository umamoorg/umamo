package org.umamo.ui.action

/**
 * Renders [chord] as a human accelerator hint for a menu row (e.g. "Ctrl+Shift+P", "⌘O").  The primary
 * modifier resolves per platform via [primaryModifierLabel] (⌘ on macOS, Ctrl elsewhere); the rest is
 * pure and position-based.
 *
 * @param KeyChord chord The chord to format.
 * @return String The display hint.
 */
fun formatAccelerator(chord: KeyChord): String = formatAccelerator(chord, primaryModifierLabel())

/**
 * The modifier caps of a chord, without the key itself.
 *
 * A key view shows the modifiers as they are pressed, before any key joins them.
 *
 * @param KeyChord chord The chord to read the modifiers of.
 * @param String primaryLabel The platform label for the primary modifier ("Ctrl" or "⌘").
 * @return List The modifier cap texts, in order.
 */
fun acceleratorModifierParts(chord: KeyChord, primaryLabel: String = primaryModifierLabel()): List<String> =
	buildList {
		// A literal Control only appears on macOS, where Control is not the primary modifier.
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
	}

/**
 * The parts of an accelerator, one per key cap: the modifiers in the canonical order, then the key itself.
 *
 * A surface that draws an accelerator as key caps reads this list, so the caps and the flat text form never
 * disagree about what a chord holds.
 *
 * @param KeyChord chord The chord to break into caps.
 * @param String primaryLabel The platform label for the primary modifier ("Ctrl" or "⌘").
 * @return List The cap texts, in order.
 */
fun acceleratorParts(chord: KeyChord, primaryLabel: String = primaryModifierLabel()): List<String> =
	acceleratorModifierParts(chord, primaryLabel) + keyGlyph(chord.keyName)

/**
 * The pure core of [formatAccelerator], taking the platform's [primaryLabel] as a parameter so it can be
 * unit-tested deterministically.  Modifiers are ordered Control, primary, Alt, Shift to match the common
 * platform convention.
 *
 * @param KeyChord chord The chord to format.
 * @param String primaryLabel The platform label for the primary modifier ("Ctrl" or "⌘").
 * @return String The display hint.
 */
internal fun formatAccelerator(chord: KeyChord, primaryLabel: String): String =
	acceleratorParts(chord, primaryLabel).joinToString(separator = "+")

/**
 * Maps a position-based key token to its display glyph: "KeyO" to "O", "Digit0" to "0", and the few
 * named keys to short symbols.  An unrecognized token falls back to itself so a newly bound key still
 * shows something readable rather than vanishing.
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
 * shared).  The modifier labels are not localized.
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