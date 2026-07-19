// :render — deformation eval (CPU) + the backend-neutral PuppetRenderer + morph-blend shaders, over a
// RenderDevice backend seam. Everything except the device impls is commonMain (jvm/android/iosArm64);
// GlRenderDevice (LWJGL, desktop GL 3.3) lives in jvmMain, the GLES/Metal devices are stubs in
// androidMain/iosMain. Zero expect/actual. Depends on :runtime for the puppet model.
// :render — 変形評価（CPU）＋バックエンド非依存の PuppetRenderer。RenderDevice が継ぎ目で、GL 実装のみ
// jvmMain。GLES / Metal デバイスはスタブ。:runtime に依存。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
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

	// [kmp-jvmandroid] Keep identical across module/format, module/ui, module/render build scripts.
	// Customise the default source-set hierarchy to add a `jvmAndroidMain` group shared by the two
	// JVM-based targets (desktop JVM + Android/ART). The CMO3 atlas extraction takes a Cmo3Model,
	// which lives in :format's jvmAndroidMain (the JDOM-backed codec) — so it can't sit in
	// commonMain. Promote this block to a convention plugin at the 4th user.
	applyDefaultHierarchyTemplate {
		common {
			group("jvmAndroid") {
				withJvm()
				withAndroidTarget()
			}
		}
	}

	jvm()

	// The iPadOS ship target, mirroring :format/:runtime (see :format's docblock for the rationale).
	// This is what the Metal engineer builds against: iosMain sees the whole backend-neutral stack —
	// PuppetRenderer, the RenderDevice API, eval, pose/diff/glue planning — and supplies one
	// MetalRenderDevice. It also turns commonMain purity for all of that from the root regex gate's
	// convention into a compiler guarantee. Compiles on Linux/CI (klib only, no Xcode linker); `check`
	// is wired to the compiles explicitly below because a device target has no runnable test task.
	iosArm64()

	android {
		namespace = "org.umamo.render"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		// [kmp-jvmandroid] The hierarchy group's withAndroidTarget() matches the LEGACY android
		// target, not AGP 9's new KMP android-library target — so androidMain never inherits the
		// jvmAndroid group and can't see its declarations. Wire the edge explicitly. (withJvm()
		// does match, which is why the JVM target compiles fine.)
		val jvmAndroidMain = getByName("jvmAndroidMain")
		val androidMain = getByName("androidMain")
		androidMain.dependsOn(jvmAndroidMain)

		commonMain {
			dependencies {
				// `api` (not `implementation`): the deformation eval's public surface returns/accepts
				// :runtime model types (PuppetModel, DrawableId, …), so consumers see them transitively.
				// :format rides along through :runtime's own api(:format).
				api(project(":runtime"))
			}
		}
		// Shared by desktop JVM + Android: the CMO3 atlas extraction (extractPuppetTextures), which
		// takes a Cmo3Model from :format's jvmAndroidMain. Declared directly per project convention
		// even though :format also surfaces transitively via :runtime's api.
		jvmAndroidMain.dependencies {
			implementation(project(":format"))
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

// Wire the iosArm64 compile into `check`, main AND test — neither arrives on its own, because a device
// target has no runnable test task (see :format's wiring comment for the war story: main compiled green
// while commonTest was broken, and only CI's explicit compileTestKotlinIosArm64 caught it).
tasks.named("check") {
	dependsOn("compileKotlinIosArm64", "compileTestKotlinIosArm64")
}

// Corpus + differential-oracle paths for the eval's gated tests:
// `./gradlew :render:jvmTest -Dcmo3.sample=… -Dmoc3.sample=… -Drelive.dumpModel=… -Drelive.coreLib=…`.
//
// `cmo3.sample` additionally DEFAULTS to the local golden corpus, mirroring what :format already does
// (module/format/build.gradle.kts § corpusDefaultFor — read its comment, it explains the reasoning in
// full).  Without that default this module's corpus gates — the GPU-vs-CPU deform oracle above all —
// only ran when someone remembered the flag, so in practice they never ran: GpuDeformValidationTest,
// GlueCorpusTest, and RenderOrderCorpusTest all sat skipping on a machine that had the corpus the whole
// time.  The deform oracle is the only pin on DEFORM_GLSL's math and the thing a Metal port will check
// itself against, so "runs only if asked" was the wrong default for it.
//
// Absent entirely (a fresh clone, CI) → the tests skip and the build stays green, since test/corpus is
// gitignored on purpose (see README).  That is deliberate; CI passes no sample flags.
//
// `umamo.requireGl` is forwarded for the opposite reason: it turns the GL tests' missing-context skip
// into a hard failure (see HeadlessGlGate).  CI sets it so the GL suite can never silently stop covering
// anything; a developer machine leaves it unset and gets the skip.

/** The local golden corpus root. Gitignored, so absent on CI and on a fresh clone. */
val corpusDirectory: File = rootDir.resolve("test/corpus")

/**
 * Resolves a sample path property, preferring an explicit `-D` over the corpus default.
 *
 * A relative `-D` resolves against the REPO ROOT, not the test JVM's working directory (which is this
 * module).  That distinction bites: a gated test that cannot find its sample skips rather than fails, so
 * a path that silently resolves to nothing disables the gate without a word.  An explicit value that
 * does not exist therefore fails the build outright instead.
 *
 * @param String samplePropertyName The system property name.
 * @return String? The absolute path to hand the test JVM, or null to leave the test skipping.
 */
fun resolveSampleProperty(samplePropertyName: String): String? {
	val explicit =
		System.getProperty(samplePropertyName)
			?: return when (samplePropertyName) {
				// Pinned to a fixed corpus model: it is the model that actually carries glue affecters,
				// so it is what makes the glue gates meaningful rather than vacuously green.
				"cmo3.sample" -> corpusDirectory.resolve("cmo3/EricaTamamo.cmo3").takeIf { it.isFile }?.absolutePath
				// The blend-shape probes and posed-oracle gates resolve cmo3/moc3 PAIRS by name.
				"cmo3.probe" ->
					corpusDirectory
						.resolve("cmo3")
						.listFiles { candidate -> candidate.isFile && candidate.extension == "cmo3" }
						?.sortedBy { it.name }
						?.joinToString(",") { it.absolutePath }
						?.takeIf { it.isNotEmpty() }
				"moc3.samples" -> corpusDirectory.resolve("moc3").takeIf { it.isDirectory }?.absolutePath
				else -> null
			}
	// Some properties (cmo3.probe) carry a comma-separated list; resolve each element.
	val resolved =
		explicit.split(',').filter { it.isNotBlank() }.joinToString(",") { path ->
			rootDir.resolve(path.trim()).absolutePath
		}
	// Fail loudly rather than let the gated test skip-and-pass on a typo.
	for (path in resolved.split(',')) {
		require(File(path).exists()) {
			"-D$samplePropertyName=$explicit resolves to '$path', which does not exist. " +
				"Relative values resolve against the repo root ($rootDir)."
		}
	}
	return resolved
}

tasks.withType<Test>().configureEach {
	for (property in listOf("cmo3.sample", "cmo3.probe", "moc3.sample", "moc3.samples", "relive.dumpModel", "relive.coreLib")) {
		resolveSampleProperty(property)?.let { value ->
			systemProperty(property, value)
		}
	}
	// A plain flag, not a path: forwarded verbatim.
	System.getProperty("umamo.requireGl")?.let { systemProperty("umamo.requireGl", it) }
}
