package org.umamo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.settings.Settings
import org.umamo.storage.AppStorage
import org.umamo.ui.resources.Res

/**
 * App-wide settings, provided once at the Compose root by [ProvideSettings]. Reading it before it is
 * provided is a programming error (the `error` default fires). `static` because the [Settings] instance
 * is stable for the app's life - individual values change through its `changes` flow, not by swapping the
 * local - so Compose need not track reads of this local for recomposition.
 */
val LocalSettings = staticCompositionLocalOf<Settings> { error("LocalSettings not provided — wrap content in ProvideSettings") }

/**
 * Loads [Settings] over the platform [storage] (bundled `defaultSettings.json` ← the user's file) and
 * provides it as [LocalSettings] to [content]. The load is async (the bundled default is a Compose
 * resource), so [content] renders once settings are ready; for a tiny config file that's an imperceptible
 * first frame. Each app supplies the right [storage] (`desktopAppStorage` / `androidAppStorage`).
 *
 * @param AppStorage storage The platform storage (config directory + IO).
 * @param Function   content The app content that may read [LocalSettings].
 */
@Composable
fun ProvideSettings(storage: AppStorage, content: @Composable () -> Unit) {
	val settings by produceState<Settings?>(initialValue = null, storage) {
		value = Settings.load(storage, defaultSettingsJson())
	}
	settings?.let { loaded ->
		CompositionLocalProvider(LocalSettings provides loaded, content = content)
	}
}

/**
 * Reads the bundled `defaultSettings.json` (a Compose Multiplatform resource, so desktop and Android share
 * one baseline) as text.
 *
 * @return String The default settings JSON.
 */
suspend fun defaultSettingsJson(): String = Res.readBytes("files/defaultSettings.json").decodeToString()
