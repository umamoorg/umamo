package org.umamo.ui.viewport

import kotlinx.coroutines.runBlocking
import okio.Path.Companion.toPath
import okio.fakefilesystem.FakeFileSystem
import org.umamo.settings.Settings
import org.umamo.storage.OkioAppStorage
import org.umamo.ui.defaultSettingsJson
import org.umamo.ui.workspace.ViewportChromeState
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Pins the bundled defaults of the viewport settings to the Kotlin-side fallbacks: a fresh install and an
 * area with no settings behind it draw the same grid on each surface and show the same chrome.
 */
class ViewportSettingsTest {
	/** The chrome defaults resolve out of defaultSettings.json to the constants a standalone shell renders. */
	@Test
	fun theBundledChromeDefaultsMatchTheFallbacks() {
		val settings = bundledSettings()

		assertEquals(ViewportSettings.SHOW_TOOLBAR_DEFAULT, settings.getBoolean(ViewportSettings.SHOW_TOOLBAR_KEY), "the toolbar")
		assertEquals(ViewportSettings.SHOW_SIDEBAR_DEFAULT, settings.getBoolean(ViewportSettings.SHOW_SIDEBAR_KEY), "the sidebar")
		assertEquals(ViewportChromeState(showToolbar = true, showSidebar = false), ViewportChromeState())
	}

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