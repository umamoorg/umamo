package org.umamo.ui.action

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.umamo.settings.Settings

/** The settings key holding the selected built-in preset id (see [KEYMAP_PRESET_IDS]). */
const val KEYMAP_PRESET_KEY: String = "input.keybinding.preset"

/**
 * The settings key holding the user's per-command overrides: a JSON object of command id → that command's chord
 * specs in order, each list replacing the selected preset's whole list for its command.  An empty list unbinds the
 * command, and a command with no entry follows the preset.
 */
const val KEYMAP_OVERRIDES_KEY: String = "input.keybinding.overrides"

/**
 * Reads the user's overrides as each command's chords, in order.  Never throws: an entry that is not a list is
 * skipped, and so is a spec that does not parse, so a corrupt or hand-mangled value falls back to the preset rather
 * than breaking input.  A chord listed twice for one command counts once.
 *
 * @param Settings settings The settings store to read from.
 * @return Map Each overridden command's chords, in order (empty = unbound).
 */
internal fun readKeymapOverrides(settings: Settings): Map<String, List<KeyChord>> {
	val element = settings.get(KEYMAP_OVERRIDES_KEY) as? JsonObject ?: return emptyMap()
	val overrides = LinkedHashMap<String, List<KeyChord>>()

	for ((commandId, value) in element) {
		val specs = value as? JsonArray ?: continue

		overrides[commandId] =
			specs
				.mapNotNull { spec -> (spec as? JsonPrimitive)?.takeIf { primitive -> primitive.isString }?.let { primitive -> parseKeyChord(primitive.content) } }
				.distinct()
	}

	return overrides
}

/**
 * Writes the overrides back to settings as each command's list of chord specs, replacing the stored value
 * wholesale.
 *
 * @param Settings settings The settings store to write to.
 * @param Map overrides Each overridden command's chords, in order.
 */
internal fun writeKeymapOverrides(settings: Settings, overrides: Map<String, List<KeyChord>>) {
	settings.set(
		KEYMAP_OVERRIDES_KEY,
		JsonObject(overrides.mapValues { (_, chords) -> JsonArray(chords.map { chord -> JsonPrimitive(chordToSpec(chord)) }) }),
	)
}

/**
 * The keymap a preset and the overrides on it resolve to.  Each command keeps one list, its override's when it has
 * one and the preset's otherwise.  Where two lists name one chord, an override outranks the preset and a later
 * override an earlier one, so the chord runs one command only; the editor never writes such a pair, so only a
 * hand-edited file reaches the second rule.
 *
 * @param Map presetBinds The preset's chords by command ([keymapPresetBinds]).
 * @param Map overrides The user's chords by command ([readKeymapOverrides]).
 * @return Keymap The resolved keymap.
 */
internal fun resolveKeymap(presetBinds: Map<String, List<KeyChord>>, overrides: Map<String, List<KeyChord>>): Keymap {
	val resolved = LinkedHashMap<KeyChord, String>()
	// The preset's lists for the commands no override replaces, then every override, in file order.
	val lists = presetBinds.filterKeys { commandId -> commandId !in overrides } + overrides

	for ((commandId, chords) in lists) {
		for (chord in chords) {
			// Removed and put back, never overwritten in place, so a chord taken from an earlier list moves to
			// its new owner's place and every command's chords read back in its own list's order.
			resolved.remove(chord)
			resolved[chord] = commandId
		}
	}

	return Keymap(resolved)
}

/**
 * The selected preset's id, the default when none is stored.
 *
 * @param Settings settings The settings store to read from.
 * @return String The preset id.
 */
private fun selectedPresetId(settings: Settings): String = settings.getString(KEYMAP_PRESET_KEY) ?: "default"

/**
 * Resolves the active [Keymap] from settings: the selected preset's chords ([keymapPresetBinds]) with each
 * overridden command's list put in place of its preset one ([resolveKeymap]).
 *
 * @param Settings settings The settings store to resolve from.
 * @return Keymap The resolved keymap.
 */
fun loadKeymap(settings: Settings): Keymap = resolveKeymap(keymapPresetBinds(selectedPresetId(settings)), readKeymapOverrides(settings))

/** The prefix every keymap setting sits under; a change to any key below it re-resolves the live keymap. */
private const val KEYMAP_SETTINGS_PREFIX: String = "input.keybinding"

/**
 * The active [Keymap] as live state: resolved from the selected preset plus the user's overrides, and
 * re-resolved whenever any input.keybinding setting changes, so a preset switch or a rebind in the
 * settings window reaches the menu's accelerator hints, the palette, and live dispatch at once.
 *
 * @param Settings settings The settings store to resolve from and follow.
 * @return State The live keymap.
 */
@Composable
fun rememberLiveKeymap(settings: Settings): State<Keymap> =
	produceState(initialValue = loadKeymap(settings), settings) {
		settings.changes.collect { changedKey ->
			if (changedKey.startsWith(KEYMAP_SETTINGS_PREFIX)) {
				value = loadKeymap(settings)
			}
		}
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
 * Gives [commandId] one more chord, after the ones it has, and persists it.  The chord is taken from whatever command
 * held it, so the caller confirms a conflict with the user first.  A chord the command already holds changes nothing.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command gaining the chord.
 * @param KeyChord chord The chord to add.
 */
fun addCommandBind(settings: Settings, commandId: String, chord: KeyChord) {
	editCommandBinds(settings, commandId) { chords -> if (chord in chords) chords else chords + chord }
}

/**
 * Puts [chord] in the place of [previous], one of [commandId]'s chords, and persists it.  The chord is taken from
 * whatever command held it, as [addCommandBind] does.  A chord the command already holds changes nothing, and a
 * [previous] it no longer holds makes this an add.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command whose chord changes.
 * @param KeyChord previous The chord being replaced.
 * @param KeyChord chord The chord to put in its place.
 */
fun replaceCommandBind(settings: Settings, commandId: String, previous: KeyChord, chord: KeyChord) {
	editCommandBinds(settings, commandId) { chords ->
		val previousIndex = chords.indexOf(previous)

		when {
			chord in chords -> chords
			previousIndex < 0 -> chords + chord
			else -> chords.mapIndexed { chordIndex, held -> if (chordIndex == previousIndex) chord else held }
		}
	}
}

/**
 * Removes [chord] from [commandId]'s chords and persists it; the rest keep their order.  Removing the last one leaves
 * the command unbound.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command losing the chord.
 * @param KeyChord chord The chord to remove.
 */
fun removeCommandBind(settings: Settings, commandId: String, chord: KeyChord) {
	editCommandBinds(settings, commandId) { chords -> chords - chord }
}

/**
 * Moves [chord] one place earlier in [commandId]'s chords and persists it, so a rigger chooses which chord comes
 * first: the one menus, the palette, and the status bar show.  A chord already first, or one the command does not
 * hold, changes nothing.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command whose chords are reordered.
 * @param KeyChord chord The chord to move.
 */
fun moveCommandBindUp(settings: Settings, commandId: String, chord: KeyChord) {
	editCommandBinds(settings, commandId) { chords ->
		val chordIndex = chords.indexOf(chord)

		when {
			chordIndex <= 0 -> chords
			else -> chords.toMutableList().apply { add(chordIndex - 1, removeAt(chordIndex)) }
		}
	}
}

/**
 * Applies [edit] to [commandId]'s chords and persists the result.  A chord the new list gains is taken from the
 * command that held it in the same write, so the file never names one chord for two commands.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being edited.
 * @param Function edit Maps the command's current chords to its new ones.
 */
private fun editCommandBinds(settings: Settings, commandId: String, edit: (List<KeyChord>) -> List<KeyChord>) {
	val keymap = loadKeymap(settings)
	val current = keymap.chordsFor(commandId)
	val edited = edit(current)

	if (edited == current) {
		return
	}

	val lists = LinkedHashMap<String, List<KeyChord>>()

	for (chord in edited) {
		val holder = keymap.commandFor(chord)

		if (holder != null && holder != commandId) {
			lists[holder] = (lists[holder] ?: keymap.chordsFor(holder)) - chord
		}
	}

	lists[commandId] = edited
	writeCommandBinds(settings, lists)
}

/**
 * Stores each command's new chords as its override.  A command whose chords are back to the selected preset's loses
 * its override instead, so the file holds only what differs from the preset.
 *
 * @param Settings settings The settings store to write to.
 * @param Map binds The new chords of each command the edit touched.
 */
private fun writeCommandBinds(settings: Settings, binds: Map<String, List<KeyChord>>) {
	val presetBinds = keymapPresetBinds(selectedPresetId(settings))
	val overrides = LinkedHashMap(readKeymapOverrides(settings))

	for ((commandId, chords) in binds) {
		if (chords == presetBinds[commandId].orEmpty()) {
			overrides.remove(commandId)
		} else {
			overrides[commandId] = chords
		}
	}

	writeKeymapOverrides(settings, overrides)
}

/**
 * Clears all user overrides, returning the keymap to the selected preset's bindings (the preset choice is
 * left unchanged).  The "reset shortcuts" action.
 *
 * @param Settings settings The settings store to write to.
 */
fun resetKeymapOverrides(settings: Settings) {
	writeKeymapOverrides(settings, emptyMap())
}