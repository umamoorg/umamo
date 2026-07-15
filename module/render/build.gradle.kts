// :render — deformation eval (CPU) + renderer + morph-blend shaders. expect/actual: LWJGL on
// desktop, GLES on Android. Backend-agnostic eval + Renderer interface in commonMain; GL impl in
// jvmMain/androidMain. Depends on :runtime for the puppet model.
// :render — 変形評価（CPU）＋レンダラ＋モーフブレンドシェーダ。expect/actual：デスクトップは LWJGL、
// Android は GLES。バックエンド非依存の評価と Renderer は commonMain、GL 実装は各プラットフォーム。:runtime に依存。

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

	// `expect`/`actual` *classes* are still flagged Beta by the compiler; this is the
	// JetBrains-recommended opt-in to silence the warning (the feature itself is stable in use).
	compilerOptions {
		freeCompilerArgs.add("-Xexpect-actual-classes")
	}

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

// Forward corpus + differential-oracle paths to the test JVM so the eval's gated tests can run:
// `./gradlew :render:jvmTest -Dcmo3.sample=… -Dmoc3.sample=… -Drelive.dumpModel=… -Drelive.coreLib=…`.
// Absent properties self-skip, so CI needs no committed corpus or external oracle.
tasks.withType<Test>().configureEach {
	for (property in listOf("cmo3.sample", "moc3.sample", "relive.dumpModel", "relive.coreLib")) {
		System.getProperty(property)?.let { value ->
			systemProperty(property, value)
		}
	}
}
