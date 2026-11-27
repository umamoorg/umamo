// Build script for Umamo's ktlint rule set.
//
// The jar runs INSIDE the ktlint engine's worker, on the engine's own classpath: the engine supplies the
// rule API and the Kotlin standard library at runtime, so both are compile-only here and the jar carries
// nothing but the rules (gradle.properties turns off the Kotlin plugin's implicit stdlib dependency).
// The API level is held to the engine's Kotlin, so a newer compiler can never emit a call into a stdlib
// function the engine's older stdlib does not have.

import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion

plugins {
	alias(libs.plugins.kotlinJvm)
}

// The coordinate the root build's `ktlintRuleset("org.umamo:ktlint-rules")` names; the included-build
// substitution matches on group and project name.
group = "org.umamo"

kotlin {
	jvmToolchain(21)

	compilerOptions {
		// ktlint 1.8.0 embeds Kotlin 2.2.
		apiVersion.set(KotlinVersion.KOTLIN_2_2)
		languageVersion.set(KotlinVersion.KOTLIN_2_2)
	}
}

dependencies {
	compileOnly(kotlin("stdlib"))
	compileOnly(libs.ktlintRuleEngineCore)
	compileOnly(libs.ktlintCliRulesetCore)

	testImplementation(kotlin("test"))
	testImplementation(libs.ktlintRuleEngine)
	testImplementation(libs.ktlintCliRulesetCore)
	testImplementation(libs.ktlintRulesetStandard)
	testRuntimeOnly(libs.slf4jNop)
}

tasks.test {
	useJUnitPlatform()

	// IncludedBuildSourcesLintTest reads these files as text.  The test classpath carries the sources only as
	// classes, and a comment compiles to the same classes whatever it says, so without this an edit the
	// lint would report could leave the task up to date.
	inputs
		.files(fileTree("src"), fileTree("../build-logic") { include("src/**", "*.gradle.kts") }, "build.gradle.kts", "settings.gradle.kts", "../../.editorconfig")
		.withPropertyName("lintedSources")
		.withPathSensitivity(PathSensitivity.RELATIVE)

	testLogging {
		events("passed", "skipped", "failed")
		exceptionFormat = TestExceptionFormat.FULL
	}
}