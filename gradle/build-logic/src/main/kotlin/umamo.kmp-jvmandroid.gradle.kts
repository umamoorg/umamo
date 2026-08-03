// Convention plugin: the `jvmAndroidMain` source-set group, shared by the two JVM-based targets
// (desktop JVM + Android/ART).
//
// Some code is JVM-API-bound but must reach BOTH those targets — the JDOM + Kotlin-reflection CMO3
// serializer in :format above all, and everything layered on it (:interop's export lowering,
// :render's CMO3 atlas extraction, :ui's document/file layer and app shell). commonMain cannot hold
// it and duplicating it into jvmMain + androidMain would be two copies of the same file, so it lives
// in src/jvmAndroidMain and is shared verbatim.
//
// This block was copy-pasted across four build scripts and marked "[kmp-jvmandroid] keep identical" —
// which is the shape of a convention plugin written out longhand. Applied as
// `id("umamo.kmp-jvmandroid")`, after the module applies the Kotlin Multiplatform plugin.

// The hierarchy-template DSL (`applyDefaultHierarchyTemplate {}` with a describe block) is still
// marked experimental in KGP. The module build scripts opted in implicitly by living in a Gradle
// script; say it out loud here instead of emitting five warnings per compile.
@file:OptIn(org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi::class)

import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

// `withId` rather than a bare `configure`, so the order of the module's `plugins {}` block does not
// matter: whether Kotlin Multiplatform is applied before or after this plugin, the hierarchy is set up
// the moment it arrives.
plugins.withId("org.jetbrains.kotlin.multiplatform") {
	extensions.configure<KotlinMultiplatformExtension> {
		// Using a template group (rather than raw `dependsOn`) keeps the auto-wired commonMain edges
		// intact and avoids the "default hierarchy not applied" warning.
		applyDefaultHierarchyTemplate {
			common {
				group("jvmAndroid") {
					withJvm()
					withAndroidTarget()
				}
			}
		}

		// The group's withAndroidTarget() above matches the LEGACY android target, not AGP 9's new KMP
		// android-library target (com.android.kotlin.multiplatform.library, which is what every module
		// here applies) — so androidMain never inherits the jvmAndroid group and cannot see its
		// declarations or its actuals. Wire the edge explicitly. (withJvm() does match, which is why the
		// JVM target compiles fine and the breakage shows up as an Android-only failure.)
		//
		// Keyed on whichever of the two source sets is configured LAST, because their creation order is
		// not ours to pick: androidMain arrives with the android target, jvmAndroidMain when the
		// hierarchy template is applied over the registered targets. Checking both directions means the
		// edge lands either way, and `dependsOn` is set-valued so arriving twice is harmless.
		val kotlinSourceSets = sourceSets
		kotlinSourceSets.configureEach {
			val configuredSourceSet = this
			when (configuredSourceSet.name) {
				"jvmAndroidMain" ->
					kotlinSourceSets.findByName("androidMain")?.dependsOn(configuredSourceSet)

				"androidMain" ->
					kotlinSourceSets.findByName("jvmAndroidMain")?.let { jvmAndroidMain ->
						configuredSourceSet.dependsOn(jvmAndroidMain)
					}
			}
		}
	}
}
