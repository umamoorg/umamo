package org.umamo.ui.help

/**
 * Application identity constants: the version and the project URLs, the single source of truth the
 * About dialog, the Help menu, and the desktop's launch log read.  This constant IS the application
 * version; the build derives its version stamps from it (gradle/project-version.gradle.kts parses the
 * VERSION line at configuration time for the Android versionName and the desktop packaging) — never
 * introduce a second definition.  A released version is a plain MAJOR.MINOR.PATCH; between releases
 * master carries the next one with a `-dev` suffix (RELEASING.md).
 */
object ProjectInfo {
	const val VERSION = "0.4.0-dev"
	const val WEB_SITE_URL = "https://umamo.org"
	const val SOURCE_CODE_URL = "https://github.com/umamoorg/umamo"
	const val DOCUMENTATION_URL = "https://docs.umamo.org/"
}