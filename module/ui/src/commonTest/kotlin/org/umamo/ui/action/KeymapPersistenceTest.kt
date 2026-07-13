package org.umamo.ui.action

import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Verifies settings-backed keymap resolution and mutation over an in-memory filesystem: preset selection,
 * rebinding (move + conflict reassign), unbinding, and reset, each persisting across a fresh Settings load.
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
	 * With no overrides, the resolved keymap is exactly the selected preset's bindings.
	 */
	@Test
	fun resolvesSelectedPreset() {
		val settings = Settings.load(storage(), defaultJson)
		val keymap = loadKeymap(settings)
		assertEquals("palette.toggle", keymap.commandFor(parseKeyChord("primary+KeyP")!!))
		assertEquals(parseKeyChord("primary+Digit0"), keymap.chordFor("view.fit"))
	}

	/**
	 * Selecting a preset changes the base bindings (Blender frames all on Home, searches on F3).
	 */
	@Test
	fun presetSelectionSwitchesBase() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setKeymapPreset(settings, "blender")

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(parseKeyChord("Home"), keymap.chordFor("view.fit"))
		assertEquals("palette.toggle", keymap.commandFor(parseKeyChord("F3")!!))
	}

	/**
	 * Rebinding moves a command to a new chord (releasing its preset chord) and survives a reload.
	 */
	@Test
	fun rebindMovesBindingAndPersists() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		rebindCommand(settings, "view.fit", parseKeyChord("F5")!!)

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals("view.fit", keymap.commandFor(parseKeyChord("F5")!!))
		// The preset chord it used to hold is now unbound (masked), so view.fit no longer answers to it.
		assertNull(keymap.commandFor(parseKeyChord("primary+Digit0")!!))
		assertEquals(parseKeyChord("F5"), keymap.chordFor("view.fit"))
	}

	/**
	 * Rebinding a command onto a chord another command holds reassigns it: the new command wins, the old
	 * one loses that chord.
	 */
	@Test
	fun rebindOntoOccupiedChordReassigns() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		// primary+Digit0 is view.fit in the default preset; give it to view.zoomIn instead.
		rebindCommand(settings, "view.zoomIn", parseKeyChord("primary+Digit0")!!)

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals("view.zoomIn", keymap.commandFor(parseKeyChord("primary+Digit0")!!))
	}

	/**
	 * Unbinding clears a command's chord and persists, leaving other bindings intact.
	 */
	@Test
	fun unbindClearsAndPersists() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		unbindCommand(settings, "view.fit")

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertNull(keymap.chordFor("view.fit"))
		assertNull(keymap.commandFor(parseKeyChord("primary+Digit0")!!))
		// An untouched binding is still present.
		assertEquals("file.open", keymap.commandFor(parseKeyChord("primary+KeyO")!!))
	}

	/**
	 * Reset clears all overrides, restoring the selected preset's bindings (preset choice unchanged).
	 */
	@Test
	fun resetRestoresPreset() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		rebindCommand(settings, "view.fit", parseKeyChord("F5")!!)
		resetKeymapOverrides(settings)

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(parseKeyChord("primary+Digit0"), keymap.chordFor("view.fit"))
		assertNull(keymap.commandFor(parseKeyChord("F5")!!))
	}
}
