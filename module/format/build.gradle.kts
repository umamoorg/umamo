// :format — Cubism file family + art I/O. KMP library shared by desktop (JVM) and Android.
// :format — Cubism ファイル群とアート入出力。デスクトップ(JVM)と Android が共有する KMP ライブラリ。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
	// Generates the typed CLIP database from module/format/src/commonMain/sqldelight/*.sq.
	alias(libs.plugins.sqldelight)
}

kotlin {
	// One toolchain for every target. Pinned to JDK 21 (LTS) — the build JVM and the
	// bytecode level. `jvmToolchain` replaces hand-set sourceCompatibility/targetCompatibility.
	jvmToolchain(21)

	// NOTE: `-Xexpect-actual-classes` used to live here, opting in to the Beta expect/actual CLASS
	// feature (KT-61573) purely because CaffZip was an expect/actual object. It no longer is: the ZIP
	// framing is plain commonMain code over the shared DEFLATE seam, and the module's only remaining
	// expect is `useClipDatabase`, a top-level fun — which needs no opt-in. Do not add the flag back
	// without a declaration that genuinely requires it.

	// [kmp-jvmandroid] Keep identical across module/format, module/ui, module/render build scripts.
	// Customise the default source-set hierarchy to add a `jvmAndroidMain` group shared by
	// the two JVM-based targets (desktop JVM + Android/ART). CMO3 read/write is a JDOM +
	// Kotlin-reflection XML serializer (NOT Java object serialization — see docs/formats/CMO3.md)
	// and those are JVM-only APIs — so that code lives in src/jvmAndroidMain and is shared verbatim
	// by both, honouring CLAUDE.md's intent.
	// NOTE: commonMain purity is a COMPILER GUARANTEE in this module, not a convention — the iosArm64
	// target below is non-JVM, so `java.*` in commonMain is an unresolved reference rather than a
	// latent surprise. (That is not true of a JVM-only module: there, commonMain resolves `java.*`
	// happily and the root build's `checkCommonSourcePurity` regex task is the only net.)
	// Using a template group (rather than raw `dependsOn`) keeps the auto-wired commonMain
	// edges intact and avoids the "default hierarchy not applied" warning.
	applyDefaultHierarchyTemplate {
		common {
			group("jvmAndroid") {
				withJvm()
				withAndroidTarget()
			}
		}
	}

	jvm()

	// The iPadOS target — and the reason commonMain purity is enforceable rather than merely intended.
	// Windows, macOS, Linux and Android all run on the JVM (`:desktop` is jvm() packaged per-OS by
	// jpackage), so iOS is the ONLY platform here that is not, and the only one that can catch a
	// `java.*` leak or a `final`-on-native stdlib inheritance at compile time. It is not a proxy: no
	// stand-in target is used, because a stand-in would prove "non-JVM" without proving Apple — and a
	// dependency that publishes linuxX64 but not iosArm64 would sail straight through it.
	//
	// Compiles on Linux and on ubuntu CI: the Kotlin/Native Linux distribution bundles an iOS sysroot
	// and the ios_arm64 platform klibs, and :format is a library with no binaries.framework {}, so
	// nothing here needs Xcode's linker. Real iPadOS builds and native test runs happen on Apple
	// hardware; this target is what makes the daily loop and CI fail fast instead of days later.
	//
	// iosArm64 is a DEVICE target, so it has no runnable test task — the gate is compile-time. Add
	// iosSimulatorArm64() if native test execution on the Mac is wanted.
	iosArm64()

	// New AGP KMP-aware Android target: declared inside `kotlin {}` (not a separate
	// `com.android.library` module). namespace = R class package root.
	android {
		namespace = "org.umamo.format"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		// [kmp-jvmandroid] The hierarchy group's withAndroidTarget() above matches the LEGACY android
		// target, not AGP 9's new KMP android-library target — so androidMain never inherits the
		// jvmAndroid group and can't see its actuals (the CaffZip expect/actual, the CMO3 codec).
		// Wire the edge explicitly. (withJvm() does match, which is why the JVM target compiles fine.)
		val jvmAndroidMain = getByName("jvmAndroidMain")
		val androidMain = getByName("androidMain")
		androidMain.dependsOn(jvmAndroidMain)

		commonMain.dependencies {
			implementation(libs.kotlinxSerializationJson)
			// okio: DEFLATE (its zlibMain source set reaches every target we build, iosArm64 included)
			// and Buffer, the growable byte sink the codecs assemble output into. `implementation`,
			// not `api`: no okio type appears in :format's public surface.
			implementation(libs.okio)
			// kotlinx-datetime: the local wall clock CaffZip stamps into the CAFF zip timestamp, to
			// match what the official editor writes. The stdlib's common Clock has no time zone and a
			// DOS timestamp is local, so this closes the gap without an expect/actual.
			implementation(libs.kotlinxDatetime)
		}

		// Shared by desktop JVM + Android: the CMO3 CAFF/XML codec. JDOM lives here (not jvmMain)
		// because the editor's XML serializer must round-trip on both targets — CLAUDE.md keeps
		// CMO3 read/write available on Android. JDOM 1.x works on Android via the platform's JAXP.
		jvmAndroidMain.dependencies {
			implementation(libs.jdom)
			// The CMO3 serializer is reflection-driven (declaredMemberProperties, findAnnotation,
			// javaField) — those kotlin.reflect.full/.jvm extensions live in kotlin-reflect, not
			// the stdlib. Available on Android too, so it stays in the shared source set.
			implementation(kotlin("reflect"))
		}
		jvmMain {
			dependencies {
				// Driver for CLIP's embedded SQLite database.
				implementation(libs.sqldelightSqliteDriver)
			}
		}
		androidMain.dependencies {
			// Driver for CLIP's embedded SQLite database.
			implementation(libs.sqldelightAndroidDriver)
		}
		// nativeMain, not iosArm64Main: the default hierarchy makes it the parent of appleMain, so the
		// actual here already covers any further Apple target (a simulator, say) without moving.
		nativeMain.dependencies {
			// Driver for CLIP's embedded SQLite database (SQLiter under the hood).
			implementation(libs.sqldelightNativeDriver)
			// The native actual writes the extracted database to a temp file; okio is the only
			// filesystem the common source sets have.
			implementation(libs.okio)
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}

// SQLDelight: generate a typed ClipDatabase from src/commonMain/sqldelight/.../Clip.sq.
sqldelight {
	databases {
		create("ClipDatabase") {
			packageName.set("org.umamo.format.clip.db")
		}
	}
}

// Corpus wiring for the sample-gated tests.
//
// Two ways a sample path is found, in order:
//   1. An explicit `-D` on the Gradle command line. A RELATIVE value is resolved against the REPO
//      ROOT — not the test JVM's working directory, which is this module. That distinction is the
//      whole point: a gated test that cannot find its sample does not fail, it prints and returns,
//      so it reports PASSED while doing nothing. A relative path therefore used to disable the gate
//      in total silence. An explicit path that resolves to nothing now fails the build outright
//      (see the require below) instead.
//   2. The local golden corpus at test/corpus/ (gitignored, see README), when present. This is what
//      makes CLAUDE.md's mandatory CMO3 round-trip gate run BY DEFAULT for anyone who has the
//      corpus, rather than only when they remember the flag.
//
// Absent entirely (a fresh clone, CI) → the tests self-skip and the build stays green without
// committing a multi-gigabyte corpus. That is deliberate; CI passes no `-D` flags.
//
// Most art readers (kra/clip/psd/tiff/jpeg/png/webp) additionally auto-discover test/corpus by
// walking up from the test working directory, so they need no default here — their entries exist
// only so an explicit `-D` can override. `bmp.sample` was dropped: nothing reads it.

/** The local golden corpus root. Gitignored, so absent on CI and on a fresh clone. */
val corpusDirectory: File = rootDir.resolve("test/corpus")

/**
 * The corpus default for [samplePropertyName], or null when there is none (or no corpus).
 *
 * @param String samplePropertyName The system property name.
 * @return String? The absolute path (or comma-separated paths), or null.
 */
fun corpusDefaultFor(samplePropertyName: String): String? {
	if (!corpusDirectory.isDirectory) {
		return null
	}
	return when (samplePropertyName) {
		// Pinned to EricaTamamo specifically: the facade/CAFF/document tests assert exact counts
		// (180 image resources, 926 CAFF entries, 158 import PIs) that only this model satisfies.
		"cmo3.sample" -> corpusDirectory.resolve("cmo3/EricaTamamo.cmo3").takeIf { it.isFile }?.absolutePath
		// The cross-version gate wants every .cmo3 under cmo3/, spanning Cubism 3.x/4.x/5.3.
		"cmo3.probe" ->
			corpusDirectory
				.resolve("cmo3")
				.listFiles { candidate -> candidate.isFile && candidate.extension == "cmo3" }
				?.sortedBy { it.name }
				?.joinToString(",") { it.absolutePath }
				?.takeIf { it.isNotEmpty() }

		else -> null
	}
}

/**
 * Resolves [samplePropertyName] to an absolute path, preferring an explicit `-D` over the corpus.
 *
 * @param String samplePropertyName The system property name.
 * @return String? The value to hand the test JVM, or null to leave the test self-skipping.
 */
fun resolveSampleProperty(samplePropertyName: String): String? {
	val explicit = System.getProperty(samplePropertyName) ?: return corpusDefaultFor(samplePropertyName)
	// Some properties (cmo3.probe) carry a comma-separated list; resolve each element.
	// File.resolve returns an absolute argument unchanged, so this handles both forms.
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
	maxHeapSize = "4g"
	val sampleProperties =
		listOf(
			"cmo3.sample",
			// Forwarded so AllVersionsGateTest can run at all — it was omitted here, so the
			// cross-version parity gate could never see its property and always skipped.
			"cmo3.probe",
			"moc3.samples",
			"kra.sample",
			"clip.sample",
			"psd.sample",
			"png.sample",
			"tiff.sample",
			"webp.sample",
			"jpeg.sample",
		)
	for (samplePropertyName in sampleProperties) {
		resolveSampleProperty(samplePropertyName)?.let { samplePath ->
			systemProperty(samplePropertyName, samplePath)
		}
	}
}
