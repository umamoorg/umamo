package org.umamo.ui.action

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies accelerator-hint formatting: key-token to glyph mapping, modifier ordering, and the
 * "+"-joined assembly.  The pure overload that takes an explicit primary-modifier label is tested
 * (rather than the platform one) so the assertions are host-independent.
 */
class AcceleratorTextTest {
	/**
	 * A "Key" token becomes its bare letter and a "Digit" token its bare digit; the named keys map to
	 * their short symbols.
	 */
	@Test
	fun keyGlyphMapsTokensToDisplayForms() {
		assertEquals("O", keyGlyph("KeyO"))
		assertEquals("0", keyGlyph("Digit0"))
		assertEquals("=", keyGlyph("Equals"))
		assertEquals("−", keyGlyph("Minus"))
		assertEquals("Esc", keyGlyph("Escape"))
		assertEquals("Space", keyGlyph("Space"))
	}

	/**
	 * An unrecognized token falls back to itself, so a newly bound key still shows something readable.
	 */
	@Test
	fun keyGlyphFallsBackToTheTokenItself() {
		assertEquals("F13", keyGlyph("F13"))
	}

	/**
	 * The primary modifier prefixes the key with the supplied platform label.
	 */
	@Test
	fun formatsPrimaryModifierWithSuppliedLabel() {
		assertEquals("Ctrl+O", formatAccelerator(KeyChord(keyName = "KeyO", primaryModifier = true), "Ctrl"))
		assertEquals("⌘+O", formatAccelerator(KeyChord(keyName = "KeyO", primaryModifier = true), "⌘"))
	}

	/**
	 * Modifiers render in the order primary, Alt, Shift, ahead of the key glyph.
	 */
	@Test
	fun ordersModifiersPrimaryAltShiftThenKey() {
		val chord = KeyChord(keyName = "KeyP", primaryModifier = true, shift = true, alt = true)
		assertEquals("Ctrl+Alt+Shift+P", formatAccelerator(chord, "Ctrl"))
	}

	/**
	 * A bare key with no modifiers formats as just its glyph.
	 */
	@Test
	fun formatsBareKey() {
		assertEquals("Esc", formatAccelerator(KeyChord(keyName = "Escape"), "Ctrl"))
	}
}
