package org.umamo.ui.viewport

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.defaultSettingsJson
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the bundled defaults of the grid settings to the Kotlin-side fallbacks: a fresh install and an area
 * with no settings behind it draw the same grid on each surface.
 */
class ViewportSettingsTest {
	/** Each surface's pair resolves out of defaultSettings.json to the constants the code falls back on. */
	@Test
	fun theBundledGridDefaultsMatchTheFallbacks() {
		val settings = bundledSettings()
		for (surface in OverlaySurface.entries) {
			val default = GridConfig.applicationDefault(surface)
			assertEquals(default.scale.toDouble(), settings.getDouble(ViewportSettings.gridScaleKey(surface)), "$surface scale")
			assertEquals(default.subdivisions, settings.getInt(ViewportSettings.gridSubdivisionsKey(surface)), "$surface subdivisions")
		}
		assertEquals(100.0, settings.getDouble(ViewportSettings.GRID_SCALE_KEY), "the 2D viewport's world grid")
		assertEquals(10, settings.getInt(ViewportSettings.GRID_SUBDIVISIONS_KEY))
		assertEquals(256.0, settings.getDouble(ViewportSettings.UV_GRID_SCALE_KEY), "the UV editor's texel grid")
		assertEquals(8, settings.getInt(ViewportSettings.UV_GRID_SUBDIVISIONS_KEY))
	}

	/**
	 * Settings over the bundled defaults and an empty in-memory config directory - a first run's.
	 *
	 * @return Settings The loaded settings.
	 */
	private fun bundledSettings(): Settings {
		val fileSystem = FakeFileSystem()
		fileSystem.createDirectories("/config".toPath())
		return Settings.load(OkioAppStorage(fileSystem, "/config".toPath(), "/data".toPath()), runBlocking { defaultSettingsJson() })
	}
}