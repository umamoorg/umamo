package org.umamo.ui.l10n

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import org.umamo.settings.Settings

/**
 * The active UI language tag (BCP-47, e.g. "en" / "ja" / "ko") for the composition, driven by the
 * localization.locale setting rather than the OS locale. Descendants that need the raw tag read
 * `LocalAppLocale.current`; most code just calls stringResource() and lets the catalogs resolve.
 */
val LocalAppLocale = staticCompositionLocalOf { "en" }

/** The settings key holding the UI language tag. */
private const val LOCALE_SETTINGS_KEY: String = "localization.locale"

/** The language the UI falls back to when the setting holds none. */
private const val FALLBACK_LOCALE_TAG: String = "en"

/**
 * The UI language tag as live state: read from the localization.locale setting and updated when it
 * changes, so whatever is keyed on it re-localizes on a runtime language switch.
 *
 * @param Settings settings The settings store to read and follow.
 * @return State The live BCP-47 language tag.
 */
@Composable
fun rememberLocaleTag(settings: Settings): State<String> =
	produceState(initialValue = settings.getString(LOCALE_SETTINGS_KEY) ?: FALLBACK_LOCALE_TAG, settings) {
		settings.changes.collect { changedKey ->
			if (changedKey == LOCALE_SETTINGS_KEY) {
				value = settings.getString(LOCALE_SETTINGS_KEY) ?: FALLBACK_LOCALE_TAG
			}
		}
	}

/**
 * Applies [languageTag] as the process resource locale on the current platform. Compose
 * Multiplatform has no first-party "override the resource locale at runtime" API yet (JetBrains issue
 * #4197), so the portable lever is the JVM default locale, which the desktop/Android resource
 * environment derives from. expect/actual keeps java.util.Locale out of commonMain (absent there).
 *
 * @param String languageTag The BCP-47 language tag to apply.
 */
expect fun applyAppLocale(languageTag: String)

/**
 * Provides [languageTag] to the subtree and makes stringResource() resolve against it. Two effects:
 * [applyAppLocale] sets the platform locale before children compose (via remember keyed on the tag,
 * so it re-runs only when the language actually changes), and `key(languageTag)` forces the subtree -
 * including Compose's cached resource environment - to recompose on a switch so the new catalog is read.
 *
 * @param String languageTag The BCP-47 language tag to make active.
 * @param Function content The subtree to render under this locale.
 */
@Composable
fun ProvideAppLocale(languageTag: String, content: @Composable () -> Unit) {
	// remember(key) runs once per distinct tag - applies the locale before the keyed content composes.
	remember(languageTag) {
		applyAppLocale(languageTag)
		languageTag
	}
	CompositionLocalProvider(LocalAppLocale provides languageTag) {
		key(languageTag) {
			content()
		}
	}
}