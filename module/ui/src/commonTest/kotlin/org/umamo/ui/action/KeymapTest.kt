package org.umamo.ui.action

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
	 * Where Control is the primary modifier, "cmd" / "ctrl" / "control" all map to the single logical primary
	 * modifier, so a "ctrl" spec can never conflict with or double-bind a "primary" one.
	 */
	@Test
	fun primaryModifierAliasesUnifyWhereControlIsPrimary() {
		val viaCmd = parseKeyChord("cmd+KeyA", controlIsDistinct = false)
		val viaCtrl = parseKeyChord("ctrl+KeyA", controlIsDistinct = false)
		val viaControl = parseKeyChord("control+KeyA", controlIsDistinct = false)
		assertEquals(viaCmd, viaCtrl)
		assertEquals(viaCmd, viaControl)
		assertEquals(KeyChord(keyName = "KeyA", primaryModifier = true), viaCmd)
	}

	/**
	 * Where Control is its own modifier (macOS), "ctrl" parses as the literal Control key and "cmd" as primary,
	 * two different chords.
	 */
	@Test
	fun ctrlIsLiteralWhereControlIsDistinct() {
		assertEquals(KeyChord(keyName = "Tab", controlKey = true), parseKeyChord("ctrl+Tab", controlIsDistinct = true))
		assertEquals(KeyChord(keyName = "Tab", controlKey = true), parseKeyChord("Control+Tab", controlIsDistinct = true))
		assertEquals(KeyChord(keyName = "Tab", primaryModifier = true), parseKeyChord("cmd+Tab", controlIsDistinct = true))
		assertEquals(
			KeyChord(keyName = "KeyK", primaryModifier = true, shift = true, controlKey = true),
			parseKeyChord("shift+ctrl+primary+KeyK", controlIsDistinct = true),
		)
	}

	/**
	 * A literal Control serializes as "ctrl" in the fixed primary, ctrl, shift, alt order, and parses back to
	 * the same chord where Control is distinct.
	 */
	@Test
	fun literalControlRoundTripsThroughItsSpec() {
		val chord = KeyChord(keyName = "KeyK", primaryModifier = true, shift = true, alt = true, controlKey = true)
		assertEquals("primary+ctrl+shift+alt+KeyK", chordToSpec(chord))
		assertEquals(chord, parseKeyChord(chordToSpec(chord), controlIsDistinct = true))
		assertEquals("ctrl+Tab", chordToSpec(KeyChord(keyName = "Tab", controlKey = true)))
	}

	/**
	 * A press decodes by host: Control and Meta both press primary where Control is primary; on macOS Command
	 * presses primary and Control is literal.
	 */
	@Test
	fun aPressDecodesItsModifiersByHost() {
		assertEquals(
			KeyChord(keyName = "Tab", primaryModifier = true),
			keyChordOf("Tab", controlPressed = true, metaPressed = false, shiftPressed = false, altPressed = false, controlIsDistinct = false),
		)
		assertEquals(
			KeyChord(keyName = "Tab", primaryModifier = true),
			keyChordOf("Tab", controlPressed = false, metaPressed = true, shiftPressed = false, altPressed = false, controlIsDistinct = false),
		)
		assertEquals(
			KeyChord(keyName = "Tab", controlKey = true),
			keyChordOf("Tab", controlPressed = true, metaPressed = false, shiftPressed = false, altPressed = false, controlIsDistinct = true),
		)
		assertEquals(
			KeyChord(keyName = "Tab", primaryModifier = true, shift = true, controlKey = true),
			keyChordOf("Tab", controlPressed = true, metaPressed = true, shiftPressed = true, altPressed = false, controlIsDistinct = true),
		)
	}

	/**
	 * A literal-Control press dispatches its own binding when one exists, and otherwise falls back to the
	 * primary binding of the same key, so Control keeps reaching every primary chord on macOS.  A press with
	 * no literal Control never falls back to anything.
	 */
	@Test
	fun aLiteralControlPressPrefersItsOwnBindingThenFallsBackToPrimary() {
		val literalTab = KeyChord(keyName = "Tab", controlKey = true)
		val primaryTab = KeyChord(keyName = "Tab", primaryModifier = true)
		val primaryS = KeyChord(keyName = "KeyS", primaryModifier = true)
		val keymap = Keymap(mapOf(literalTab to "mode.togglePose", primaryTab to "workspace.cycle", primaryS to "file.save"))

		assertEquals("mode.togglePose", keymap.commandForPress(literalTab))
		assertEquals("file.save", keymap.commandForPress(KeyChord(keyName = "KeyS", controlKey = true)))
		assertEquals("workspace.cycle", keymap.commandForPress(primaryTab))
		assertNull(keymap.commandForPress(KeyChord(keyName = "KeyS")))
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
	 * Every built-in preset builds without duplicate chords (presetSpecs refuses them at construction, so
	 * merely reading each preset asserts it), and Home resolves to the context-aware frame command in the
	 * presets that bind it.  mapOf's silent last-wins once left the Blender preset with two Home entries
	 * and keyform.frameAll unbound, invisible even to the keybindings editor's conflict detection.
	 */
	@Test
	fun presetsHaveNoDuplicateChordsAndHomeFramesContextually() {
		for (presetId in listOf("default", "cubism", "blender")) {
			val specs = keymapPresetSpecs(presetId)
			assertEquals(specs.size, specs.keys.distinct().size, "$presetId: chords are unique")
		}

		assertEquals("frame.all", keymapPresetSpecs("default")["Home"], "default Home is the context-aware frame")
		assertEquals("frame.all", keymapPresetSpecs("blender")["Home"], "blender Home is the context-aware frame")
	}

	/**
	 * Every preset reaches the document commands a rigger starts from, under the accelerators every other
	 * application uses for them: New, Open, Save, and Save As on their usual keys, the artwork import
	 * - which ADDS to the open document - keeping the chord it has always had so a rebind saved against that
	 * id still resolves, and Import CMO3 with no chord at all, since an import is not an open.
	 */
	@Test
	fun everyPresetBindsTheDocumentCommands() {
		for (presetId in listOf("default", "cubism", "blender")) {
			val specs = keymapPresetSpecs(presetId)
			assertEquals("file.new", specs["primary+KeyN"], "$presetId: Ctrl+N starts a new document")
			assertEquals("file.open", specs["primary+KeyO"], "$presetId: Ctrl+O opens a document")
			assertEquals("file.save", specs["primary+KeyS"], "$presetId: Ctrl+S saves")
			assertEquals("file.saveAs", specs["primary+shift+KeyS"], "$presetId: Ctrl+Shift+S saves as")
			assertEquals("file.importArtwork", specs["primary+shift+KeyO"], "$presetId: Ctrl+Shift+O imports artwork")
			assertFalse(specs.containsValue("file.importCmo3"), "$presetId: Import CMO3 has no chord")
		}
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

	/**
	 * Shift+Alt+Z toggles the hovered area's overlays (Blender's chord) in the default and blender presets, and
	 * stays unbound in the cubism preset.
	 */
	@Test
	fun overlayMasterBindsInDefaultAndBlenderButNotCubism() {
		val chord = KeyChord(keyName = "KeyZ", shift = true, alt = true)

		for (presetId in listOf("default", "blender")) {
			val keymap = Keymap.fromSpecs(keymapPresetSpecs(presetId))
			assertEquals("view.overlay.all", keymap.commandFor(chord), "Shift+Alt+Z in $presetId")
			assertEquals(chord, keymap.chordFor("view.overlay.all"), "the chord reads back in $presetId")
		}

		val cubism = Keymap.fromSpecs(keymapPresetSpecs("cubism"))
		assertNull(cubism.commandFor(chord))
		assertNull(cubism.chordFor("view.overlay.all"))
	}

	/**
	 * Shift+M generates meshes from art in the default and blender presets (beside M's Merge), and stays
	 * unbound in the cubism preset.
	 */
	@Test
	fun generateMeshFromArtBindsInDefaultAndBlenderButNotCubism() {
		val chord = KeyChord(keyName = "KeyM", shift = true)

		for (presetId in listOf("default", "blender")) {
			val keymap = Keymap.fromSpecs(keymapPresetSpecs(presetId))
			assertEquals("mesh.generateFromArt", keymap.commandFor(chord), "Shift+M in $presetId")
			assertEquals(chord, keymap.chordFor("mesh.generateFromArt"), "the chord reads back in $presetId")
		}

		val cubism = Keymap.fromSpecs(keymapPresetSpecs("cubism"))
		assertNull(cubism.commandFor(chord))
	}

	/** Generate Quad Mesh ships unbound in every preset; a rigger binds it in Preferences. */
	@Test
	fun generateQuadMeshShipsUnbound() {
		for (presetId in listOf("default", "blender", "cubism")) {
			assertNull(Keymap.fromSpecs(keymapPresetSpecs(presetId)).chordFor("mesh.generateQuad"), "unbound in $presetId")
		}
	}
}