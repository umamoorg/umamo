package org.umamo.ui

import okio.Path
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.l10n.LOCALE_SETTINGS_KEY
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** What a first run - no user settings file at load - seeds before anything is shown. */
class FirstRunSettingsTest {
	private val configDir: Path = "/config".toPath()

	private val defaultJson = """{"localization":{"locale":"en"}}"""

	/**
	 * An in-memory storage rooted at /config, with the user settings file seeded when [userJson] is given.
	 *
	 * @param String? userJson The user settings file's contents, or null for none.
	 * @return Pair<FakeFileSystem, OkioAppStorage> The filesystem and a storage over it.
	 */
	private fun storageWith(userJson: String? = null): Pair<FakeFileSystem, OkioAppStorage> {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories(configDir)
		if (userJson != null) {
			fileSystem.write(configDir / "settings.json") { writeUtf8(userJson) }
		}
		return fileSystem to OkioAppStorage(fileSystem, configDir, "/data".toPath())
	}

	@Test
	fun aFirstRunSeedsTheSystemLanguageAndCreatesTheFile() {
		val (fileSystem, storage) = storageWith()
		val settings = Settings.load(storage, defaultJson)

		seedFirstRunSettings(settings, systemTags = listOf("ja-JP"))

		assertEquals("ja", settings.getString(LOCALE_SETTINGS_KEY))
		assertTrue(fileSystem.exists(configDir / "settings.json"), "the seed creates the user file")
		assertTrue(Settings.load(storage, defaultJson).foundUserFile, "so the next launch is not a first run")
	}

	@Test
	fun aFirstRunWithNoCatalogForTheSystemLanguageStillPinsEnglish() {
		val (fileSystem, storage) = storageWith()
		val settings = Settings.load(storage, defaultJson)

		seedFirstRunSettings(settings, systemTags = listOf("fr-FR"))

		assertEquals("en", settings.getString(LOCALE_SETTINGS_KEY))
		assertTrue(fileSystem.exists(configDir / "settings.json"), "written even when it matches the default")
	}

	@Test
	fun existingSettingsAreNeverReseeded() {
		val (_, storage) = storageWith(userJson = """{"localization":{"locale":"ko"}}""")
		val settings = Settings.load(storage, defaultJson)

		seedFirstRunSettings(settings, systemTags = listOf("ja-JP"))

		assertEquals("ko", settings.getString(LOCALE_SETTINGS_KEY), "the rigger's own choice stands")
	}

	@Test
	fun existingSettingsWithoutALanguageAreNotSeededEither() {
		// Only a first run detects: a settings file that never chose a language keeps the default.
		val (fileSystem, storage) = storageWith(userJson = """{"interface":{"theme":"light"}}""")
		val settings = Settings.load(storage, defaultJson)

		seedFirstRunSettings(settings, systemTags = listOf("ja-JP"))

		assertEquals("en", settings.getString(LOCALE_SETTINGS_KEY))
		assertFalse(fileSystem.read(configDir / "settings.json") { readUtf8() }.contains("locale"), "nothing was written")
	}
}