// Build script for the convention plugins themselves.
//
// `kotlin-dsl` turns every *.gradle.kts under src/main/kotlin into a PRECOMPILED SCRIPT PLUGIN whose
// id is the file name minus the extension — umamo.kmp-jvmandroid.gradle.kts becomes the plugin id
// `umamo.kmp-jvmandroid`. Ordinary .kt files in the same source set compile alongside them, which is
// where the shared types (see org.umamo.buildlogic) live.

plugins {
	`kotlin-dsl`
}

// The same JDK 21 (LTS) toolchain every module pins. Without it these scripts compile against whatever
// JVM happens to be running the daemon, which is how the Java and Kotlin compile tasks end up
// disagreeing about their target level.
kotlin {
	jvmToolchain(21)
}

dependencies {
	// The KMP source-set hierarchy is configured through the Kotlin Gradle Plugin's own API
	// (KotlinMultiplatformExtension, the hierarchy-template DSL), so the convention plugins compile
	// against it. This is the LIBRARY coordinate, not the plugin marker — build-logic configures KGP,
	// it never applies it; the modules still apply `alias(libs.plugins.kotlinMultiplatform)`
	// themselves. Both read the same `kotlin` version from the catalog, so the API compiled against
	// and the plugin applied can never drift.
	implementation(libs.kotlinGradlePlugin)
}
