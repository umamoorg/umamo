package org.umamo.ui.action

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies settings-backed keymap resolution and editing over an in-memory filesystem: preset selection, per-command
 * override lists, adding, replacing, removing, and moving up one chord, taking a chord from its holder, and reset,
 * each read back through a fresh Settings load.
 */
class KeymapPersistenceTest {
	private val configDir: Path = "/config".toPath()

	/** Defaults with the input.keybinding subtree the persistence reads/writes. */
	private val defaultJson = """{"input":{"keybinding":{"preset":"default","overrides":{}}}}"""

	/** A fresh in-memory storage rooted at /config + /data. */
	private fun storage(): OkioAppStorage {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories(configDir)

		return OkioAppStorage(fileSystem, configDir, "/data".toPath())
	}

	/**
	 * Parses a chord spec the test knows is valid.
	 *
	 * @param String spec The chord spec.
	 * @return KeyChord The chord.
	 */
	private fun chord(spec: String): KeyChord = checkNotNull(parseKeyChord(spec)) { "spec $spec parses" }

	/**
	 * Builds the stored overrides object from each command's specs, as a rigger's settings file holds it.
	 *
	 * @param Pair entries Each command id with its chord specs.
	 * @return JsonObject The overrides value.
	 */
	private fun overrides(vararg entries: Pair<String, List<String>>): JsonObject = JsonObject(entries.associate { (commandId, specs) -> commandId to JsonArray(specs.map { spec -> JsonPrimitive(spec) }) })

	/**
	 * The stored overrides, read raw.
	 *
	 * @param Settings settings The settings store.
	 * @return JsonElement? The overrides value.
	 */
	private fun storedOverrides(settings: Settings): JsonElement? = settings.get(KEYMAP_OVERRIDES_KEY)

	/** With no overrides, the resolved keymap is exactly the selected preset's bindings. */
	@Test
	fun resolvesSelectedPreset() {
		val settings = Settings.load(storage(), defaultJson)
		val keymap = loadKeymap(settings)
		assertEquals("palette.toggle", keymap.commandFor(chord("primary+KeyP")))
		assertEquals(listOf(chord("primary+KeyP"), chord("Space")), keymap.chordsFor("palette.toggle"))
		assertEquals(chord("primary+Digit0"), keymap.chordFor("view.fit"))
	}

	/**
	 * Selecting a preset changes the base bindings (Blender frames all on Home - the context-aware
	 * frame.all, which resolves per hovered editor - and searches on F3).
	 */
	@Test
	fun presetSelectionSwitchesBase() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setKeymapPreset(settings, "blender")

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(chord("Home"), keymap.chordFor("frame.all"))
		assertEquals("palette.toggle", keymap.commandFor(chord("F3")))
	}

	/** A command's override replaces the preset's whole list for it, and every chord it drops is free. */
	@Test
	fun anOverrideReplacesTheCommandsWholeList() {
		val settings = Settings.load(storage(), defaultJson)
		settings.set(KEYMAP_OVERRIDES_KEY, overrides("palette.toggle" to listOf("F2")))

		val keymap = loadKeymap(settings)
		assertEquals(listOf(chord("F2")), keymap.chordsFor("palette.toggle"))
		assertNull(keymap.commandFor(chord("primary+KeyP")))
		assertNull(keymap.commandFor(chord("Space")))
	}

	/** An empty list unbinds the command and leaves every other command as the preset has it. */
	@Test
	fun anEmptyListUnbinds() {
		val settings = Settings.load(storage(), defaultJson)
		settings.set(KEYMAP_OVERRIDES_KEY, overrides("view.fit" to emptyList()))

		val keymap = loadKeymap(settings)
		assertEquals(emptyList(), keymap.chordsFor("view.fit"))
		assertNull(keymap.commandFor(chord("primary+Digit0")))
		assertEquals("file.open", keymap.commandFor(chord("primary+KeyO")))
	}

	/** A spec that does not parse and a value that is not a spec are dropped, and a chord listed twice counts once. */
	@Test
	fun readingSkipsWhatItCannotUse() {
		val settings = Settings.load(storage(), defaultJson)
		settings.set(
			KEYMAP_OVERRIDES_KEY,
			JsonObject(mapOf("view.fit" to JsonArray(listOf(JsonPrimitive("F5"), JsonPrimitive("bogus+F6"), JsonPrimitive("F5"), JsonPrimitive(3))))),
		)

		assertEquals(listOf(chord("F5")), loadKeymap(settings).chordsFor("view.fit"))
	}

	/** Adding a chord puts it after the command's others, so the first, the one menus show, stays; it survives a reload. */
	@Test
	fun addAppendsAndPersists() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		addCommandBind(settings, "view.fit", chord("F5"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(listOf(chord("primary+Digit0"), chord("F5")), keymap.chordsFor("view.fit"))
		assertEquals(chord("primary+Digit0"), keymap.chordFor("view.fit"))
		assertEquals("view.fit", keymap.commandFor(chord("F5")))
	}

	/** Replacing a chord keeps its place in the command's list, so replacing the first changes what menus show. */
	@Test
	fun replaceKeepsThePlace() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		replaceCommandBind(settings, "palette.toggle", chord("primary+KeyP"), chord("F2"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(listOf(chord("F2"), chord("Space")), keymap.chordsFor("palette.toggle"))
		assertNull(keymap.commandFor(chord("primary+KeyP")))
	}

	/** Capturing a chord the command already holds, by add or by replace, changes nothing and stores nothing. */
	@Test
	fun aChordTheCommandAlreadyHoldsChangesNothing() {
		val settings = Settings.load(storage(), defaultJson)
		addCommandBind(settings, "palette.toggle", chord("Space"))
		replaceCommandBind(settings, "palette.toggle", chord("primary+KeyP"), chord("Space"))

		assertEquals(listOf(chord("primary+KeyP"), chord("Space")), loadKeymap(settings).chordsFor("palette.toggle"))
		assertEquals(JsonObject(emptyMap()), storedOverrides(settings))
	}

	/** Removing one chord leaves the command's others in order, and removing the last leaves it unbound. */
	@Test
	fun removeDropsOneChord() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		removeCommandBind(settings, "palette.toggle", chord("primary+KeyP"))

		assertEquals(listOf(chord("Space")), loadKeymap(Settings.load(storage, defaultJson)).chordsFor("palette.toggle"))

		removeCommandBind(settings, "palette.toggle", chord("Space"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(emptyList(), keymap.chordsFor("palette.toggle"))
		assertNull(keymap.commandFor(chord("Space")))
	}

	/**
	 * Moving a chord up brings it a place forward, so the second becomes the first, the one menus show; the list
	 * survives a reload, and moving the other back stores nothing, since the preset's order is restored.
	 */
	@Test
	fun moveUpBringsAChordForwardAndBackStoresNothing() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		moveCommandBindUp(settings, "palette.toggle", chord("Space"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(listOf(chord("Space"), chord("primary+KeyP")), keymap.chordsFor("palette.toggle"))
		assertEquals(chord("Space"), keymap.chordFor("palette.toggle"))
		assertEquals(overrides("palette.toggle" to listOf("Space", "primary+KeyP")), storedOverrides(settings))

		moveCommandBindUp(settings, "palette.toggle", chord("primary+KeyP"))
		assertEquals(listOf(chord("primary+KeyP"), chord("Space")), loadKeymap(settings).chordsFor("palette.toggle"))
		assertEquals(JsonObject(emptyMap()), storedOverrides(settings))
	}

	/** Moving up the first chord, or one the command does not hold, changes nothing and stores nothing. */
	@Test
	fun moveUpOfTheFirstOrAnAbsentChordChangesNothing() {
		val settings = Settings.load(storage(), defaultJson)
		moveCommandBindUp(settings, "palette.toggle", chord("primary+KeyP"))
		moveCommandBindUp(settings, "palette.toggle", chord("F5"))

		assertEquals(listOf(chord("primary+KeyP"), chord("Space")), loadKeymap(settings).chordsFor("palette.toggle"))
		assertEquals(JsonObject(emptyMap()), storedOverrides(settings))
	}

	/**
	 * A chord bound to a command another command holds is taken from that one alone: the holder keeps its other
	 * chords, whether its list came from the preset or from an override of its own.
	 */
	@Test
	fun takingAChordStripsOnlyThatChordFromItsHolder() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		// The holder's list is the preset's.
		addCommandBind(settings, "view.fit", chord("Space"))

		var keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(listOf(chord("primary+KeyP")), keymap.chordsFor("palette.toggle"))
		assertEquals(listOf(chord("primary+Digit0"), chord("Space")), keymap.chordsFor("view.fit"))

		// The holder's list is an override.
		addCommandBind(settings, "palette.toggle", chord("F2"))
		replaceCommandBind(settings, "view.zoomIn", chord("primary+Equals"), chord("F2"))

		keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(listOf(chord("primary+KeyP")), keymap.chordsFor("palette.toggle"))
		assertEquals(listOf(chord("F2")), keymap.chordsFor("view.zoomIn"))
		assertEquals("view.zoomIn", keymap.commandFor(chord("F2")))
	}

	/** A command edited back to the preset's chords keeps no override, so the file holds only what differs. */
	@Test
	fun anOverrideEqualToThePresetIsNotStored() {
		val settings = Settings.load(storage(), defaultJson)
		addCommandBind(settings, "view.fit", chord("F5"))
		assertEquals(overrides("view.fit" to listOf("primary+Digit0", "F5")), storedOverrides(settings))

		removeCommandBind(settings, "view.fit", chord("F5"))
		assertEquals(JsonObject(emptyMap()), storedOverrides(settings))
	}

	/**
	 * An override in the old chord-keyed shape is ignored, so the keymap is the bare preset, and the next edit writes
	 * the overrides without it.
	 */
	@Test
	fun anOldShapeEntryIsIgnoredAndGoneAfterTheNextWrite() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		settings.set(KEYMAP_OVERRIDES_KEY, JsonObject(mapOf("primary+KeyS" to JsonPrimitive("view.fit"), "primary+Digit0" to JsonPrimitive(""))))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals("file.save", keymap.commandFor(chord("primary+KeyS")))
		assertEquals("view.fit", keymap.commandFor(chord("primary+Digit0")))

		addCommandBind(settings, "view.fit", chord("F5"))
		assertEquals(overrides("view.fit" to listOf("primary+Digit0", "F5")), storedOverrides(Settings.load(storage, defaultJson)))
	}

	/** A preset switch keeps the overrides, and an override outranks the new preset where both name one chord. */
	@Test
	fun aPresetSwitchKeepsOverridesAndTheOverrideWinsAContestedChord() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		addCommandBind(settings, "view.fit", chord("F3"))
		setKeymapPreset(settings, "blender")

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals("view.fit", keymap.commandFor(chord("F3")))
		assertEquals(emptyList(), keymap.chordsFor("palette.toggle"), "Blender's only palette chord is F3")
	}

	/** Two overrides that name one chord, which only a hand-edited file holds, resolve to the later one. */
	@Test
	fun aHandEditedDuplicateResolvesToTheLaterEntry() {
		val settings = Settings.load(storage(), defaultJson)
		settings.set(KEYMAP_OVERRIDES_KEY, overrides("view.fit" to listOf("F5"), "view.zoomIn" to listOf("F5")))

		val keymap = loadKeymap(settings)
		assertEquals("view.zoomIn", keymap.commandFor(chord("F5")))
		assertEquals(emptyList(), keymap.chordsFor("view.fit"))
	}

	/** Reset clears all overrides, restoring the selected preset's bindings (preset choice unchanged). */
	@Test
	fun resetRestoresPreset() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		replaceCommandBind(settings, "view.fit", chord("primary+Digit0"), chord("F5"))
		resetKeymapOverrides(settings)

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(chord("primary+Digit0"), keymap.chordFor("view.fit"))
		assertNull(keymap.commandFor(chord("F5")))
	}
}