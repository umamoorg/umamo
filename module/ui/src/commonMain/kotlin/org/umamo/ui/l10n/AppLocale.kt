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
 * The active UI language tag (BCP-47) for the composition, driven by the localization.locale setting rather
 * than the OS locale. Descendants that need the raw tag read `LocalAppLocale.current`; most code just calls
 * stringResource() and lets the catalogs resolve.
 */
val LocalAppLocale = staticCompositionLocalOf { "en" }

/** The settings key holding the UI language tag. */
const val LOCALE_SETTINGS_KEY: String = "localization.locale"

/** The language the UI falls back to when the setting holds none, and when no system language has a catalog. */
const val FALLBACK_LOCALE_TAG: String = "en"

/**
 * The UI languages Umamo ships, in display order: one entry per composeResources catalog, keyed by the BCP-47
 * tag written to localization.locale (which applyAppLocale feeds to the resource environment).  A language tag
 * ("ja") has its catalog in values-<language>/, a regional tag ("zh-TW") in values-<language>-r<REGION>/.
 *
 * The values are endonyms shown verbatim whatever the active UI language: a language's own name is identity,
 * not chrome to translate, and it is what a rigger who cannot read the current language looks for.
 */
val UI_LANGUAGE_ENDONYMS: Map<String, String> =
	linkedMapOf(
		"en" to "English",
		"ja" to "日本語",
		"ko" to "한국어",
		"fr" to "Français",
		"zh-CN" to "简体中文",
		"zh-TW" to "繁體中文",
	)

/**
 * The catalogs of a language whose catalogs are split by script rather than by language, and which one a tag
 * belongs to when neither its region nor the bare language has a catalog of its own.
 *
 * @property Map    byScript        Lowercase script subtag to lowercase catalog tag.
 * @property Map    byRegion        Lowercase region subtag to lowercase catalog tag.
 * @property String languageDefault The catalog tag for a tag with neither a known script nor a known region.
 */
private class ScriptSplitCatalogs(
	val byScript: Map<String, String>,
	val byRegion: Map<String, String>,
	val languageDefault: String,
)

// CLDR's parent locales for Chinese: Traditional is the Hant script and Taiwan, Hong Kong, and Macau;
// Simplified is the Hans script and mainland China, Singapore, and Malaysia, and the default for any other zh.
private val SCRIPT_SPLIT_CATALOGS: Map<String, ScriptSplitCatalogs> =
	mapOf(
		"zh" to
			ScriptSplitCatalogs(
				byScript = mapOf("hant" to "zh-tw", "hans" to "zh-cn"),
				byRegion =
					mapOf(
						"tw" to "zh-tw",
						"hk" to "zh-tw",
						"mo" to "zh-tw",
						"cn" to "zh-cn",
						"sg" to "zh-cn",
						"my" to "zh-cn",
					),
				languageDefault = "zh-cn",
			),
	)

/**
 * The operating system's preferred UI languages as BCP-47 tags, most preferred first; empty when the platform
 * reports none.  Reads the system's own preference, never the process default [applyAppLocale] overrides, so
 * the answer is the same before and after the app applies its language.
 *
 * @return List<String> The preferred language tags.
 */
expect fun systemLanguageTags(): List<String>

/**
 * Picks the UI language for a list of preferred language tags: the first preferred tag with a catalog wins.
 * Each tag is tried at its most specific first - the whole tag, then language-region, then the language alone -
 * so a region catalog ("zh-CN") is matched before a bare language one would be.  Last, a language whose
 * catalogs are split by script tries the catalog its script or region belongs to ("zh-Hant-HK" -> "zh-TW").
 * `_` separators (the JVM and POSIX spelling) read as `-`, and case does not matter.
 *
 * @param List       preferredTags The preferred language tags, most preferred first.
 * @param Collection supported     The tags that have a catalog.
 * @return String The matched supported tag, or [FALLBACK_LOCALE_TAG] when none matches.
 */
fun matchUiLanguage(preferredTags: List<String>, supported: Collection<String>): String {
	val supportedByLowercase = supported.associateBy { tag -> tag.lowercase() }

	for (preferredTag in preferredTags) {
		val subtags = preferredTag.replace('_', '-').lowercase().split('-').filter { subtag -> subtag.isNotEmpty() }
		val language = subtags.firstOrNull() ?: continue
		// A region is the two-letter or three-digit subtag after the language (and any four-letter script).
		val region = subtags.drop(1).firstOrNull { subtag -> subtag.length == 2 || (subtag.length == 3 && subtag.all(Char::isDigit)) }
		val script = subtags.drop(1).firstOrNull { subtag -> subtag.length == 4 && subtag.all(Char::isLetter) }
		// A script names the catalog before a region does: zh-Hant-SG is Traditional.
		val scriptSplitCandidate =
			SCRIPT_SPLIT_CATALOGS[language]?.let { catalogs ->
				script?.let(catalogs.byScript::get) ?: region?.let(catalogs.byRegion::get) ?: catalogs.languageDefault
			}
		val candidates = listOfNotNull(subtags.joinToString("-"), region?.let { "$language-$it" }, language, scriptSplitCandidate)

		for (candidate in candidates) {
			supportedByLowercase[candidate]?.let { matched -> return matched }
		}
	}

	return FALLBACK_LOCALE_TAG
}

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
 * Provides [languageTag] to the subtree and makes stringResource() resolve against it: the platform locale
 * is applied before children compose, and `key(languageTag)` forces the subtree - including Compose's
 * cached resource environment - to recompose on a switch so the new catalog is read.
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