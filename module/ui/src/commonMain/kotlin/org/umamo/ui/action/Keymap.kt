package org.umamo.ui.action

import androidx.compose.runtime.staticCompositionLocalOf

/**
 * One key combination, stored logically rather than as a literal character. [primaryModifier] is the
 * platform's main accelerator (Cmd on macOS, Ctrl elsewhere) - the intent is stored, not "Ctrl", so a
 * binding resolves correctly per OS. [keyName] names the physical key by position
 * ("KeyP", "KeyK"), not the produced character, so a chord survives non-QWERTY layouts.
 *
 * 1 つのキー組み合わせ。修飾は論理的な「主修飾キー」で持ち、キーは文字でなく位置で持つ。
 *
 * @property String keyName The position-based key token (e.g. "KeyP"), layout-independent.
 * @property Boolean primaryModifier Whether the platform primary accelerator (Cmd/Ctrl) is held.
 * @property Boolean shift Whether Shift is held.
 * @property Boolean alt Whether Alt/Option is held.
 */
data class KeyChord(
	val keyName: String,
	val primaryModifier: Boolean = false,
	val shift: Boolean = false,
	val alt: Boolean = false,
)

/**
 * Parses a human-authored chord spec like "primary+shift+KeyP" into a [KeyChord]. Tokens are split on
 * '+'; the final token is the key, the rest are modifiers ("primary", "shift", "alt"/"option", case
 * insensitive). This is the on-disk keymap form (settings input.keybinding) and the form preset files
 * use, so it must be tolerant of case and whitespace but reject a malformed spec (empty key, unknown
 * modifier) rather than silently mis-binding.
 *
 * 「primary+shift+KeyP」形式の指定を KeyChord に解析する。不正な指定は null を返す。
 *
 * @param String spec The chord spec, modifiers and key joined by '+'.
 * @return KeyChord? The parsed chord, or null if the spec is malformed.
 */
fun parseKeyChord(spec: String): KeyChord? {
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
	for (modifierIndex in 0 until tokens.size - 1) {
		when (tokens[modifierIndex].lowercase()) {
			"primary", "cmd", "ctrl", "control" -> primaryModifier = true
			"shift" -> shift = true
			"alt", "option", "opt" -> alt = true
			else -> return null
		}
	}
	return KeyChord(keyName, primaryModifier, shift, alt)
}

/**
 * Serializes a [KeyChord] back to the spec string [parseKeyChord] reads ("primary+shift+KeyP") - the form
 * stored in settings input.keybinding.overrides.  Modifiers are emitted in a fixed order (primary, shift,
 * alt) so a given chord always round-trips to the same string and the stored override key stays stable;
 * parseKeyChord itself accepts any order, so this is the inverse of parsing for any chord it produced.
 *
 * KeyChord を parseKeyChord が読む指定文字列へ戻す（設定 overrides の保存形式）。修飾は固定順で出力する。
 *
 * @param KeyChord chord The chord to serialize.
 * @return String The spec string.
 */
fun chordToSpec(chord: KeyChord): String =
	buildList {
		if (chord.primaryModifier) {
			add("primary")
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
 *
 * キーコード→コマンド ID の束縛表。入力層はこれを引いて [CommandRegistry] に委譲する。
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
	 * The first chord bound to [commandId], or null if none is - the reverse of [commandFor], used to
	 * render a menu row's accelerator hint.  When several chords map to one command (palette.toggle has
	 * both primary+KeyP and Space) the first in insertion order wins, so a menu shows the canonical
	 * accelerator (primary+KeyP) rather than whichever the map happens to yield first.
	 *
	 * commandId に束縛された最初のコード。複数あれば挿入順で先頭を返す（メニューの表示用）。
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
 *
 * 組み込みキーマッププリセットの id（表示順）。設定 input.keybinding.preset で選び、ユーザー上書きを重ねる。
 */
val KEYMAP_PRESET_IDS: List<String> = listOf("default", "cubism", "blender")

/**
 * The "default" preset: a small, neutral starter set.  Bindings are by key position with the logical
 * primary modifier, so they hold across OSes and layouts.
 */
private val DEFAULT_KEYMAP_SPECS: Map<String, String> =
	mapOf(
		"primary+KeyP" to "palette.toggle",
		"Space" to "palette.toggle",
		// Escape cancels the current transient interaction (today: an in-flight area corner drag).
		"Escape" to "area.dragCancel",
		// Tab toggles object/edit mode (Blender's convention; Edit mode is a stub in v1).
		"Tab" to "mode.toggleEdit",
		// Browser-style workspace navigation (Previous = Page Up, Next = Page Down).
		"primary+PageUp" to "workspace.prev",
		"primary+PageDown" to "workspace.next",
		"primary+KeyO" to "file.open",
		"primary+KeyS" to "file.saveAs",
		// Undo / redo (Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z); H toggles the selection's visibility (Blender's hide key).
		"primary+KeyZ" to "edit.undo",
		"primary+shift+KeyZ" to "edit.redo",
		"KeyH" to "object.toggleVisibility",
		// Edit-mode modal mesh transforms (Blender G / S / R); each no-ops outside Edit mode with a
		// selection. Unmodified, so no clash with primary+KeyS (saveAs).
		"KeyG" to "mesh.grab",
		"KeyS" to "mesh.scale",
		"KeyR" to "mesh.rotate",
		// Edit-mode select modes (Blender 1 / 2 / 3: vertex / edge / face); no-op outside Edit mode.
		"Digit1" to "mesh.selectMode.vertex",
		"Digit2" to "mesh.selectMode.edge",
		"Digit3" to "mesh.selectMode.face",
		// Selection operators (Blender A / Ctrl+I); mode-dispatched to mesh elements or whole objects. I is
		// reserved for the inset operation, so Invert takes the primary modifier.
		"KeyA" to "select.all",
		"primary+KeyI" to "select.invert",
		// Box (B) and Circle (C) select tools; Zoom Region on Shift+B; numpad +/- resize the circle brush.
		"KeyB" to "mesh.boxSelect",
		"KeyC" to "mesh.circleSelect",
		"shift+KeyB" to "view.zoomRegion",
		"NumpadAdd" to "mesh.circleSelect.grow",
		"NumpadSubtract" to "mesh.circleSelect.shrink",
		// The transform pivot pie (Blender's Period) and the snap pie (Blender's Shift+S).
		"Period" to "transform.pivotPie",
		"shift+KeyS" to "snap.pie",
		// Select Linked (Blender's L under the cursor, Ctrl+L from the selection) and the Alt+Q
		// edited-mesh switch; Frame Selected on the numpad period (Blender's framing key).
		"KeyL" to "mesh.selectLinkedAtCursor",
		"primary+KeyL" to "mesh.selectLinked",
		"alt+KeyQ" to "edit.switchObjectUnderCursor",
		"NumpadDecimal" to "view.frameSelected",
		// The topology operators (Blender's Shift+D duplicate, M merge, V rip, J connect, Shift+V slide).
		"shift+KeyD" to "mesh.duplicate",
		"KeyM" to "mesh.merge",
		"KeyV" to "mesh.rip",
		"KeyJ" to "mesh.connect",
		"shift+KeyV" to "mesh.vertexSlide",
		// Proportional editing (Blender's O; Alt+O toggles Connected Only).
		"KeyO" to "mesh.proportional.toggle",
		"alt+KeyO" to "mesh.proportional.connectedToggle",
		// The viewport chrome toggles (Blender's T toolbar and N sidebar).
		"KeyT" to "view.toggleToolbar",
		"KeyN" to "view.toggleSidebar",
		// The conventional Preferences shortcut (Ctrl+, / Cmd+,); opens the settings window.
		"primary+Comma" to "edit.preferences",
		// Photoshop / Cubism muscle memory for view navigation.
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
	mapOf(
		"primary+KeyP" to "palette.toggle",
		"Escape" to "area.dragCancel",
		"primary+PageUp" to "workspace.prev",
		"primary+PageDown" to "workspace.next",
		"primary+KeyO" to "file.open",
		"primary+KeyS" to "file.saveAs",
		// Undo / redo: Ctrl+Z plus both redo conventions Cubism migrants carry (Ctrl+Shift+Z and Ctrl+Y).
		"primary+KeyZ" to "edit.undo",
		"primary+shift+KeyZ" to "edit.redo",
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
 * point keyed to Blender muscle memory (operator search on F3, Frame All on Home for view.fit); fully
 * rebindable in the keybindings editor.
 */
private val BLENDER_KEYMAP_SPECS: Map<String, String> =
	mapOf(
		"F3" to "palette.toggle",
		"Escape" to "area.dragCancel",
		// Tab toggles object/edit mode (Blender's convention; Edit mode is a stub in v1).
		"Tab" to "mode.toggleEdit",
		"primary+PageUp" to "workspace.prev",
		"primary+PageDown" to "workspace.next",
		"primary+KeyO" to "file.open",
		"primary+KeyS" to "file.saveAs",
		// Undo / redo (Ctrl/Cmd+Z, Ctrl/Cmd+Shift+Z); H hides the selection (Blender's hide key) as a toggle.
		"primary+KeyZ" to "edit.undo",
		"primary+shift+KeyZ" to "edit.redo",
		"KeyH" to "object.toggleVisibility",
		// Blender's mesh transform operators (Grab / Scale / Rotate) in Edit mode.
		"KeyG" to "mesh.grab",
		"KeyS" to "mesh.scale",
		"KeyR" to "mesh.rotate",
		// Blender's Edit-mode select modes (1 / 2 / 3: vertex / edge / face); no-op outside Edit mode.
		"Digit1" to "mesh.selectMode.vertex",
		"Digit2" to "mesh.selectMode.edge",
		"Digit3" to "mesh.selectMode.face",
		// Blender's selection operators (A select all, Ctrl+I invert), Box (B) / Circle (C) select, Zoom Region
		// (Shift+B), and numpad +/- for the circle brush radius.
		"KeyA" to "select.all",
		"primary+KeyI" to "select.invert",
		"KeyB" to "mesh.boxSelect",
		"KeyC" to "mesh.circleSelect",
		"shift+KeyB" to "view.zoomRegion",
		"NumpadAdd" to "mesh.circleSelect.grow",
		"NumpadSubtract" to "mesh.circleSelect.shrink",
		// Blender's pivot pie (Period) and snap pie (Shift+S).
		"Period" to "transform.pivotPie",
		"shift+KeyS" to "snap.pie",
		// Blender's Select Linked (L / Ctrl+L), the Alt+Q object switch, and numpad-period framing.
		"KeyL" to "mesh.selectLinkedAtCursor",
		"primary+KeyL" to "mesh.selectLinked",
		"alt+KeyQ" to "edit.switchObjectUnderCursor",
		"NumpadDecimal" to "view.frameSelected",
		// Blender's topology operators (Shift+D, M, V, J, Shift+V).
		"shift+KeyD" to "mesh.duplicate",
		"KeyM" to "mesh.merge",
		"KeyV" to "mesh.rip",
		"KeyJ" to "mesh.connect",
		"shift+KeyV" to "mesh.vertexSlide",
		// Blender's proportional editing (O; Alt+O toggles Connected Only).
		"KeyO" to "mesh.proportional.toggle",
		"alt+KeyO" to "mesh.proportional.connectedToggle",
		// Blender's chrome toggles (T toolbar, N sidebar).
		"KeyT" to "view.toggleToolbar",
		"KeyN" to "view.toggleSidebar",
		"primary+Comma" to "edit.preferences",
		// Blender frames all content with Home; the numpad . frames the selection (mapped to 1:1 here).
		"Home" to "view.fit",
		"primary+Digit1" to "view.zoomActualSize",
		"Equals" to "view.zoomIn",
		"shift+Equals" to "view.zoomInCoarse",
		"Minus" to "view.zoomOut",
		"shift+Minus" to "view.zoomOutCoarse",
	)

/**
 * The chord-spec → command-id bindings for the built-in preset [presetId], falling back to the default
 * preset's bindings for an unknown id so a stale or hand-edited settings value can never leave the editor
 * with no keymap at all.
 *
 * 組み込みプリセット presetId の束縛表を返す。未知の id は既定にフォールバックする。
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
 * [org.umamo.ui.workspace.EditorShell]'s default parameter when no settings-backed keymap
 * ([org.umamo.ui.action.loadKeymap]) is supplied by the caller.
 *
 * 既定キーマップ（解決済み）。テストおよび設定連動解決の前のフォールバックに使う。
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
 *
 * コンポジション内で有効なキーマップ。深い階層のメニューがアクセラレータを解決するために static で公開する。
 */
val LocalKeymap =
	staticCompositionLocalOf<Keymap> {
		error("LocalKeymap not provided — wrap the editor in an EditorShell")
	}
