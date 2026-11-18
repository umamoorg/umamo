package org.umamo.ui.action

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.produceState
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import org.umamo.settings.Settings

/** The settings key holding the selected built-in preset id (see [KEYMAP_PRESET_IDS]). */
const val KEYMAP_PRESET_KEY: String = "input.keybinding.preset"

/**
 * The settings key holding the user's per-command binding overrides: a JSON object of command id → slot
 * overrides, one entry per command the user has touched.
 *
 * The shape is keyed by command, not by chord, because a command has two named slots and a chord-keyed map
 * cannot say which slot a chord occupies.  Within an entry, an absent slot is at its default, an
 * empty slot is explicitly unbound, and a chord spec is a custom binding.
 *
 * ```json
 * { "view.zoomIn": { "primary": "primary+shift+Equal", "alternative": "primary+KeyZ" },
 *   "file.save": { "primary": "" } }
 * ```
 */
const val KEYMAP_OVERRIDES_KEY: String = "input.keybinding.overrides"

/**
 * One command's overridden slots, as stored.
 *
 * A null slot is absent - the slot stands at its default - while an empty string is unbound.  The
 * two are different: absent falls back to the preset's chord, empty frees the chord.
 *
 * @property String? primary The stored primary binding: null when absent, "" when unbound, a chord spec otherwise.
 * @property String? alternative The stored alternative binding, read the same way.
 */
internal data class SlotOverrides(
	val primary: String? = null,
	val alternative: String? = null,
)

/**
 * The prefix every keymap setting sits under; a change to any input.keybinding setting re-resolves the live keymap.
 */
private const val KEYMAP_SETTINGS_PREFIX: String = "input.keybinding"

/**
 * The preset's chord-spec → command-id bindings, for the preset the settings name.
 *
 * @param Settings settings The settings store to read from.
 * @return Map The preset's bindings.
 */
private fun presetSpecsFor(settings: Settings): Map<String, String> =
	keymapPresetSpecs(settings.getString(KEYMAP_PRESET_KEY) ?: "default")

/**
 * Reads the user's slot overrides as a command id → overrides map.
 *
 * A value that is an object is read as the current shape.  A value that is a string is the older
 * shape - a chord spec → command id map, where an empty command id masked the chord - and is converted
 * here.  The converted shape is written back the next time anything is stored.
 *
 * Never throws: a malformed or absent overrides object yields an empty map, so a corrupt settings value
 * falls back to the bare preset rather than breaking input.
 *
 * @param Settings settings The settings store to read from.
 * @param Map presetSpecs The preset's chord-spec → command-id bindings, for converting the older shape.
 * @return Map The overrides, keyed by command id.
 */
internal fun readSlotOverrides(settings: Settings, presetSpecs: Map<String, String>): Map<String, SlotOverrides> {
	val element = settings.get(KEYMAP_OVERRIDES_KEY) as? JsonObject ?: return emptyMap()
	val overrides = LinkedHashMap<String, SlotOverrides>()
	val legacy = LinkedHashMap<String, String>()

	for ((key, value) in element) {
		if (value is JsonObject) {
			overrides[key] =
				SlotOverrides(
					primary = value["primary"]?.let { slot -> slot.jsonPrimitive.contentOrNull },
					alternative = value["alternative"]?.let { slot -> slot.jsonPrimitive.contentOrNull },
				)
		} else {
			legacy[key] = value.jsonPrimitive.contentOrNull.orEmpty()
		}
	}

	if (legacy.isNotEmpty()) {
		for ((commandId, override) in convertLegacyOverrides(legacy, presetSpecs)) {
			overrides.putIfAbsent(commandId, override)
		}
	}

	return overrides
}

/**
 * Converts the older chord-keyed override shape into the command-keyed one.
 *
 * The older shape was keyed by chord, so a command could hold more than one chord: the first is its
 * primary and the second its alternative.  A mask entry unbinds the chord the preset binds that chord to,
 * and the command the preset binds it to keeps its own other slot.
 *
 * @param Map legacy The older shape's chord spec → command id entries, an empty command id being a mask.
 * @param Map presetSpecs The preset's chord-spec → command-id bindings.
 * @return Map The overrides, keyed by command id.
 */
private fun convertLegacyOverrides(
	legacy: Map<String, String>,
	presetSpecs: Map<String, String>,
): Map<String, SlotOverrides> {
	val chords = LinkedHashMap<String, MutableList<KeyChord>>()

	for ((spec, commandId) in legacy) {
		if (commandId.isEmpty()) {
			continue
		}

		val chord = parseKeyChord(spec) ?: continue
		chords.getOrPut(commandId) { mutableListOf() } += chord
	}

	val overrides = LinkedHashMap<String, SlotOverrides>()

	for ((commandId, commandChords) in chords) {
		overrides[commandId] =
			SlotOverrides(
				primary = chordToSpec(commandChords[0]),
				alternative = commandChords.getOrNull(1)?.let { chord -> chordToSpec(chord) },
			)
	}

	for ((spec, commandId) in legacy) {
		if (commandId.isNotEmpty()) {
			continue
		}

		val owner = presetSpecs[spec] ?: continue
		val held = overrides[owner] ?: SlotOverrides()
		overrides[owner] = held.copy(primary = "")
	}

	return overrides
}

/**
 * Writes the slot overrides back to settings, replacing the stored value wholesale.
 *
 * @param Settings settings The settings store to write to.
 * @param Map overrides The overrides to persist, keyed by command id.
 */
private fun writeSlotOverrides(settings: Settings, overrides: Map<String, SlotOverrides>) {
	val element =
		JsonObject(
			overrides.mapValues { (_, override) ->
				JsonObject(
					buildMap<String, JsonElement> {
						override.primary?.let { spec -> put("primary", JsonPrimitive(spec)) }
						override.alternative?.let { spec -> put("alternative", JsonPrimitive(spec)) }
					},
				)
			},
		)

	settings.set(KEYMAP_OVERRIDES_KEY, element)
}

/**
 * The chord a stored slot spec means: the default when the slot is absent, nothing when the spec is empty,
 * and the parsed chord otherwise - falling back to the default when the spec is malformed, so a corrupt
 * value cannot unbind a binding by accident.
 *
 * @param KeyChord? fallback The slot's default.
 * @return KeyChord? The chord the slot holds.
 */
private fun String?.slotChord(fallback: KeyChord?): KeyChord? =
	when {
		this == null -> fallback
		isEmpty() -> null
		else -> parseKeyChord(this) ?: fallback
	}

/**
 * Resolves the active [Keymap] from settings: the selected preset's bindings with the user's overrides
 * layered on top.
 *
 * The preset's chords are read first and the overrides claim their chords over them, so a rebind always
 * wins the chord it takes.  A chord two commands claim goes to the later one and the earlier one loses
 * that slot, so the slots and the chord map never disagree about who holds what.
 *
 * @param Settings settings The settings store to resolve from.
 * @return Keymap The resolved keymap.
 */
fun loadKeymap(settings: Settings): Keymap {
	val presetSpecs = presetSpecsFor(settings)
	val overrides = readSlotOverrides(settings, presetSpecs)

	// The preset's chords, grouped per command: the first is the default primary, the second the default
	// alternative, and the preset's own order is the order the chords are read in.
	val defaults = LinkedHashMap<String, MutableList<KeyChord>>()

	for ((spec, commandId) in presetSpecs) {
		val chord = parseKeyChord(spec) ?: continue
		defaults.getOrPut(commandId) { mutableListOf() } += chord
	}

	val resolved = LinkedHashMap<KeyChord, String>()
	val slots = LinkedHashMap<String, CommandSlots>()

	for ((commandId, chords) in defaults) {
		val slot = CommandSlots(primary = chords.getOrNull(0), alternative = chords.getOrNull(1))
		slots[commandId] = slot
		slot.primary?.let { chord -> resolved[chord] = commandId }
		slot.alternative?.let { chord -> resolved[chord] = commandId }
	}

	// The overrides, which win over the preset.
	for ((commandId, override) in overrides) {
		val defaultSlot = defaults[commandId]?.let { chords -> CommandSlots(chords.getOrNull(0), chords.getOrNull(1)) } ?: CommandSlots()
		val primary = override.primary.slotChord(defaultSlot.primary)
		val alternative = override.alternative.slotChord(defaultSlot.alternative)

		// The command releases the chords it no longer holds.
		val held = listOfNotNull(defaultSlot.primary, defaultSlot.alternative)
		val wanted = listOfNotNull(primary, alternative)

		for (chord in held) {
			if (chord !in wanted && resolved[chord] == commandId) {
				resolved.remove(chord)
			}
		}

		slots[commandId] = CommandSlots(primary = primary, alternative = alternative)
		claimChord(resolved, slots, commandId, primary)
		claimChord(resolved, slots, commandId, alternative)
	}

	return Keymap(resolved, slots)
}

/**
 * Claims [chord] for [commandId], taking it from whoever held it: the earlier holder loses the slot that
 * held it, so the slot map and the chord map agree about who holds what.
 *
 * @param MutableMap resolved The chord → command-id map being built.
 * @param MutableMap slots The command → slots map being built.
 * @param String commandId The command claiming the chord.
 * @param KeyChord? chord The chord claimed, or null when the slot holds none.
 */
private fun claimChord(
	resolved: MutableMap<KeyChord, String>,
	slots: MutableMap<String, CommandSlots>,
	commandId: String,
	chord: KeyChord?,
) {
	if (chord == null) {
		return
	}

	val previous = resolved.put(chord, commandId)

	if (previous != null && previous != commandId) {
		val held = slots[previous] ?: CommandSlots()
		slots[previous] =
			held.copy(
				primary = held.primary?.takeIf { it != chord },
				alternative = held.alternative?.takeIf { it != chord },
			)
	}
}

/**
 * The default slots for one command under the selected preset: the first chord the preset binds it to, and
 * the second when the preset binds it twice.
 *
 * This is what a slot falls back to when the user has not overridden it, so the shortcut editor can offer
 * "reset to default" a chord to reset to.
 *
 * @param Settings settings The settings store to read the selected preset from.
 * @param String commandId The command to read the defaults of.
 * @return CommandSlots The command's default slots.
 */
fun defaultSlotsFor(settings: Settings, commandId: String): CommandSlots {
	val chords = mutableListOf<KeyChord>()

	for ((spec, boundCommandId) in presetSpecsFor(settings)) {
		if (boundCommandId == commandId) {
			parseKeyChord(spec)?.let { chord -> chords += chord }
		}
	}

	return CommandSlots(primary = chords.getOrNull(0), alternative = chords.getOrNull(1))
}

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
 * Sets the command's primary binding: the chord it answers to, or null to leave it unbound.  The
 * alternative is untouched.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being rebound.
 * @param KeyChord? chord The chord to bind it to, or null to unbind it.
 */
fun setPrimaryBinding(settings: Settings, commandId: String, chord: KeyChord?) {
	val overrides = readSlotOverrides(settings, presetSpecsFor(settings)).toMutableMap()
	val current = overrides[commandId] ?: SlotOverrides()
	overrides[commandId] = current.copy(primary = chord?.let { chord -> chordToSpec(chord) } ?: "")
	writeSlotOverrides(settings, overrides)
}

/**
 * Sets the command's alternative binding: the chord it answers to besides its primary, or null to leave it
 * unbound.  The primary is untouched.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being rebound.
 * @param KeyChord? chord The chord to bind it to, or null to unbind it.
 */
fun setAlternativeBinding(settings: Settings, commandId: String, chord: KeyChord?) {
	val overrides = readSlotOverrides(settings, presetSpecsFor(settings)).toMutableMap()
	val current = overrides[commandId] ?: SlotOverrides()
	overrides[commandId] = current.copy(alternative = chord?.let { chord -> chordToSpec(chord) } ?: "")
	writeSlotOverrides(settings, overrides)
}

/**
 * Puts the command's primary slot back to its default: the preset's chord, or none where the preset
 * binds the command once and the alternative is empty.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being reset.
 */
fun resetPrimaryBinding(settings: Settings, commandId: String) {
	val overrides = readSlotOverrides(settings, presetSpecsFor(settings)).toMutableMap()
	val current = overrides[commandId] ?: return
	val updated = current.copy(primary = null)

	if (updated.primary == null && updated.alternative == null) {
		overrides.remove(commandId)
	} else {
		overrides[commandId] = updated
	}

	writeSlotOverrides(settings, overrides)
}

/**
 * Puts the command's alternative slot back to its default, which is empty.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being reset.
 */
fun resetAlternativeBinding(settings: Settings, commandId: String) {
	val overrides = readSlotOverrides(settings, presetSpecsFor(settings)).toMutableMap()
	val current = overrides[commandId] ?: return
	val updated = current.copy(alternative = null)

	if (updated.primary == null && updated.alternative == null) {
		overrides.remove(commandId)
	} else {
		overrides[commandId] = updated
	}

	writeSlotOverrides(settings, overrides)
}

/**
 * Rebinds [commandId] to [newChord], persisting the change.  The command moves to the new chord and
 * releases the one it held; the chord it takes is taken from whatever held it, so the caller confirms a
 * conflict with the user first.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command being rebound.
 * @param KeyChord newChord The chord to bind it to.
 */
fun rebindCommand(settings: Settings, commandId: String, newChord: KeyChord) {
	setPrimaryBinding(settings, commandId, newChord)
}

/**
 * Unbinds [commandId] entirely, persisting the change: it answers to neither of its slots.  A preset
 * binding is masked; a pure override is dropped.
 *
 * @param Settings settings The settings store to write to.
 * @param String commandId The command to unbind.
 */
fun unbindCommand(settings: Settings, commandId: String) {
	val overrides = readSlotOverrides(settings, presetSpecsFor(settings)).toMutableMap()
	overrides[commandId] = SlotOverrides(primary = "", alternative = "")
	writeSlotOverrides(settings, overrides)
}

/**
 * Clears all user overrides, returning the keymap to the selected preset's bindings (the preset choice is
 * left unchanged).  The "reset shortcuts" action.
 *
 * @param Settings settings The settings store to write to.
 */
fun resetKeymapOverrides(settings: Settings) {
	writeSlotOverrides(settings, emptyMap())
}