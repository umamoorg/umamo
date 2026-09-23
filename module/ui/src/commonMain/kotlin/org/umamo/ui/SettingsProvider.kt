package org.umamo.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.settings.Settings
import org.umamo.storage.AppStorage
import org.umamo.ui.l10n.LOCALE_SETTINGS_KEY
import org.umamo.ui.l10n.UI_LANGUAGE_ENDONYMS
import org.umamo.ui.l10n.matchUiLanguage
import org.umamo.ui.l10n.systemLanguageTags
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
		value = loadAppSettings(storage)
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

/**
 * Loads the app's settings - the bundled defaults under the user's file - and seeds a first run's choices, so
 * every platform starts from the same state.  The one load path both apps take: the desktop entrypoint calls
 * it directly (it must hold settings before its window opens), and [ProvideSettings] calls it for Android.
 *
 * @param AppStorage storage The platform storage (config directory + IO).
 * @return Settings The loaded, first-run-seeded settings.
 */
suspend fun loadAppSettings(storage: AppStorage): Settings = Settings.load(storage, defaultSettingsJson()).also { settings -> seedFirstRunSettings(settings) }

/**
 * Seeds what a first run decides before anything is shown: the UI language, matched from the operating
 * system's preferred languages (English when none has a catalog).  Runs only when no user settings file was
 * found at load, so a language the rigger chose is never overridden.
 *
 * The match is written even when it is English: it pins the language the app first opened in, and it creates
 * the user file, so the next launch finds settings and does not treat itself as a first run.  The Quick Setup
 * modal a first run opens then shows this language already selected.
 *
 * @param Settings settings   The freshly loaded settings.
 * @param List     systemTags The operating system's preferred language tags, most preferred first.
 */
fun seedFirstRunSettings(settings: Settings, systemTags: List<String> = systemLanguageTags()) {
	if (settings.foundUserFile) {
		return
	}
	settings.setString(LOCALE_SETTINGS_KEY, matchUiLanguage(systemTags, UI_LANGUAGE_ENDONYMS.keys))
}