// Settings for the ktlint-rules INCLUDED BUILD — Umamo's own ktlint rule set.
//
// The root settings includes it as a regular build (`includeBuild("gradle/ktlint-rules")`), not under
// pluginManagement: the rule set is a jar the ktlint engine loads, not a Gradle plugin, and only a
// regular inclusion substitutes `org.umamo:ktlint-rules` with this build's output.  Every module's
// `ktlintRuleset` dependency resolves through that substitution, so editing a rule rebuilds it before
// the next ktlintCheck / ktlintFormat.
//
// Like build-logic, it inherits nothing from the root, so the repositories and the version catalog are
// re-declared here, the catalog by relative path to the same file the modules use.

rootProject.name = "ktlint-rules"

dependencyResolutionManagement {
	repositories {
		mavenCentral()
	}

	versionCatalogs {
		create("libs") {
			from(files("../libs.versions.toml"))
		}
	}
}

pluginManagement {
	repositories {
		mavenCentral()
		gradlePluginPortal()
	}
}