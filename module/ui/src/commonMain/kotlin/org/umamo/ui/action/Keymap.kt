package org.umamo.ui.action

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One key combination, stored logically rather than as a literal character. [primaryModifier] is the
 * platform's main accelerator (Cmd on macOS, Ctrl elsewhere) - the intent is stored, not "Ctrl", so a
 * binding resolves correctly per OS. [controlKey] is the literal Control key, a modifier of its own only
 * where Control is not the primary one (macOS, see [controlIsDistinctFromPrimary]); everywhere else a
 * Control chord IS a primary chord and never carries this flag. [keyName] names the physical key by
 * position ("KeyP", "KeyK"), not the produced character, so a chord survives non-QWERTY layouts.
 *
 * @property String keyName The position-based key token (e.g. "KeyP"), layout-independent.
 * @property Boolean primaryModifier Whether the platform primary accelerator (Cmd/Ctrl) is held.
 * @property Boolean shift Whether Shift is held.
 * @property Boolean alt Whether Alt/Option is held.
 * @property Boolean controlKey Whether the literal Control key is held, on a host where it is not the
 *   primary modifier.
 */
data class KeyChord(
	val keyName: String,
	val primaryModifier: Boolean = false,
	val shift: Boolean = false,
	val alt: Boolean = false,
	val controlKey: Boolean = false,
)

/**
 * Whether a press holds the primary modifier: Command on a host where Control is its own modifier, else
 * Control or Meta, both of which press primary there.
 *
 * One half of the single rule that turns held keys into a chord's modifiers; [pressHoldsLiteralControl] is
 * the other.  The shell's dispatch and the keybindings editor's capture both read it, so a chord the editor
 * captures is exactly the chord dispatch looks up.
 *
 * @param Boolean controlPressed Whether the Control key is held.
 * @param Boolean metaPressed Whether the Meta (Command / Windows) key is held.
 * @param Boolean controlIsDistinct Whether Control is a modifier of its own on this host.
 * @return Boolean True when the press holds the primary modifier.
 */
fun pressHoldsPrimary(
	controlPressed: Boolean,
	metaPressed: Boolean,
	controlIsDistinct: Boolean = controlIsDistinctFromPrimary(),
): Boolean = metaPressed || (controlPressed && !controlIsDistinct)

/**
 * Whether a press holds the literal Control modifier: only on a host where Control is not the primary one.
 *
 * @param Boolean controlPressed Whether the Control key is held.
 * @param Boolean controlIsDistinct Whether Control is a modifier of its own on this host.
 * @return Boolean True when the press holds a literal Control.
 */
fun pressHoldsLiteralControl(
	controlPressed: Boolean,
	controlIsDistinct: Boolean = controlIsDistinctFromPrimary(),
): Boolean = controlPressed && controlIsDistinct

/**
 * The chord a key press makes, its modifiers decoded by [pressHoldsPrimary] and [pressHoldsLiteralControl].
 *
 * @param String keyName The position-based key token.
 * @param Boolean controlPressed Whether the Control key is held.
 * @param Boolean metaPressed Whether the Meta (Command / Windows) key is held.
 * @param Boolean shiftPressed Whether Shift is held.
 * @param Boolean altPressed Whether Alt / Option is held.
 * @param Boolean controlIsDistinct Whether Control is a modifier of its own on this host.
 * @return KeyChord The chord.
 */
fun keyChordOf(
	keyName: String,
	controlPressed: Boolean,
	metaPressed: Boolean,
	shiftPressed: Boolean,
	altPressed: Boolean,
	controlIsDistinct: Boolean = controlIsDistinctFromPrimary(),
): KeyChord =
	KeyChord(
		keyName = keyName,
		primaryModifier = pressHoldsPrimary(controlPressed, metaPressed, controlIsDistinct),
		shift = shiftPressed,
		alt = altPressed,
		controlKey = pressHoldsLiteralControl(controlPressed, controlIsDistinct),
	)

/**
 * The chords a press may dispatch as, in lookup order: the chord itself, then - when it holds a literal
 * Control - the same press with Control folded into primary.
 *
 * The fallback keeps every primary binding reachable from Control on macOS, while a binding that names Control
 * itself wins over it.
 *
 * @return List<KeyChord> The chords to try, the exact one first.
 */
fun KeyChord.dispatchCandidates(): List<KeyChord> =
	if (controlKey) {
		listOf(this, copy(primaryModifier = true, controlKey = false))
	} else {
		listOf(this)
	}

/**
 * Parses a human-authored chord spec like "primary+shift+KeyP" into a [KeyChord]. Tokens are split on
 * '+'; the final token is the key, the rest are modifiers ("primary" / "cmd", "ctrl" / "control", "shift",
 * "alt" / "option", case insensitive). This is the on-disk keymap form (settings input.keybinding) and the
 * form preset files use, so it must be tolerant of case and whitespace but reject a malformed spec (empty
 * key, unknown modifier) rather than silently mis-binding.
 *
 * "ctrl" names the literal Control key, which is the primary modifier everywhere but macOS: where Control is
 * not distinct it parses as primary, so "ctrl+Tab" and "primary+Tab" are one chord there and can never
 * conflict or double-bind.
 *
 * @param String spec The chord spec, modifiers and key joined by '+'.
 * @param Boolean controlIsDistinct Whether Control is a modifier of its own on this host.
 * @return KeyChord? The parsed chord, or null if the spec is malformed.
 */
fun parseKeyChord(spec: String, controlIsDistinct: Boolean = controlIsDistinctFromPrimary()): KeyChord? {
	val tokens = spec.split('+').map { token -> token.trim() }.filter { token -> token.isNotEmpty() }

	if (tokens.isEmpty()) {
		return null
	}

	val keyName = tokens.last()

	if (keyName.isEmpty()) {
		return null
	}

	var primaryModifier = false
	var shift = false
	var alt = false
	var controlKey = false

	for (modifierIndex in 0 until tokens.size - 1) {
		when (tokens[modifierIndex].lowercase()) {
			"primary", "cmd" -> {
				primaryModifier = true
			}

			"ctrl", "control" -> {
				if (controlIsDistinct) {
					controlKey = true
				} else {
					primaryModifier = true
				}
			}

			"shift" -> {
				shift = true
			}

			"alt", "option", "opt" -> {
				alt = true
			}

			else -> {
				return null
			}
		}
	}

	return KeyChord(keyName, primaryModifier, shift, alt, controlKey)
}

/**
 * Serializes a [KeyChord] back to the spec string [parseKeyChord] reads ("primary+shift+KeyP") - the form
 * stored in settings input.keybinding.overrides.  Modifiers are emitted in a fixed order (primary, ctrl,
 * shift, alt) so a given chord always round-trips to the same string and the stored override key stays
 * stable; parseKeyChord itself accepts any order, so this is the inverse of parsing for any chord it
 * produced.
 *
 * @param KeyChord chord The chord to serialize.
 * @return String The spec string.
 */
fun chordToSpec(chord: KeyChord): String =
	buildList {
		if (chord.primaryModifier) {
			add("primary")
		}

		if (chord.controlKey) {
			add("ctrl")
		}

		if (chord.shift) {
			add("shift")
		}

		if (chord.alt) {
			add("alt")
		}

		add(chord.keyName)
	}.joinToString(separator = "+")

/**
 * A resolved set of key-chord → command-id bindings, plus the lookup the input layer uses. Built from
 * a preset spec map; conflicts (two chords on the same command, or a re-bound chord) are resolved
 * last-write-wins at build time. The keymap holds only ids - it never references [Command] objects -
 * so it stays decoupled from registration order and from whether a command currently exists.
 */
class Keymap(private val commandIdByChord: Map<KeyChord, String>) {
	/**
	 * The command id bound to [chord], or null if the chord is unbound.
	 *
	 * @param KeyChord chord The pressed chord.
	 * @return String? The bound command id, or null.
	 */
	fun commandFor(chord: KeyChord): String? = commandIdByChord[chord]

	/**
	 * The command a key press dispatches, trying the press's [dispatchCandidates] in order: a binding on the
	 * exact chord wins, and a literal-Control press falls back to its primary binding.
	 *
	 * @param KeyChord pressed The chord the press made ([keyChordOf]).
	 * @return String? The bound command id, or null when no candidate is bound.
	 */
	fun commandForPress(pressed: KeyChord): String? = pressed.dispatchCandidates().firstNotNullOfOrNull { candidate -> commandFor(candidate) }

	/**
	 * The first chord bound to [commandId], or null if none is - the reverse of [commandFor], used to
	 * render a menu row's accelerator hint.  When several chords map to one command (palette.toggle has
	 * both primary+KeyP and Space) the first in insertion order wins, so a menu shows the canonical
	 * accelerator (primary+KeyP) rather than whichever the map happens to yield first.
	 *
	 * @param String commandId The command id to find an accelerator for.
	 * @return KeyChord? The first bound chord, or null.
	 */
	fun chordFor(commandId: String): KeyChord? {
		// LinkedHashMap iterates in insertion order, so the first match is the canonical binding.
		for ((chord, boundCommandId) in commandIdByChord) {
			if (boundCommandId == commandId) {
				return chord
			}
		}

		return null
	}

	/**
	 * The number of bindings - handy for tests and a future conflict/coverage report.
	 *
	 * @return Int the binding count.
	 */
	fun size(): Int = commandIdByChord.size

	companion object {
		/**
		 * Builds a [Keymap] from a preset's chord-spec → command-id map, parsing each spec via
		 * [parseKeyChord]. A spec that fails to parse is dropped (a malformed preset entry must not
		 * shadow a valid one); callers that need strict validation can compare [size] to the input.
		 *
		 * @param Map specs The preset bindings (chord spec → command id).
		 * @return Keymap the resolved keymap.
		 */
		fun fromSpecs(specs: Map<String, String>): Keymap {
			val resolved = LinkedHashMap<KeyChord, String>()

			for ((spec, commandId) in specs) {
				val chord = parseKeyChord(spec) ?: continue
				resolved[chord] = commandId
			}

			return Keymap(resolved)
		}
	}
}

/**
 * The ids of the built-in keymap presets, in display order.  [keymapPresetSpecs] resolves each to its
 * bindings; the persisted choice lives in settings input.keybinding.preset and user overrides layer on top.
 */
val KEYMAP_PRESET_IDS: List<String> = listOf("default", "cubism", "blender")

/**
 * The "default" preset: a small, neutral starter set.  Bindings are by key position with the logical
 * primary modifier, so they hold across OSes and layouts.
 */
private val DEFAULT_KEYMAP_SPECS: Map<String, String> =
	presetSpecs(
		"primary+KeyP" to "palette.toggle",
		"Space" to "palette.toggle",
		"Escape" to "area.dragCancel",
		"Tab" to "mode.toggleEdit",
		// A literal Control, so macOS gets Ctrl+Tab rather than Cmd+Tab, which the OS takes for switching apps.
		"ctrl+Tab" to "mode.togglePose",
		"primary+PageUp" to "workspace.prev",
		"primary+PageDown" to "workspace.next",
		// The document's own chords: Open, Save, and Save As on the keys every application uses for them.
		// Import CMO3 has no chord - an import is not an open - and the File menu and the palette reach it.
		"primary+KeyN" to "file.new",
		"primary+KeyO" to "file.open",
		"primary+KeyS" to "file.save",
		"primary+shift+KeyS" to "file.saveAs",
		"primary+shift+KeyO" to "file.importArtwork",
		"primary+KeyZ" to "edit.undo",
		"primary+shift+KeyZ" to "edit.redo",
		"F9" to "edit.adjustLastOperation",
		"KeyP" to "uv.pinPlacement",
		"alt+KeyP" to "uv.unpinPlacement",
		"KeyH" to "object.toggleVisibility",
		"KeyG" to "mesh.grab",
		"KeyS" to "mesh.scale",
		"KeyR" to "mesh.rotate",
		"Digit1" to "mesh.selectMode.vertex",
		"Digit2" to "mesh.selectMode.edge",
		"Digit3" to "mesh.selectMode.face",
		"KeyA" to "select.all",
		"primary+KeyI" to "select.invert",
		"KeyI" to "keyform.insert",
		"alt+KeyI" to "keyform.delete",
		"Delete" to "keyform.deleteSelectedKeys",
		"ArrowLeft" to "keyform.nudgeKeyLeft",
		"ArrowRight" to "keyform.nudgeKeyRight",
		// Context-aware, Blender's Home semantics: frames the hovered editor (keyform sheet → the keys,
		// anything else → the viewport).
		"Home" to "frame.all",
		"KeyB" to "mesh.boxSelect",
		"KeyC" to "mesh.circleSelect",
		"shift+KeyB" to "view.zoomRegion",
		"NumpadAdd" to "mesh.circleSelect.grow",
		"NumpadSubtract" to "mesh.circleSelect.shrink",
		"Period" to "transform.pivotPie",
		"shift+KeyS" to "snap.pie",
		"KeyL" to "mesh.selectLinkedAtCursor",
		"primary+KeyL" to "mesh.selectLinked",
		"alt+KeyQ" to "edit.switchObjectUnderCursor",
		"NumpadDecimal" to "view.frameSelected",
		"shift+KeyD" to "mesh.duplicate",
		"KeyM" to "mesh.merge",
		"shift+KeyM" to "mesh.generateFromArt",
		"KeyV" to "mesh.rip",
		"KeyJ" to "mesh.connect",
		"shift+KeyV" to "mesh.vertexSlide",
		"KeyO" to "mesh.proportional.toggle",
		"alt+KeyO" to "mesh.proportional.connectedToggle",
		"KeyT" to "view.toggleToolbar",
		"KeyN" to "view.toggleSidebar",
		"shift+alt+KeyZ" to "view.overlay.all",
		"primary+Comma" to "edit.preferences",
		"primary+Digit0" to "view.fit",
		"primary+Digit1" to "view.zoomActualSize",
		"primary+Equals" to "view.zoomIn",
		"primary+shift+Equals" to "view.zoomInCoarse",
		"primary+Minus" to "view.zoomOut",
		"primary+shift+Minus" to "view.zoomOutCoarse",
	)

/**
 * The "Cubism-like" preset for migrants from the official Cubism Editor.  A starting point keyed to Cubism
 * muscle memory (the command palette on the primary modifier, no bare Space - Cubism reserves Space for
 * canvas panning); fully rebindable in the keybindings editor.
 */
private val CUBISM_KEYMAP_SPECS: Map<String, String> =
	presetSpecs(
		"primary+KeyP" to "palette.toggle",
		"Escape" to "area.dragCancel",
		// A literal Control, so macOS gets Ctrl+Tab rather than Cmd+Tab, which the OS takes for switching apps.
		"ctrl+Tab" to "mode.togglePose",
		"primary+PageUp" to "workspace.prev",
		"primary+PageDown" to "workspace.next",
		// The document's own chords: Open, Save, and Save As on the keys every application uses for them.
		// Import CMO3 has no chord - an import is not an open - and the File menu and the palette reach it.
		"primary+KeyN" to "file.new",
		"primary+KeyO" to "file.open",
		"primary+KeyS" to "file.save",
		"primary+shift+KeyS" to "file.saveAs",
		"primary+shift+KeyO" to "file.importArtwork",
		// Undo / redo: Ctrl+Z plus both redo conventions Cubism migrants carry (Ctrl+Shift+Z and Ctrl+Y).
		"primary+KeyZ" to "edit.undo",
		"primary+shift+KeyZ" to "edit.redo",
		"F9" to "edit.adjustLastOperation",
		"KeyP" to "uv.pinPlacement",
		"alt+KeyP" to "uv.unpinPlacement",
		"primary+KeyY" to "edit.redo",
		"primary+Comma" to "edit.preferences",
		"primary+Digit0" to "view.fit",
		// Frame Selected joins the Ctrl+0 fit family (Cubism has no numpad-framing convention).
		"primary+shift+Digit0" to "view.frameSelected",
		"primary+Digit1" to "view.zoomActualSize",
		"primary+Equals" to "view.zoomIn",
		"primary+shift+Equals" to "view.zoomInCoarse",
		"primary+Minus" to "view.zoomOut",
		"primary+shift+Minus" to "view.zoomOutCoarse",
	)

/**
 * The "Blender-like" preset for migrants from Blender (whose editing model Umamo borrows).  A starting
 * point keyed to Blender muscle memory (operator search on F3, Frame All on Home - context-aware per
 * hovered editor, exactly as in Blender); fully rebindable in the keybindings editor.
 */
private val BLENDER_KEYMAP_SPECS: Map<String, String> =
	presetSpecs(
		"F3" to "palette.toggle",
		"Escape" to "area.dragCancel",
		"Tab" to "mode.toggleEdit",
		// A literal Control, so macOS gets Ctrl+Tab rather than Cmd+Tab, which the OS takes for switching apps.
		"ctrl+Tab" to "mode.togglePose",
		"primary+PageUp" to "workspace.prev",
		"primary+PageDown" to "workspace.next",
		// The document's own chords: Open, Save, and Save As on the keys every application uses for them.
		// Import CMO3 has no chord - an import is not an open - and the File menu and the palette reach it.
		"primary+KeyN" to "file.new",
		"primary+KeyO" to "file.open",
		"primary+KeyS" to "file.save",
		"primary+shift+KeyS" to "file.saveAs",
		"primary+shift+KeyO" to "file.importArtwork",
		"primary+KeyZ" to "edit.undo",
		"primary+shift+KeyZ" to "edit.redo",
		"F9" to "edit.adjustLastOperation",
		"KeyP" to "uv.pinPlacement",
		"alt+KeyP" to "uv.unpinPlacement",
		"KeyH" to "object.toggleVisibility",
		"KeyG" to "mesh.grab",
		"KeyS" to "mesh.scale",
		"KeyR" to "mesh.rotate",
		"Digit1" to "mesh.selectMode.vertex",
		"Digit2" to "mesh.selectMode.edge",
		"Digit3" to "mesh.selectMode.face",
		"KeyA" to "select.all",
		"primary+KeyI" to "select.invert",
		"KeyI" to "keyform.insert",
		"alt+KeyI" to "keyform.delete",
		"Delete" to "keyform.deleteSelectedKeys",
		"ArrowLeft" to "keyform.nudgeKeyLeft",
		"ArrowRight" to "keyform.nudgeKeyRight",
		"KeyB" to "mesh.boxSelect",
		"KeyC" to "mesh.circleSelect",
		"shift+KeyB" to "view.zoomRegion",
		"NumpadAdd" to "mesh.circleSelect.grow",
		"NumpadSubtract" to "mesh.circleSelect.shrink",
		"Period" to "transform.pivotPie",
		"shift+KeyS" to "snap.pie",
		"KeyL" to "mesh.selectLinkedAtCursor",
		"primary+KeyL" to "mesh.selectLinked",
		"alt+KeyQ" to "edit.switchObjectUnderCursor",
		"NumpadDecimal" to "view.frameSelected",
		"shift+KeyD" to "mesh.duplicate",
		"KeyM" to "mesh.merge",
		"shift+KeyM" to "mesh.generateFromArt",
		"KeyV" to "mesh.rip",
		"KeyJ" to "mesh.connect",
		"shift+KeyV" to "mesh.vertexSlide",
		"KeyO" to "mesh.proportional.toggle",
		"alt+KeyO" to "mesh.proportional.connectedToggle",
		"KeyT" to "view.toggleToolbar",
		"KeyN" to "view.toggleSidebar",
		"shift+alt+KeyZ" to "view.overlay.all",
		"primary+Comma" to "edit.preferences",
		// Blender's Home is Frame All in WHICHEVER editor the pointer is over, so a single context-aware
		// binding covers both cases; two separate Home entries would collide silently under mapOf's
		// last-wins semantics.
		"Home" to "frame.all",
		"primary+Digit1" to "view.zoomActualSize",
		"Equals" to "view.zoomIn",
		"shift+Equals" to "view.zoomInCoarse",
		"Minus" to "view.zoomOut",
		"shift+Minus" to "view.zoomOutCoarse",
	)

/**
 * Builds a preset's chord-spec table, refusing duplicate chords.
 *
 * mapOf would keep only the LAST of two same-chord pairs, silently dropping the other - invisible even to
 * the keybindings editor's conflict detection, which only ever sees the already-collapsed map.  Failing at
 * build time turns a duplicate chord into a test failure instead of a silent loss.
 *
 * @param Pair bindings The chord-spec to command-id pairs, in presentation order.
 * @return Map The bindings as a map.
 */
private fun presetSpecs(vararg bindings: Pair<String, String>): Map<String, String> {
	val byChord = LinkedHashMap<String, String>(bindings.size)

	for ((chord, commandId) in bindings) {
		val previous = byChord.put(chord, commandId)
		require(previous == null) { "duplicate chord $chord: bound to both $previous and $commandId" }
	}

	return byChord
}

/**
 * The chord-spec → command-id bindings for the built-in preset [presetId], falling back to the default
 * preset's bindings for an unknown id so a stale or hand-edited settings value can never leave the editor
 * with no keymap at all.
 *
 * @param String presetId The preset id (see [KEYMAP_PRESET_IDS]).
 * @return Map The preset's chord-spec → command-id bindings.
 */
fun keymapPresetSpecs(presetId: String): Map<String, String> =
	when (presetId) {
		"cubism" -> CUBISM_KEYMAP_SPECS
		"blender" -> BLENDER_KEYMAP_SPECS
		else -> DEFAULT_KEYMAP_SPECS
	}

/**
 * The built-in "default" keymap preset as a resolved [Keymap].  Used directly by tests, and as
 * [org.umamo.ui.workspace.shell.EditorShell]'s default parameter when no settings-backed keymap
 * ([org.umamo.ui.action.loadKeymap]) is supplied by the caller.
 *
 * @return Keymap the default preset.
 */
fun defaultKeymap(): Keymap = Keymap.fromSpecs(DEFAULT_KEYMAP_SPECS)

/**
 * The active [Keymap] for the composition.  `static` because the keymap instance only changes when the
 * settings-resolved preset/overrides change (see [org.umamo.ui.action.loadKeymap]), so a menu rendered
 * deep in the tree can resolve a command's accelerator (via [Keymap.chordFor]) without prop-drilling
 * the keymap through every intervening composable.  The default errors loudly so a missing provider is
 * caught at first use rather than silently showing no accelerators.
 */
val LocalKeymap =
	staticCompositionLocalOf<Keymap> {
		error("LocalKeymap not provided — wrap the editor in an EditorShell")
	}