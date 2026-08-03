// Settings for the build-logic INCLUDED BUILD — the home of Umamo's convention plugins.
//
// This is a build of its own, not a subproject: the root settings pulls it in with
// `pluginManagement { includeBuild("gradle/build-logic") }`, so its plugins are resolvable by id
// from every module's `plugins {}` block. That is what makes `id("umamo.kmp-jvmandroid")` read like
// any other plugin rather than an `apply(from = …)` script with no type-safe accessors.
//
// Being a separate build, it inherits NOTHING from the root — not the repositories, not the version
// catalog. Both are re-declared below, and the catalog is the SAME FILE the modules use so a
// convention plugin can never compile against a different Kotlin than the one the modules apply.

rootProject.name = "build-logic"

dependencyResolutionManagement {
	repositories {
		mavenCentral()
		// The Kotlin Gradle Plugin publishes to both; the portal is what resolves plugin markers.
		gradlePluginPortal()
	}

	// The one catalog, reached by relative path (this file sits in gradle/build-logic, the catalog in
	// gradle/). Gradle's automatic `gradle/libs.versions.toml` discovery is relative to THIS build's
	// root, which would be gradle/build-logic/gradle/ — hence the explicit `from`.
	versionCatalogs {
		create("libs") {
			from(files("../libs.versions.toml"))
		}
	}
}
