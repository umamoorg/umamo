// Settings script — the FIRST thing Gradle evaluates.  Declares where plugins and
// dependencies are fetched from, and which subprojects make up the build.

rootProject.name = "umamo"

// `pluginManagement` controls plugin resolution. google() is required for the
// Android Gradle Plugin; gradlePluginPortal() for community plugins (ktlint).
pluginManagement {
	// Umamo's own convention plugins (umamo.kmp-jvmandroid, umamo.kmp-ios-gate, umamo.test-corpus),
	// as an included build rather than buildSrc: a buildSrc change invalidates the whole build's
	// configuration cache, which is on for this project (see gradle.properties).
	includeBuild("gradle/build-logic")

	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

// Umamo's own ktlint rule set, included as a REGULAR build (not under pluginManagement) because it is a
// jar the ktlint engine loads, not a plugin: only a regular inclusion substitutes the
// `org.umamo:ktlint-rules` coordinate every module's ktlintRuleset names (see the root build.gradle.kts).
includeBuild("gradle/ktlint-rules")

// `dependencyResolutionManagement` centralises repositories for ALL modules, so no
// module declares its own — keeps the dependency supply chain auditable in one place.
dependencyResolutionManagement {
	repositories {
		google()
		mavenCentral()
	}
}

// Library modules live under module/, applications under app/. A module's Gradle PATH (e.g.
// ":format") is kept flat and deliberately independent of its DIRECTORY, so dependency
// declarations stay terse — project(":format"), not project(":module:format") — while the
// folder tree stays tidy.  Path ≠ directory ≠ package: three separate things.
// (settings.gradle.kts is just Kotlin, so a local helper removes the repetition.)

/**
 * Includes a module under a flat Gradle path, with its project directory set apart from that path.
 *
 * @param String path The Gradle path, such as `:format`.
 * @param String dir The module's directory, relative to the repo root.
 */
fun includeAt(path: String, dir: String) {
	include(path)
	project(path).projectDir = file(dir)
}

includeAt(":format", "module/format")
includeAt(":geometry", "module/geometry")
includeAt(":reimport", "module/reimport")
includeAt(":storage", "module/storage")
includeAt(":settings", "module/settings")
includeAt(":render", "module/render")
includeAt(":runtime", "module/runtime")
includeAt(":interop", "module/interop")
includeAt(":edit", "module/edit")
includeAt(":ui", "module/ui")
includeAt(":desktop", "app/desktop")
includeAt(":android", "app/android")
includeAt(":cli", "app/cli")