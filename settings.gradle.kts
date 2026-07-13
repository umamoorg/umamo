// Settings script — the FIRST thing Gradle evaluates. Declares where plugins and
// dependencies are fetched from, and which subprojects make up the build.
// 設定スクリプト：Gradle が最初に評価する。プラグイン／依存の取得元と、ビルドを構成する
// サブプロジェクトを宣言する。

rootProject.name = "umamo"

// `pluginManagement` controls plugin resolution. google() is required for the
// Android Gradle Plugin; gradlePluginPortal() for community plugins (ktlint).
pluginManagement {
	repositories {
		google()
		mavenCentral()
		gradlePluginPortal()
	}
}

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
// folder tree stays tidy. Path ≠ directory ≠ package: three separate things.
// (settings.gradle.kts is just Kotlin, so a local helper removes the repetition.)
fun includeAt(path: String, dir: String) {
	include(path)
	project(path).projectDir = file(dir)
}

includeAt(":format", "module/format")
includeAt(":reimport", "module/reimport")
includeAt(":storage", "module/storage")
includeAt(":settings", "module/settings")
includeAt(":render", "module/render")
includeAt(":runtime", "module/runtime")
includeAt(":edit", "module/edit")
includeAt(":ui", "module/ui")
includeAt(":desktop", "app/desktop")
includeAt(":android", "app/android")
