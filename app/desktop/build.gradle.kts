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
		// Open the corpus CMO3 (gitignored; the puppet preview) by default. Absolute path resolved
		// at configuration time so `:desktop:run` finds it regardless of cwd.
		jvmArgs.add("-Dumamo.testCmo3=${rootProject.file("test/corpus/EricaTamamo.cmo3").absolutePath}")
		// Decoding the 8192² atlas to RGBA is transiently heavy (~0.5 GB); give the preview headroom.
		jvmArgs.add("-Xmx4g")
		nativeDistributions {
			// packageName feeds the uber-jar base name (and any future jpackage installers);
			// lowercase matches the project/domain and Linux package conventions.
			packageName = "umamo"
			// jpackage rejects prerelease suffixes, so installers get the numeric form; the uber jar
			// keeps the full ProjectInfo string (below).
			packageVersion = umamoVersionNumeric
			// App icon per OS.  jpackage demands a different container per platform and reads only
			// its own host's file, so all three are committed and each is consumed when packaging on
			// that OS.  These feed createDistributable / the native installers (not the uber jar,
			// which carries no icon).  Regenerate from the mascot with docs/design/appicon/generate.sh.
			windows {
				iconFile.set(project.file("icons/umamo.ico"))
			}
			macOS {
				iconFile.set(project.file("icons/umamo.icns"))
			}
			linux {
				iconFile.set(project.file("icons/umamo.png"))
			}
		}
	}
}

// EN: The Compose plugin stamps the uber jar with the HOST os token (its own currentTarget
//     detection), which lies when -Pumamo.target cross-resolves the bundled natives — the jar
//     CONTENTS already honor buildTarget (Skiko artifact and LWJGL natives above). The plugin
//     assigns the name PARTS (appendix/version) directly in its afterEvaluate, after any
//     configureEach action, so part-level overrides lose; an explicit archiveFileName replaces the
//     derived-from-parts convention outright and wins regardless of assignment order.
// JA: Compose プラグインは uber jar 名にホスト OS を刻むため、解決済みターゲットと完全なバージョンで
//     ファイル名ごと上書きする（部分上書きはプラグインの後勝ち代入に負ける）。
tasks.withType<org.gradle.jvm.tasks.Jar>().matching { jarTask -> jarTask.name == "packageUberJarForCurrentOS" }
	.configureEach {
		archiveFileName.set("umamo-$buildTarget-$umamoVersion.jar")
	}
