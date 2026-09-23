package org.umamo.ui.l10n

import android.content.res.Resources

/**
 * The device's preferred UI languages, from the system configuration's locale list (the order the user set in
 * the system language settings).  Resources.getSystem() is the system's own configuration, which neither
 * Locale.setDefault - what applyAppLocale calls - nor a per-app override changes.
 *
 * @return List<String> The preferred language tags, most preferred first.
 */
actual fun systemLanguageTags(): List<String> {
	val locales = Resources.getSystem().configuration.locales
	return (0 until locales.size()).map { localeIndex -> locales.get(localeIndex).toLanguageTag() }
}