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
	// Umamo's own conventions (gradle/build-logic): the shared jvmAndroidMain source-set group, and the
	// corpus/-D forwarding configured by `umamoTestCorpus` below. (No iOS target here yet, so no
	// `umamo.kmp-ios-gate` — Compose Multiplatform's iOS story is a separate piece of work.)
	id("umamo.kmp-jvmandroid")
	id("umamo.test-corpus")
}

// Resolve the host "<os>-<arch>" token (see gradle/build-target.gradle.kts) — jvmTest needs the
// matching Compose Desktop artifact for Skiko's native runtime (rasterizing tests load it).
apply(from = rootProject.file("gradle/build-target.gradle.kts"))
val buildTarget = extra["umamoBuildTarget"] as String

kotlin {
	jvmToolchain(21)

	// The `jvmAndroidMain` group comes from the `umamo.kmp-jvmandroid` convention plugin. This module
	// needs it because commonMain cannot see :format's own jvmAndroidMain types (FormatRegistry,
	// Cmo3Model — the JDOM-backed CMO3 codec), but the shared document/file layer and app shell do.

	jvm()

	android {
		namespace = "org.umamo.ui"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		// The `umamo.kmp-jvmandroid` convention plugin creates this group and wires androidMain onto it;
		// it is not a well-known source-set name, so it has no generated accessor and is looked up here.
		val jvmAndroidMain = getByName("jvmAndroidMain")

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
				// Format↔runtime conversion: the CMO3/MOC3 import + export entry points the document
				// layer calls, and the export report/notice types the shell overlays render.
				// `implementation` — ShellOverlayState is internal, so nothing re-exports them.
				implementation(project(":interop"))
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
				// A real composition with real pointer input. The panels' gesture code (tap-versus-drag off
				// one raw stream, selection round-tripping through hoisted state) is not reachable from a
				// pure-function test, and that is exactly where the interaction bugs have been living.
				implementation(libs.compose.ui.test)
				implementation(libs.compose.ui.test.junit4)
				// sealedSubclasses, for the export-notice label coverage test. Without it the reflection
				// call resolves to an empty list rather than failing, which would make that test vacuous.
				implementation(kotlin("reflect"))
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
// moc-dependent paths (a decodable .moc3 is needed before texture resolution runs).  Explicit -D wins;
// otherwise the local (gitignored) corpus is the default.  Absent entirely (CI, a fresh clone) → those
// tests self-skip and the build stays green.
umamoTestCorpus {
	// The corpus loader test decodes the 8192² atlas (256MB of RGBA) more than once; the JVM default
	// heap cannot hold that. Same figure :format's tests use.
	maxHeap("4g")
	// Model A: the corpus family that is COMPLETE on disk (manifest + textures + cdi3 + physics all
	// resolve). The EricaTamamo corpus copy carries no texture folder, so the family loader test would
	// fail on it rather than exercise the load path. Hence a module-specific default rather than the
	// shared table, which deliberately leaves moc3.sample explicit-only for :interop and :render.
	sampleWithCorpusDefault("moc3.sample", "moc3/modelA/modelA.moc3")
	// The CMO3 loader test's counterpart. Takes the shared table's default (a whole .cmo3 embeds its
	// own pixels, so unlike the moc family there is no sidecar folder that has to resolve).
	sample("cmo3.sample")
	// The converted-repack export gate's fixture: a MOC3-origin conversion of the modelG family
	// (test/corpus/moc3/modelG) saved as a .cmo3.  It lives OUTSIDE the golden glob because a
	// converted file fails the corpus invariants by nature, and it self-skips until the file exists.
	sampleWithCorpusDefault("cmo3.repackSample", "cmo3/invalid/modelG.cmo3")
	// The artwork-import gate's fixture: a real layered PSD, imported, packed at open, and exported.
	sampleWithCorpusDefault("psd.sample", "psd/EricaTamamo.psd")
}