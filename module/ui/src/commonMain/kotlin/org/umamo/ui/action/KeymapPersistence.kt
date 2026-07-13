package org.umamo.ui.action

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.settings.Settings

/** The settings key holding the selected built-in preset id (see [KEYMAP_PRESET_IDS]). */
const val KEYMAP_PRESET_KEY: String = "input.keybinding.preset"

/**
 * The settings key holding the user's per-chord overrides: a JSON object of chord-spec → command-id that
 * layers over the selected preset.  An empty-string value is an explicit "unbind" marker (it masks a chord
 * the preset binds); a deleted entry falls back to the preset.  This mirrors the settings engine's own
 * defaults-then-overrides shape, one level down at the keymap.
 */
const val KEYMAP_OVERRIDES_KEY: String = "input.keybinding.overrides"

/**
 * Reads the user's chord overrides as a plain chord-spec → command-id map (an empty value means the chord
 * is explicitly unbound).  Never throws: a malformed or absent overrides object yields an empty map, so a
 * corrupt settings value falls back to the bare preset rather than breaking input.
 *
 * ユーザーのコード上書きを「指定文字列→コマンド id」のマップとして読む（空値は明示的な解除）。例外は投げない。
 *
 * @param Settings settings The settings store to read from.
 * @return Map The override map (chord spec → command id; "" = unbound).
 */
internal fun readKeymapOverrides(settings: Settings): Map<String, String> {
	val element = settings.get(KEYMAP_OVERRIDES_KEY) as? JsonObject ?: return emptyMap()
	val overrides = LinkedHashMap<String, String>()
	for ((spec, value) in element) {
		val commandId = runCatching { value.jsonPrimitive.contentOrNull }.getOrNull() ?: ""
		overrides[spec] = commandId
	}
	return overrides
}

/**
 * Writes the chord overrides back to settings as a JSON object (chord-spec → command-id), replacing the
 * stored value wholesale.  Persists via the Settings user layer and emits the [KEYMAP_OVERRIDES_KEY] change
 * so the reactive keymap resolver rebuilds.
 *
 * コード上書きを JSON オブジェクトとして保存する（全置換）。
 *
 * @param Settings settings The settings store to write to.
 * @param Map overrides The override map to persist.
 */
internal fun writeKeymapOverrides(settings: Settings, overrides: Map<String, String>) {
	settings.set(KEYMAP_OVERRIDES_KEY, JsonObject(overrides.mapValues { entry -> JsonPrimitive(entry.value) }))
}

/**
 * Resolves the active [Keymap] from settings: the selected preset's bindings ([keymapPresetSpecs]) with the
 * user's overrides layered on top (an empty-string override unbinds the chord).  This is the settings-backed
 * replacement for the bare [defaultKeymap]; the shell resolves it reactively and re-resolves on any
 * input.keybinding change.
 *
 * 設定から有効なキーマップを解決する（プリセット＋ユーザー上書き、空値は解除）。
 *
 * @param Settings settings The settings store to resolve from.
 * @return Keymap The resolved keymap.
 */
fun loadKeymap(settings: Settings): Keymap {
	val presetId = settings.getString(KEYMAP_PRESET_KEY) ?: "default"
	val resolved = LinkedHashMap<KeyChord, String>()
	for ((spec, commandId) in keymapPresetSpecs(presetId)) {
		val chord = parseKeyChord(spec) ?: continue
		resolved[chord] = commandId
	}
	for ((spec, commandId) in readKeymapOverrides(settings)) {
		val chord = parseKeyChord(spec) ?: continue
		if (commandId.isEmpty()) {
			resolved.remove(chord) // explicit unbind: mask a chord the preset binds
		} else {
			resolved[chord] = commandId
		}
	}
	return Keymap(resolved)
}

/**
 * Selects the built-in preset [presetId] (persisted; user overrides still layer on top).  An unknown id is
 * stored as-is and resolves to the default preset via [keymapPresetSpecs]'s fallback.
 *
 * @param Settings settings The settings store to write to.
 * @param String presetId The preset id to select.
 */
fun setKeymapPreset(settings: Settings, presetId: String) {
	settings.setString(KEYMAP_PRESET_KEY, presetId)
}

/**
 * Whether [chord] is bound by the currently selected preset's base bindings (before overrides).  Used when
 * releasing a chord: a preset binding must be masked with an unbind marker, while a pure override is simply
 * dropped.
 *
 * @param Settings settings The settings store (for the selected preset).
 * @param KeyChord chord The chord to test.
 * @return Boolean true if the preset base binds this chord.
 */
private fun isPresetChord(settings: Settings, chord: KeyChord): Boolean {
	val presetId = settings.getString(KEYMAP_PRESET_KEY) ?: "default"
	return keymapPresetSpecs(presetId).keys.any { spec -> parseKeyChord(spec) == chord }
}

/**
 * Releases [chord] in [overrides] (mutated in place): masks it with an unbind marker if the preset binds it,
 * otherwise removes the override entry.  Either way the chord ends up unbound after resolution.
 *
 * @param Settings settings The settings store (for the preset check).
 * @param MutableMap overrides The override map being edited.
 * @param KeyChord chord The chord to release.
 */
private fun releaseChord(settings: Settings, overrides: MutableMap<String, String>, chord: KeyChord) {
	val spec = chordToSpec(chord)
	if (isPresetChord(settings, chord)) {
		overrides[spec] = ""
	} else {
		overrides.remove(spec)
	}
}

/**
 * Rebinds [commandId] to [newChord], persisting the change.  Releases the command's existing canonical chord
 * first so the command moves rather than gaining a second binding, then binds [newChord] to it - which also
 * resolves any conflict, since the new entry wins over whatever previously held that chord (the caller is
 * expected to confirm a conflict with the user first; see the keybindings editor).
 *
 * commandId を newChord に再割り当てして保存する。既存の正規コードを解放してから新コードを束縛する。
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being rebound.
 * @param KeyChord newChord The chord to bind it to.
 */
fun rebindCommand(settings: Settings, commandId: String, newChord: KeyChord) {
	val overrides = readKeymapOverrides(settings).toMutableMap()
	val previous = loadKeymap(settings).chordFor(commandId)
	if (previous != null && previous != newChord) {
		releaseChord(settings, overrides, previous)
	}
	overrides[chordToSpec(newChord)] = commandId
	writeKeymapOverrides(settings, overrides)
}

/**
 * Unbinds [commandId]'s canonical chord, persisting the change (a no-op if it has no binding).  A preset
 * binding is masked with an unbind marker; a pure override is dropped.
 *
 * commandId の正規コードを解除して保存する（束縛が無ければ何もしない）。
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command to unbind.
 */
fun unbindCommand(settings: Settings, commandId: String) {
	val chord = loadKeymap(settings).chordFor(commandId) ?: return
	val overrides = readKeymapOverrides(settings).toMutableMap()
	releaseChord(settings, overrides, chord)
	writeKeymapOverrides(settings, overrides)
}

/**
 * Clears all user overrides, returning the keymap to the selected preset's bindings (the preset choice is
 * left unchanged).  The "reset shortcuts" action.
 *
 * すべてのユーザー上書きを消去し、選択中のプリセットに戻す（プリセットの選択はそのまま）。
 *
 * @param Settings settings The settings store to write to.
 */
fun resetKeymapOverrides(settings: Settings) {
	writeKeymapOverrides(settings, emptyMap())
}
