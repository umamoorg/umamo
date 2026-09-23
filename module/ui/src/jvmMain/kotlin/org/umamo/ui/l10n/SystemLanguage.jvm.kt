package org.umamo.ui.l10n

/**
 * The desktop JVM's system language, read from the user.* system properties the JVM fills from the operating
 * system at startup.  Locale.setDefault - what applyAppLocale calls - never rewrites these properties, so the
 * answer does not depend on whether the app has applied its own language yet.  The display variants win where
 * the JVM sets them (the platforms whose display and format locales can differ), since the UI language is the
 * display one.  The JVM reports a single language, not the operating system's whole preference list.
 *
 * @return List<String> The system language tag, or empty when the JVM reports none.
 */
actual fun systemLanguageTags(): List<String> {
	val language = systemProperty("user.language.display") ?: systemProperty("user.language") ?: return emptyList()
	val script = systemProperty("user.script.display") ?: systemProperty("user.script")
	val region = systemProperty("user.country.display") ?: systemProperty("user.country") ?: systemProperty("user.region")
	return listOf(listOfNotNull(language, script, region).joinToString("-"))
}

/**
 * Reads one system property, treating a blank value as unset.
 *
 * @param String name The property name.
 * @return String? The value, or null when it is unset or blank.
 */
private fun systemProperty(name: String): String? = System.getProperty(name)?.takeIf { value -> value.isNotBlank() }