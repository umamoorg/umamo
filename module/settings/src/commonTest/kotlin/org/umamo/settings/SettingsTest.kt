package org.umamo.settings

import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.storage.OkioAppStorage
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Settings load/merge/get/set/persist + change-event coverage, all on okio's in-memory FakeFileSystem. */
class SettingsTest {
	private val configDir: Path = "/config".toPath()

	/** A FakeFileSystem seeded with an optional user settings file, plus an [OkioAppStorage] over it. */
	private fun storageWith(userJson: String? = null): Pair<FakeFileSystem, OkioAppStorage> {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories(configDir)
		if (userJson != null) {
			fileSystem.write(configDir / "settings.json") { writeUtf8(userJson) }
		}
		return fileSystem to OkioAppStorage(fileSystem, configDir, "/data".toPath())
	}

	@Test
	fun readsDefaultsWhenNoUserFile() {
		val (_, storage) = storageWith()
		val settings = Settings.load(storage, """{"interface":{"theme":"dark"},"localization":{"locale":"en"}}""")
		assertEquals("dark", settings.getString("interface.theme"))
		assertEquals("en", settings.getString("localization.locale"))
		assertNull(settings.getString("does.not.exist"))
	}

	@Test
	fun userOverridesDefaultsButKeepsUnsetSiblings() {
		val (_, storage) = storageWith(userJson = """{"interface":{"theme":"light"}}""")
		val settings = Settings.load(storage, """{"interface":{"theme":"dark","fontSize":12}}""")
		assertEquals("light", settings.getString("interface.theme")) // overridden
		assertEquals(12, settings.getInt("interface.fontSize")) // default kept (deep merge, not replace)
	}

	@Test
	fun typedGettersParsePrimitives() {
		val (_, storage) = storageWith()
		val settings = Settings.load(storage, """{"a":{"flag":true,"count":7,"ratio":0.5,"name":"x"}}""")
		assertEquals(true, settings.getBoolean("a.flag"))
		assertEquals(7, settings.getInt("a.count"))
		assertEquals(0.5, settings.getDouble("a.ratio"))
		assertEquals("x", settings.getString("a.name"))
		assertNull(settings.getInt("a.name")) // wrong type → null, not a throw
	}

	@Test
	fun setWritesUserLayerAndSurvivesReload() {
		val (fileSystem, storage) = storageWith()
		val settings = Settings.load(storage, """{"interface":{"theme":"dark","fontSize":12}}""")
		settings.setString("interface.theme", "light")
		settings.setInt("interface.fontSize", 16)

		// A fresh load over the same filesystem sees the persisted user overrides on top of the defaults.
		val reloaded = Settings.load(OkioAppStorage(fileSystem, configDir, "/data".toPath()), """{"interface":{"theme":"dark","fontSize":12}}""")
		assertEquals("light", reloaded.getString("interface.theme"))
		assertEquals(16, reloaded.getInt("interface.fontSize"))
	}

	@Test
	fun corruptUserFileFallsBackToDefaults() {
		val (_, storage) = storageWith(userJson = "{ this is not json")
		val settings = Settings.load(storage, """{"interface":{"theme":"dark"}}""")
		assertEquals("dark", settings.getString("interface.theme")) // no throw; defaults stand
	}

	@Test
	fun setEmitsChangedKey() =
		runTest {
			val (_, storage) = storageWith()
			val settings = Settings.load(storage, """{"a":{"b":1}}""")
			val received = mutableListOf<String>()
			val collector = launch { settings.changes.collect { received.add(it) } }
			@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
			runCurrent() // let the collector subscribe before we emit
			settings.setInt("a.b", 2)
			@OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
			runCurrent() // deliver the emission
			collector.cancel()
			assertEquals(listOf("a.b"), received)
			assertTrue(settings.getInt("a.b") == 2)
		}
}
