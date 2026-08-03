// :render — deformation eval (CPU) + the backend-neutral PuppetRenderer + morph-blend shaders, over a
// RenderDevice backend seam. Everything except the device impls is commonMain (jvm/android/iosArm64);
// GlRenderDevice (LWJGL, desktop GL 3.3) lives in jvmMain, the GLES/Metal devices are stubs in
// androidMain/iosMain. Zero expect/actual. Depends on :runtime for the puppet model.
// :render — 変形評価（CPU）＋バックエンド非依存の PuppetRenderer。RenderDevice が継ぎ目で、GL 実装のみ
// jvmMain。GLES / Metal デバイスはスタブ。:runtime に依存。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// Umamo's own conventions (gradle/build-logic): the shared jvmAndroidMain source-set group, the
	// iosArm64 → `check` wiring, and the corpus/-D forwarding configured by `umamoTestCorpus` below.
	id("umamo.kmp-jvmandroid")
	id("umamo.kmp-ios-gate")
	id("umamo.test-corpus")
}

// EN: LWJGL ships its native libraries as classifier'd artifacts; pick the one for the build target.
//     Default is the host; -Pumamo.target=<os>-<arch> cross-resolves (e.g. windows-x64 from WSL2). The
//     selection is shared with :desktop so a cross-build can't bundle this module's host natives by
//     mistake — see gradle/build-target.gradle.kts. (Desktop only — Android uses GLES via android.opengl.)
// JA: LWJGL のネイティブ分類子を選ぶ。既定はホスト、-Pumamo.target で他プラットフォーム向けに解決。
apply(from = rootProject.file("gradle/build-target.gradle.kts"))
val lwjglNatives = extra["umamoLwjglNatives"] as String

kotlin {
	jvmToolchain(21)

	// (The -Xexpect-actual-classes opt-in that used to sit here left with its only user, the
	// `expect class GpuRenderer` — this module is zero expect/actual now; the backend seam is the
	// RenderDevice interface instead.)

	// The `jvmAndroidMain` group comes from the `umamo.kmp-jvmandroid` convention plugin. This module
	// needs it because the CMO3 atlas extraction takes a Cmo3Model, which lives in :format's own
	// jvmAndroidMain (the JDOM-backed codec) — so it can't sit in commonMain.

	jvm()

	// The iPadOS ship target, mirroring :format/:runtime (see :format's docblock for the rationale).
	// This is what the Metal engineer builds against: iosMain sees the whole backend-neutral stack —
	// PuppetRenderer, the RenderDevice API, eval, pose/diff/glue planning — and supplies one
	// MetalRenderDevice. It also turns commonMain purity for all of that from the root regex gate's
	// convention into a compiler guarantee. Compiles on Linux/CI (klib only, no Xcode linker); a device
	// target has no runnable test task, so `umamo.kmp-ios-gate` wires `check` to the compiles.
	iosArm64()

	android {
		namespace = "org.umamo.render"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// `api` (not `implementation`): the deformation eval's public surface returns/accepts
				// :runtime model types (PuppetModel, DrawableId, …), so consumers see them transitively.
				api(project(":runtime"))
				// `api`: :format types sit in the renderer's own public surface too — RasterImage in
				// RenderDevice's upload API, PngCodec behind the MOC3 texture decode — and :runtime is
				// format-free, so nothing supplies :format transitively. The jvmAndroidMain CMO3 atlas
				// extraction (extractPuppetTextures, takes a Cmo3Model) rides on this same edge.
				api(project(":format"))
			}
		}
		jvmMain {
			dependencies {
				// BOM aligns every LWJGL module to one version; the `::classifier` (empty
				// version) coordinate inherits that version while adding the host natives.
				implementation(project.dependencies.platform(libs.lwjgl.bom))
				implementation(libs.lwjgl.core)
				implementation(libs.lwjgl.opengl)
				runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
		jvmTest {
			dependencies {
				// The oracle/parity tests ingest corpus models through :interop's Cmo3Import/Moc3Import.
				implementation(project(":interop"))
				// Headless GL (GLFW hidden window) for the GPU-vs-CPU transform-feedback validation test.
				// `implementation` here (not inherited from jvmMain's `implementation`) so the test sources
				// compile against the GL/GLFW bindings; natives are pulled for the host.
				implementation(project.dependencies.platform(libs.lwjgl.bom))
				implementation(libs.lwjgl.core)
				implementation(libs.lwjgl.opengl)
				implementation(libs.lwjgl.glfw)
				runtimeOnly("org.lwjgl:lwjgl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-opengl::$lwjglNatives")
				runtimeOnly("org.lwjgl:lwjgl-glfw::$lwjglNatives")
			}
		}
	}
}

// Corpus + differential-oracle paths for the eval's gated tests (the `umamo.test-corpus` convention
// plugin): `./gradlew :render:jvmTest -Dcmo3.sample=… -Dmoc3.sample=… -Drelive.dumpModel=…
// -Drelive.coreLib=…`.
//
// `cmo3.sample`, `cmo3.probe`, and `moc3.samples` carry corpus DEFAULTS (see
// org.umamo.buildlogic.sharedCorpusDefault).  Without them this module's corpus gates — the GPU-vs-CPU
// deform oracle above all — only ran when someone remembered the flag, so in practice they never ran:
// GpuDeformValidationTest, GlueCorpusTest, and RenderOrderCorpusTest all sat skipping on a machine that
// had the corpus the whole time.  The deform oracle is the only pin on DEFORM_GLSL's math and the thing
// a Metal port will check itself against, so "runs only if asked" was the wrong default for it.  The
// relive.* oracle paths and moc3.sample stay explicit-only.
//
// Absent entirely (a fresh clone, CI) → the tests skip and the build stays green, since test/corpus is
// gitignored on purpose (see README).  That is deliberate; CI passes no sample flags.
//
// `umamo.requireGl` is forwarded for the opposite reason: it turns the GL tests' missing-context skip
// into a hard failure (see HeadlessGlGate).  CI sets it so the GL suite can never silently stop covering
// anything; a developer machine leaves it unset and gets the skip.  It is a flag, not a path, so it goes
// through `flag` and skips the file-existence check.
umamoTestCorpus {
	sample("cmo3.sample", "cmo3.probe", "moc3.sample", "moc3.samples", "relive.dumpModel", "relive.coreLib")
	flag("umamo.requireGl")
}
