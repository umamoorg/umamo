package org.umamo.ui.help

/**
 * Application identity constants: the version and the project URLs, the single source of truth the
 * About dialog and the Help menu read.  This constant IS the application version; the build derives
 * its version stamps from it (gradle/project-version.gradle.kts parses the VERSION line at
 * configuration time for the Android versionName and the desktop packaging) — never introduce a
 * second definition.
 */
internal object ProjectInfo {
	const val VERSION = "0.1.0-dev"
	const val WEB_SITE_URL = "https://umamo.org"
	const val SOURCE_CODE_URL = "https://github.com/umamoorg/umamo"
	const val DOCUMENTATION_URL = "https://docs.umamo.org/"
}
