// Root build script. Plugins are declared here with `apply false` purely to pin their
// versions for the whole build; each module opts in by `alias(...)`-ing the ones it needs.
// This avoids every module repeating version numbers (the version catalog holds those).
// ルートビルドスクリプト。プラグインは `apply false` でバージョン固定のみを行い、各モジュールが
// 必要なものを `alias(...)` で取り込む。

plugins {
	alias(libs.plugins.kotlinMultiplatform) apply false
	alias(libs.plugins.composeCompiler) apply false
	alias(libs.plugins.composeMultiplatform) apply false
	alias(libs.plugins.androidApplication) apply false
	alias(libs.plugins.androidKmpLibrary) apply false
	alias(libs.plugins.ktlint)
}

// Apply ktlint to every subproject so tab-based style (see .editorconfig) is enforced
// uniformly. ktlint reads .editorconfig, so no rule config is needed here.
subprojects {
	apply(plugin = "org.jlleitschuh.gradle.ktlint")
	// Compose Multiplatform generates Kotlin under build/generated (JetBrains space-indent style); lint
	// our own sources, not generated output (e.g. the resources `Res` collectors).
	configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
		filter {
			exclude { element -> element.file.path.contains("/generated/") }
		}
	}
}
