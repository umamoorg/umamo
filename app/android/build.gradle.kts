// :android (app/android) — Android entrypoint (Activity + Compose + GLSurfaceView viewport).
// A plain Android application module (not KMP): it consumes the shared KMP libraries.
// :android — Android 起動点（Activity ＋ Compose ＋ GLSurfaceView ビューポート）。

plugins {
	// AGP 9+ has built-in Kotlin support, so the org.jetbrains.kotlin.android plugin is gone —
	// applying it now errors. AGP compiles Kotlin itself; the Compose compiler plugin hooks in.
	alias(libs.plugins.androidApplication)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// Resolve the application version from ProjectInfo.kt (see gradle/project-version.gradle.kts) so
// versionName never drifts from what the About dialog shows.
apply(from = rootProject.file("gradle/project-version.gradle.kts"))
val umamoVersion = extra["umamoVersion"] as String

android {
	namespace = "org.umamo.editor.android"
	compileSdk = libs.versions.android.compileSdk.get().toInt()

	defaultConfig {
		// applicationId is the permanent Play identity; it intentionally differs from `namespace`
		// so org.umamo stays a clean umbrella for a future viewer app.
		applicationId = "org.umamo.editor"
		minSdk = libs.versions.android.minSdk.get().toInt()
		targetSdk = libs.versions.android.targetSdk.get().toInt()
		// versionCode is Play's monotonic release counter — bumped by hand on release; it cannot be
		// derived from a semver string.
		versionCode = 1
		versionName = umamoVersion
	}

	buildTypes {
		release {
			isMinifyEnabled = false
		}
	}

	// Keep AGP's Java compilation in lockstep with Kotlin's JDK 21 target (otherwise the
	// "inconsistent JVM target" error bites the moment any Java source appears).
	compileOptions {
		sourceCompatibility = JavaVersion.VERSION_21
		targetCompatibility = JavaVersion.VERSION_21
	}
}

kotlin {
	jvmToolchain(21)
}

dependencies {
	implementation(project(":ui"))
	implementation(project(":runtime"))
	// Editing core (EditorSession / Selection model): the Android host creates a per-document session
	// the same way the desktop does; declared directly for parity with :runtime / :render.
	implementation(project(":edit"))
	implementation(project(":render"))
	// androidAppStorage + the per-OS config/data dirs the settings live in; also pulls FileKit
	// (api-exposed by :storage), whose Android pickers MainActivity initialises with FileKit.init.
	implementation(project(":storage"))
	// The Settings type returned by LocalSettings: :ui depends on :settings only via implementation, so
	// MainActivity needs it on its own classpath to read settings.getString / observe settings.changes.
	implementation(project(":settings"))

	// setContent { } lives in androidx.activity.compose; the compose.* accessors (from the
	// Compose MP plugin) resolve to Jetpack Compose on this Android target.
	implementation(libs.androidx.activity.compose)
	implementation(compose.runtime)
	implementation(compose.foundation)
	implementation(compose.ui)
}
