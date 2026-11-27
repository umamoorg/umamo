package org.umamo.ui.l10n

import java.util.Locale

/**
 * Shared JVM/Android actual: sets the JVM default locale, which Compose Multiplatform's resource
 * environment reads when resolving string catalogs.  Combined with the key()-driven recomposition in
 * ProvideAppLocale, switching localization.locale re-resolves stringResource() to the new catalog.
 * The Android configuration locale, which platform resource resolution also reads, is not changed.
 *
 * @param String languageTag The BCP-47 language tag to apply.
 */
actual fun applyAppLocale(languageTag: String) {
	Locale.setDefault(Locale.forLanguageTag(languageTag))
}