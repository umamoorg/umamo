package org.umamo.ui.l10n

import java.util.Locale

/**
 * Shared JVM/Android actual: sets the JVM default locale, which Compose Multiplatform's resource
 * environment reads when resolving string catalogs. Combined with the key()-driven recomposition in
 * ProvideAppLocale, switching localization.locale re-resolves stringResource() to the new catalog.
 * On Android the configuration locale also matters for platform resource resolution; for v1 the JVM
 * default is the lever we control, and a per-Activity configuration override can layer on when the
 * Android UX is hardened.
 *
 * JVM／Android 共有実装：JVM 既定ロケールを設定する。CMP のリソース解決がこれを参照する。
 *
 * @param String languageTag The BCP-47 language tag to apply.
 */
actual fun applyAppLocale(languageTag: String) {
	Locale.setDefault(Locale.forLanguageTag(languageTag))
}
