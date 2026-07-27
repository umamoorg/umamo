// :desktop (app/desktop) — desktop entrypoint (Compose Desktop + LWJGL viewport interop).
// :desktop — デスクトップ起動点（Compose Desktop ＋ LWJGL ビューポート連携）。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
}

// EN: Resolve which platform's natives this build bundles (default: the host; override with
//     -Pumamo.target=<os>-<arch>, e.g. windows-x64 to cross-build from WSL2 Linux). The shared script
//     also drives :render so both modules agree; it exposes its result via extra properties. See
//     gradle/build-target.gradle.kts for the full rationale and the cross-build recipe.
// JA: 同梱するネイティブの対象プラットフォームを解決する（既定はホスト、-Pumamo.target で上書き）。
apply(from = rootProject.file("gradle/build-target.gradle.kts"))
val buildTarget = extra["umamoBuildTarget"] as String
val lwjglNatives = extra["umamoLwjglNatives"] as String

// Resolve the application version from ProjectInfo.kt (see gradle/project-version.gradle.kts) so
// the packaged artifacts never drift from what the About dialog shows.
apply(from = rootProject.file("gradle/project-version.gradle.kts"))
val umamoVersion = extra["umamoVersion"] as String
val umamoVersionNumeric = extra["umamoVersionNumeric"] as String

kotlin {
	jvmToolchain(21)

	jvm()

	sourceSets {
		jvmMain {
			dependencies {
				// EN: Compose Desktop's per-target artifact bundles the matching Skiko native (a
				//     .so/.dll/.dylib). buildTarget is exactly the artifact's "<os>-<arch>" suffix, so
				//     `desktop-jvm-$buildTarget` selects the right one — on the host this is what
				//     `compose.desktop.currentOs` resolves to, so the default path is unchanged. Direct
				//     coordinate (not the `compose.desktop.<target>` accessor) because those accessors are
				//     deprecated in CMP 1.11 — same migration the version catalog already made for the
				//     other compose.* artifacts (see libs.versions.toml).
				// JA: 対象プラットフォームの Skiko ネイティブを含む Compose Desktop 依存を直接座標で選ぶ。
				implementation("org.jetbrains.compose.desktop:desktop-jvm-$buildTarget:${libs.versions.composeMultiplatform.get()}")
				// Compose resources: the desktop menu bar localizes against :ui's EN/JA catalogs via
				// stringResource(Res.string.*); :ui exposes it as implementation, so declare it here too.
				implementation(libs.compose.components.resources)
				implementation(project(":ui"))
				implementation(project(":runtime"))
				// Editing core: the per-document EditorSession the desktop host creates and the
				// Selection / EditorMode model the viewport pick reads (used directly in jvmMain).
				implementation(project(":edit"))
				implementation(project(":render"))
				implementation(project(":settings"))
				implementation(project(":storage"))
				// Explicit (also transitive via :runtime) — Document loading calls FormatRegistry/Cmo3 directly.
				implementation(project(":format"))

				// JNA: a tiny Win32 FFI for desktop window chrome (the DWM title-bar caption tint).
				// Already on the runtime classpath via FileKit; declared here so jvmMain compiles
				// against com.sun.jna.* directly. No-op off Windows — the call is OS-guarded.
				implementation(libs.jna)

				// LWJGL GL for the offscreen viewport renderer; BOM keeps module versions aligned.
				implementation(project.dependencies.platform(libs.lwjgl.bom))
				implementation(libs.lwjgl.core)
				implementation(libs.lwjgl.opengl)
				// GLFW: a hidden-window GL context for the OFFSCREEN viewport renderer. The puppet is
				// rendered to an FBO and shown as a lightweight Compose Image (not a heavyweight AWT
				// canvas), so Compose menus/overlays/gizmos layer over it correctly on every platform.
				implementation(libs.lwjgl.glfw)
				runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
			}
		}
		jvmTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}

// Compose Desktop's packaging/run DSL. `:desktop:run` launches the editor.
compose.desktop {
	application {
		mainClass = "org.umamo.editor.desktop.MainKt"
		// Everything in this jvmArgs list is baked into the PACKAGED launcher too — the plugin
		// hands application.jvmArgs straight to jpackage as --java-options, which end up in
		// umamo.cfg. So only put things here that are true for a shipped build. The corpus-preview
		// override, a developer affordance pointing at a gitignored absolute host path, is set on
		// `:desktop:run` alone at the bottom of this file.
		//
		// Decoding the 8192² atlas to RGBA is transiently heavy (~0.5 GB); give the preview headroom.
		jvmArgs.add("-Xmx4g")
		nativeDistributions {
			// packageName feeds the uber-jar base name (and any future jpackage installers);
			// lowercase matches the project/domain and Linux package conventions.
			packageName = "umamo"
			// jpackage rejects prerelease suffixes, so installers get the numeric form; the uber jar
			// keeps the full ProjectInfo string (below).
			packageVersion = umamoVersionNumeric

			// Identity metadata. jpackage stamps vendor/description/copyright into the Windows exe
			// version resource and the macOS Info.plist, and copies licenseFile into the app image
			// — so even an unsigned build says who made it and under what terms.
			vendor = "Umamo Project"
			description = "Cross-platform 2D puppet modelling editor with Live2D Cubism .cmo3 interop."
			copyright = "Copyright (C) Umamo Project contributors.  Licensed under the GPL-3.0."
			licenseFile.set(rootProject.file("LICENSE"))

			// The jlink module set for the bundled runtime. Compose's default is
			// [java.base, java.desktop, java.logging, jdk.crypto.ec] and modules(...) APPENDS to
			// it rather than replacing it, so these are purely additive. Each is load-bearing,
			// and each was traced to the jar that needs it with `jdeps --list-deps`:
			//   java.instrument   — kotlinx-coroutines' AgentPremain debug-probe transformer.
			//   java.sql          — SQLDelight's JdbcSqliteDriver / sqlite-jdbc (CLIP ingest).
			//   java.xml          — JDOM's SAX/JAXP path, i.e. all of CMO3 read/write. Already
			//                       implied (java.desktop requires it transitively), but CMO3 is
			//                       the product; it should not ride on someone else's implication.
			//   jdk.security.auth — dbus-java, under FileKit's XDG-portal open/save dialogs.
			//   jdk.unsupported   — sun.misc.Unsafe, which LWJGL's MemoryBackendUnsafe* needs.
			// Deliberately NOT includeAllModules: that adds ~50 MB per platform to paper over a
			// class of bug the release workflow already catches by asserting on the MODULES= line
			// of jlink's `release` descriptor. Re-run `:desktop:suggestRuntimeModules` after any
			// dependency change and add whatever it names — but note it under-reports, since it
			// misses reflective and service-loaded edges (it does not name java.xml).
			modules("java.instrument", "java.sql", "java.xml", "jdk.security.auth", "jdk.unsupported")

			// App icon per OS.  jpackage demands a different container per platform and reads only
			// its own host's file, so all three are committed and each is consumed when packaging on
			// that OS.  These feed createDistributable / the native installers (not the uber jar,
			// which carries no icon).  Regenerate from the mascot with docs/design/appicon/generate.sh.
			windows {
				iconFile.set(project.file("icons/umamo.ico"))
			}
			macOS {
				iconFile.set(project.file("icons/umamo.icns"))
				// CFBundleIdentifier. Matches :android's applicationId so one reverse-DNS identity
				// covers the project on both platforms. Not required for an unsigned app image (the
				// plugin only validates it when signing), but jpackage would otherwise derive one
				// from the main-class package — and macOS keys preferences and TCC permission grants
				// on this string, so changing it later orphans every user's saved state.
				bundleID = "org.umamo.editor"
			}
			linux {
				iconFile.set(project.file("icons/umamo.png"))
			}
		}
	}
}

// `umamo.testCmo3` opens the corpus CMO3 (gitignored; the puppet preview) on launch. It is a
// DEVELOPER affordance carrying an absolute host path, so it must never reach a packaged
// artifact where that path does not exist — hence the run task rather than
// application.jvmArgs, which the plugin forwards to jpackage as --java-options.
//
// Two deliberate choices:
//   * withType/matching/configureEach, not tasks.named("run"): the Compose plugin registers its
//     tasks inside its own afterEvaluate, so `run` does not exist while this script is being
//     evaluated and named() would throw. Same lazy idiom as the uber-jar override below.
//   * systemProperty(), not jvmArgs(): the plugin's own configuration action calls
//     JavaExec.setJvmArgs() — a REPLACE, not an append. Gradle happens to run that action before
//     this one so an appended jvmArg would survive today, but systemProperties is a separate
//     collection setJvmArgs never touches, so this holds regardless of action ordering.
// The path is resolved to a val first so the lambda captures a String, not the Project
// (configuration cache).
val runPreviewCmo3Path = rootProject.file("test/corpus/cmo3/EricaTamamo.cmo3").absolutePath
tasks.withType<JavaExec>().matching { execTask -> execTask.name == "run" }
	.configureEach {
		systemProperty("umamo.testCmo3", runPreviewCmo3Path)
	}

// The Compose plugin stamps the uber jar with the HOST os token (its own currentTarget
// detection), which lies when -Pumamo.target cross-resolves the bundled natives — the jar
// CONTENTS already honor buildTarget (Skiko artifact and LWJGL natives above). The plugin
// assigns the name PARTS (appendix/version) directly in its afterEvaluate, after any
// configureEach action, so part-level overrides lose; an explicit archiveFileName replaces the
// derived-from-parts convention outright and wins regardless of assignment order.
//
// Version before target so every release asset reads umamo-<version>-<target>.<ext> and the jar
// sorts next to its app-image archive on the releases page.
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { jarTask -> jarTask.name == "packageUberJarForCurrentOS" }
	.configureEach {
		archiveFileName.set("umamo-$umamoVersion-$buildTarget.jar")
	}
