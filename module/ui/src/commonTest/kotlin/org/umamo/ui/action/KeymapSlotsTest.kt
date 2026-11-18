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
 * Verifies the two-slot model over an in-memory filesystem: what each slot holds by default, that each slot
 * persists on its own, that a slot resets to its default, and that the older chord-keyed override shape is
 * converted rather than lost.
 */
class KeymapSlotsTest {
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
	 * A command the preset binds once holds that chord in its primary slot and nothing in its alternative.
	 */
	@Test
	fun aCommandBoundOnceHasNoAlternative() {
		val settings = Settings.load(storage(), defaultJson)

		val slots = loadKeymap(settings).slotsFor("view.fit")
		assertEquals(parseKeyChord("primary+Digit0"), slots.primary)
		assertNull(slots.alternative)
	}

	/**
	 * A command the preset binds twice holds the first chord in its primary slot and the second in its
	 * alternative - the default preset binds the command palette to both a chord and the space bar.
	 */
	@Test
	fun aCommandBoundTwiceHasBothSlots() {
		val settings = Settings.load(storage(), defaultJson)

		val slots = loadKeymap(settings).slotsFor("palette.toggle")
		assertEquals(parseKeyChord("primary+KeyP"), slots.primary)
		assertEquals(parseKeyChord("Space"), slots.alternative)
	}

	/**
	 * The accelerator a command shows is its primary slot, never its alternative.
	 */
	@Test
	fun theAcceleratorIsThePrimarySlot() {
		val settings = Settings.load(storage(), defaultJson)

		val keymap = loadKeymap(settings)
		assertEquals(keymap.slotsFor("palette.toggle").primary, keymap.chordFor("palette.toggle"))
	}

	/**
	 * An alternative binding is stored on its own and leaves the primary where it was.
	 */
	@Test
	fun anAlternativeBindingIsPersisted() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setAlternativeBinding(settings, "view.fit", parseKeyChord("F9"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(parseKeyChord("F9"), keymap.slotsFor("view.fit").alternative)
		assertEquals(parseKeyChord("primary+Digit0"), keymap.slotsFor("view.fit").primary)
		assertEquals("view.fit", keymap.commandFor(parseKeyChord("F9")!!))
	}

	/**
	 * Resetting the primary slot puts the preset's chord back.
	 */
	@Test
	fun resettingThePrimaryRestoresThePresetChord() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setPrimaryBinding(settings, "view.fit", parseKeyChord("F5"))
		resetPrimaryBinding(settings, "view.fit")

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals(parseKeyChord("primary+Digit0"), keymap.slotsFor("view.fit").primary)
		assertNull(keymap.commandFor(parseKeyChord("F5")!!))
	}

	/**
	 * Resetting the alternative slot empties it, leaving the primary alone.
	 */
	@Test
	fun resettingTheAlternativeLeavesItEmpty() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setAlternativeBinding(settings, "view.fit", parseKeyChord("F9"))
		resetAlternativeBinding(settings, "view.fit")

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertNull(keymap.slotsFor("view.fit").alternative)
		assertEquals(parseKeyChord("primary+Digit0"), keymap.slotsFor("view.fit").primary)
	}

	/**
	 * Unbinding the primary slot leaves the command answering to nothing, and frees the chord it held.
	 */
	@Test
	fun unbindingThePrimaryFreesItsChord() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setPrimaryBinding(settings, "view.fit", null)

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertNull(keymap.slotsFor("view.fit").primary)
		assertNull(keymap.commandFor(parseKeyChord("primary+Digit0")!!))
	}

	/**
	 * A chord two commands claim goes to the later one, and the earlier one loses that slot.
	 */
	@Test
	fun aChordTwoCommandsClaimGoesToTheLaterOne() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		// primary+Digit0 is view.fit in the default preset; give it to view.zoomIn instead.
		setPrimaryBinding(settings, "view.zoomIn", parseKeyChord("primary+Digit0"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals("view.zoomIn", keymap.commandFor(parseKeyChord("primary+Digit0")!!))
		assertNull(keymap.slotsFor("view.fit").primary)
	}

	/**
	 * The older chord-keyed override shape is converted: a command id becomes that command's primary binding,
	 * and an empty value - the old unbind marker - empties the primary of the command the preset binds that
	 * chord to.
	 */
	@Test
	fun theOlderChordKeyedOverridesAreConverted() {
		val older = """{"input":{"keybinding":{"preset":"default","overrides":{"F5":"view.fit","primary+KeyS":""}}}}"""
		val settings = Settings.load(storage(), older)

		val keymap = loadKeymap(settings)
		assertEquals(parseKeyChord("F5"), keymap.slotsFor("view.fit").primary)
		assertNull(keymap.slotsFor("file.save").primary)
		assertNull(keymap.commandFor(parseKeyChord("primary+KeyS")!!))
	}

	/**
	 * The older shape was keyed by chord, so a command could be given two of them; both survive the
	 * conversion, the first as the primary and the second as the alternative.
	 */
	@Test
	fun theOlderShapeGivesACommandBothOfItsChords() {
		val older = """{"input":{"keybinding":{"preset":"default","overrides":{"F5":"view.fit","F6":"view.fit"}}}}"""
		val settings = Settings.load(storage(), older)

		val slots = loadKeymap(settings).slotsFor("view.fit")
		assertEquals(parseKeyChord("F5"), slots.primary)
		assertEquals(parseKeyChord("F6"), slots.alternative)
	}

	/**
	 * A chord two commands claim goes to the later one, and the earlier one loses only the slot that held
	 * it: the command that held the chord as its alternative keeps its primary.
	 */
	@Test
	fun aCommandLosesOnlyTheSlotThatWasTaken() {
		val storage = storage()
		val settings = Settings.load(storage, defaultJson)
		setAlternativeBinding(settings, "view.zoomIn", parseKeyChord("primary+KeyY"))
		setPrimaryBinding(settings, "view.zoomOut", parseKeyChord("primary+KeyY"))

		val keymap = loadKeymap(Settings.load(storage, defaultJson))
		assertEquals("view.zoomOut", keymap.commandFor(parseKeyChord("primary+KeyY")!!))
		assertNull(keymap.slotsFor("view.zoomIn").alternative)
		assertEquals(parseKeyChord("primary+Equals"), keymap.slotsFor("view.zoomIn").primary)
	}
}