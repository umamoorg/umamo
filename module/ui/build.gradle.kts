// :ui — Compose Multiplatform editor shell (panels, tree, timeline, parameter grid).
// Hosts the :render viewport, injected by each app as a composable slot (see ViewportHost).
// :ui — Compose Multiplatform 製エディタ UI。ビューポートは各アプリがスロットで注入する。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.composeMultiplatform)
	alias(libs.plugins.composeCompiler)
	// kotlinx.serialization codegen: the workspace/area-tree layout is `@Serializable` and persisted
	// to the `interface.layout` settings key. The plugin generates the serializers at compile time.
	alias(libs.plugins.kotlinSerialization)
}

// Resolve the host "<os>-<arch>" token (see gradle/build-target.gradle.kts) — jvmTest needs the
// matching Compose Desktop artifact for Skiko's native runtime (rasterizing tests load it).
apply(from = rootProject.file("gradle/build-target.gradle.kts"))
val buildTarget = extra["umamoBuildTarget"] as String

kotlin {
	jvmToolchain(21)

	// [kmp-jvmandroid] Keep identical across module/format, module/ui, module/render build scripts.
	// Customise the default source-set hierarchy to add a `jvmAndroidMain` group shared by the two
	// JVM-based targets (desktop JVM + Android/ART). commonMain cannot see :format's jvmAndroidMain
	// types (FormatRegistry, Cmo3Model — the JDOM-backed CMO3 codec), but the shared document/file
	// layer and app shell need them — so that code lives in src/jvmAndroidMain and is shared verbatim
	// by both targets. Promote this block to a convention plugin at the 4th user.
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
		namespace = "org.umamo.ui"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		// [kmp-jvmandroid] The hierarchy group's withAndroidTarget() matches the LEGACY android
		// target, not AGP 9's new KMP android-library target — so androidMain never inherits the
		// jvmAndroid group and can't see its actuals. Wire the edge explicitly. (withJvm() does
		// match, which is why the JVM target compiles fine.)
		val jvmAndroidMain = getByName("jvmAndroidMain")
		val androidMain = getByName("androidMain")
		androidMain.dependsOn(jvmAndroidMain)

		commonMain {
			dependencies {
				// Direct Compose Multiplatform coordinates via the version catalog — the old
				// `compose.runtime` plugin aliases were deprecated in CMP 1.10/1.11. The artifacts
				// still redirect to androidx.compose on Android (Gradle metadata), so per-target
				// resolution is unchanged; see the catalog note on the `compose-*` entries.
				implementation(libs.compose.runtime)
				implementation(libs.compose.foundation)
				implementation(libs.compose.ui)
				// Compose Multiplatform resources — bundles defaultSettings.json so desktop + Android
				// share one baseline; read via the generated `Res` (see SettingsProvider).
				implementation(libs.compose.components.resources)
				// Layout serialization (the area-tree → `interface.layout` JSON) and coroutines for the
				// debounced `snapshotFlow` persist. Declared directly rather than leaned on transitively
				// from :settings — coroutines is `implementation` there, so it would not leak through.
				implementation(libs.kotlinxSerializationJson)
				implementation(libs.kotlinxCoroutinesCore)
				implementation(project(":runtime"))
				// `api` (not `implementation`): the viewport seam's public surface exposes :render types
				// (PuppetViewportService returns ViewportCamera / PickCandidate / CheckerboardColors), so
				// consumers see them transitively.
				api(project(":render"))
				// Editing core: the Selection / EditorMode model and the EditorSession the panels read and
				// drive (LocalEditorSession, the session-backed handles); declared directly, the project's
				// convention even though :edit also surfaces transitively.
				implementation(project(":edit"))
				implementation(project(":settings"))
				implementation(project(":storage"))
			}
		}
		// Shared by desktop JVM + Android: the document/file layer and the shared EditorApp shell.
		// They call :format's FormatRegistry / Cmo3 codec, which live in :format's jvmAndroidMain
		// (the JDOM XML serializer is JVM-only API) — so this layer can't sit in commonMain.
		jvmAndroidMain.dependencies {
			implementation(project(":format"))
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
				// In-memory FileSystem + storage so the layout persistence round-trip runs with no real disk.
				implementation(libs.okio)
				implementation(libs.okio.fakefilesystem)
			}
		}
		jvmTest {
			dependencies {
				// Skiko's native runtime for the host, so commonTest code that rasterizes (the thumbnailer's
				// rgbaToImageBitmap → Image.makeRaster) can run under :ui:jvmTest — the plain compose-ui
				// coordinate carries no natives. Same direct-coordinate rationale as app/desktop.
				runtimeOnly("org.jetbrains.compose.desktop:desktop-jvm-$buildTarget:${libs.versions.composeMultiplatform.get()}")
			}
		}
	}
}

// Bundle the repo-root CREDITS.md as a compose resource at build time, so the Help → Credits dialog
// ships the one authored file on desktop and Android alike — a single source of truth, no checked-in
// copy to drift. customDirectory REPLACES the source set's default resource directory rather than
// adding one, so the replacement is a Sync-merged root: the checked-in composeResources plus
// CREDITS.md under files/, readable as Res.readBytes("files/CREDITS.md").
val syncBundledCredits =
	tasks.register<Sync>("syncBundledCredits") {
		from(layout.projectDirectory.dir("src/commonMain/composeResources"))
		from(rootProject.file("CREDITS.md")) {
			into("files")
		}
		into(layout.buildDirectory.dir("generated/bundledCredits"))
	}

// Generated resource accessor package. Public so the desktop/Android app modules can localize their
// own chrome (e.g. the desktop menu bar) against the same EN/JA string catalogs via stringResource —
// the shared UI strings live here, not duplicated per app.
compose.resources {
	packageOfResClass = "org.umamo.ui.resources"
	publicResClass = true
	// The map over the task output carries the task dependency into the provider, so the resource
	// pipeline orders after the Sync (and the configuration cache stays happy).
	customDirectory(
		sourceSetName = "commonMain",
		directoryProvider = layout.dir(syncBundledCredits.map { syncTask -> syncTask.destinationDir }),
	)
}

// Forward the MOC3 corpus sample to the test JVM so the sidecar-loader tests can exercise the
// moc-dependent paths (a decodable .moc3 is needed before texture resolution runs).  Explicit -D
// wins; otherwise the local (gitignored) corpus is the default, mirroring :runtime and :render.
// Absent entirely (CI, a fresh clone) → those tests self-skip and the build stays green.
tasks.withType<Test>().configureEach {
	// The corpus loader test decodes the 8192² atlas (256MB of RGBA) more than once; the JVM default
	// heap cannot hold that. Same figure :format's tests use.
	maxHeapSize = "4g"
	val explicitSample = System.getProperty("moc3.sample")
	val resolvedSample =
		if (explicitSample != null) {
			// An explicit -D resolves against the repo root and must exist: a gated test that cannot
			// find its sample skips rather than fails, so a typo would silently disable the gate while
			// reporting PASSED (mirrors :render's resolveSampleProperty).
			val explicitFile = rootDir.resolve(explicitSample.trim())
			require(explicitFile.exists()) {
				"-Dmoc3.sample=$explicitSample resolves to '${explicitFile.absolutePath}', which does not exist. " +
					"Relative values resolve against the repo root ($rootDir)."
			}
			explicitFile.absolutePath
		} else {
			rootProject.layout.projectDirectory
				.file("test/corpus/moc3/EricaTamamo/EricaTamamo.moc3")
				.asFile
				.takeIf { it.isFile }
				?.absolutePath
		}
	resolvedSample?.let { value ->
		systemProperty("moc3.sample", value)
	}
}
