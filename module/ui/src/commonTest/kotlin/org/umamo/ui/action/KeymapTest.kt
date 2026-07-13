package org.umamo.ui.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies chord parsing (logical primary modifier, position-based key, malformed rejection) and
 * keymap lookup.
 */
class KeymapTest {
	/**
	 * A full modifier+key spec parses into the expected chord, case- and whitespace-insensitive on modifiers.
	 */
	@Test
	fun parsesModifiersAndKey() {
		assertEquals(
			KeyChord(keyName = "KeyP", primaryModifier = true, shift = true, alt = false),
			parseKeyChord(" primary + Shift + KeyP "),
		)
	}

	/**
	 * "cmd"/"ctrl"/"control" all map to the single logical primary modifier.
	 */
	@Test
	fun primaryModifierAliasesUnify() {
		val viaCmd = parseKeyChord("cmd+KeyA")
		val viaCtrl = parseKeyChord("ctrl+KeyA")
		assertEquals(viaCmd, viaCtrl)
		assertEquals(KeyChord(keyName = "KeyA", primaryModifier = true), viaCmd)
	}

	/**
	 * A bare key with no modifiers parses; an unknown modifier or empty spec is rejected.
	 */
	@Test
	fun bareKeyParsesAndMalformedIsRejected() {
		assertEquals(KeyChord(keyName = "KeyF"), parseKeyChord("KeyF"))
		assertNull(parseKeyChord("hyper+KeyF"))
		assertNull(parseKeyChord(""))
		assertNull(parseKeyChord("+"))
	}

	/**
	 * A keymap built from specs resolves a bound chord to its command id and an unbound chord to null.
	 */
	@Test
	fun keymapLooksUpBoundChord() {
		val keymap = Keymap.fromSpecs(mapOf("primary+KeyP" to "palette.toggle"))
		assertEquals("palette.toggle", keymap.commandFor(parseKeyChord("primary+KeyP")!!))
		assertNull(keymap.commandFor(parseKeyChord("primary+KeyQ")!!))
	}

	/**
	 * A malformed spec is dropped from the keymap rather than shadowing valid bindings.
	 */
	@Test
	fun malformedSpecIsDropped() {
		// "hyper+KeyF" has an unrecognised modifier, so parseKeyChord rejects it and it is dropped.
		val keymap = Keymap.fromSpecs(mapOf("primary+KeyP" to "palette.toggle", "hyper+KeyF" to "nope"))
		assertEquals(1, keymap.size())
	}

	/**
	 * The reverse lookup resolves a command id to its bound chord, and an unbound id to null.
	 */
	@Test
	fun chordForResolvesBoundCommand() {
		val keymap = Keymap.fromSpecs(mapOf("primary+KeyO" to "file.open"))
		assertEquals(KeyChord(keyName = "KeyO", primaryModifier = true), keymap.chordFor("file.open"))
		assertNull(keymap.chordFor("file.close"))
	}

	/**
	 * When several chords bind one command, the reverse lookup returns the first in insertion order, so a
	 * menu shows the canonical accelerator (primary+KeyP) rather than the secondary (Space).
	 */
	@Test
	fun chordForReturnsFirstBindingWhenSeveral() {
		val keymap = Keymap.fromSpecs(mapOf("primary+KeyP" to "palette.toggle", "Space" to "palette.toggle"))
		assertEquals(KeyChord(keyName = "KeyP", primaryModifier = true), keymap.chordFor("palette.toggle"))
	}

	/**
	 * The default keymap opens the palette on both Space (the Blender-style headline default) and the
	 * primary modifier + P (the familiar accelerator), and resolves primary+P as the canonical chord the
	 * palette / menu shows as the hint.  Guards the Space-default requirement and the keybind-hint feature.
	 */
	@Test
	fun defaultKeymapBindsPaletteToggleToSpaceAndPrimaryP() {
		val keymap = defaultKeymap()
		assertEquals("palette.toggle", keymap.commandFor(KeyChord(keyName = "Space")))
		assertEquals("palette.toggle", keymap.commandFor(KeyChord(keyName = "KeyP", primaryModifier = true)))
		assertEquals(KeyChord(keyName = "KeyP", primaryModifier = true), keymap.chordFor("palette.toggle"))
	}

	/**
	 * Bare 1 / 2 / 3 switch the Edit-mode select modes (Blender's convention) in the default and blender
	 * presets, and stay unbound in the cubism preset (which carries no Blender edit-mode family).
	 */
	@Test
	fun selectModeDigitsBindInDefaultAndBlenderButNotCubism() {
		for (presetId in listOf("default", "blender")) {
			val keymap = Keymap.fromSpecs(keymapPresetSpecs(presetId))
			assertEquals("mesh.selectMode.vertex", keymap.commandFor(KeyChord(keyName = "Digit1")), "Digit1 in $presetId")
			assertEquals("mesh.selectMode.edge", keymap.commandFor(KeyChord(keyName = "Digit2")), "Digit2 in $presetId")
			assertEquals("mesh.selectMode.face", keymap.commandFor(KeyChord(keyName = "Digit3")), "Digit3 in $presetId")
		}
		val cubism = Keymap.fromSpecs(keymapPresetSpecs("cubism"))
		assertNull(cubism.commandFor(KeyChord(keyName = "Digit1")))
		assertNull(cubism.commandFor(KeyChord(keyName = "Digit2")))
		assertNull(cubism.commandFor(KeyChord(keyName = "Digit3")))
	}
}
