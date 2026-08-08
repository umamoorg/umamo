// :format — Cubism file family + art I/O. KMP library shared by desktop (JVM) and Android.
// :format — Cubism ファイル群とアート入出力。デスクトップ(JVM)と Android が共有する KMP ライブラリ。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
	// Generates the typed CLIP database from module/format/src/commonMain/sqldelight/*.sq.
	alias(libs.plugins.sqldelight)
	// Umamo's own conventions (gradle/build-logic): the shared jvmAndroidMain source-set group, the
	// iosArm64 → `check` wiring, and the corpus/-D forwarding configured by `umamoTestCorpus` below.
	id("umamo.kmp-jvmandroid")
	id("umamo.kmp-ios-gate")
	id("umamo.test-corpus")
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

	// The `jvmAndroidMain` group this module's CMO3 serializer lives in comes from the
	// `umamo.kmp-jvmandroid` convention plugin — the serializer is Kotlin-reflection-driven (NOT Java
	// object serialization — see docs/format/CMO3.md), and kotlin-reflect is a JVM-only API.  The XML
	// layer itself (org.umamo.format.xml + XmlCodec) is commonMain; de-reflecting the serializer is
	// Workstream 2 of docs/plan/cmo3-commonmain-migration.md.
	// NOTE: commonMain purity is a COMPILER GUARANTEE in this module, not a convention — the iosArm64
	// target below is non-JVM, so `java.*` in commonMain is an unresolved reference rather than a
	// latent surprise. (That is not true of a JVM-only module: there, commonMain resolves `java.*`
	// happily and the root build's `checkCommonSourcePurity` regex task is the only net.)

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
	// iosArm64 is a DEVICE target, so it has no runnable test task — the gate is compile-time, and
	// therefore nothing pulls it into `check` on its own; that is what `umamo.kmp-ios-gate` above fixes.
	// Add iosSimulatorArm64() if native test EXECUTION on the Mac is wanted.
	iosArm64()

	// New AGP KMP-aware Android target: declared inside `kotlin {}` (not a separate
	// `com.android.library` module). namespace = R class package root.
	android {
		namespace = "org.umamo.format"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		// The `umamo.kmp-jvmandroid` convention plugin creates this group and wires androidMain onto it;
		// it is not a well-known source-set name, so it has no generated accessor and is looked up here.
		val jvmAndroidMain = getByName("jvmAndroidMain")
		val androidMain = getByName("androidMain")

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
			// xmlutil: the streaming reader behind org.umamo.format.xml.XmlParser. `implementation`
			// on purpose — no xmlutil type may appear in :format's public surface, so the parser
			// stays swappable. Emission never goes through xmlutil (XmlEmitter owns the bytes).
			implementation(libs.xmlutilCore)
		}

		// Shared by desktop JVM + Android: the CMO3 serializer and the KRA reader.
		jvmAndroidMain.dependencies {
			// JDOM's ONE remaining production use is KraReader's maindoc.xml parse (java.util.zip
			// pins that file here anyway); the CMO3 codec reads and writes through the commonMain
			// XML layer.  Workstream 4 of docs/plan/cmo3-commonmain-migration.md removes this.
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
		jvmTest {
			dependencies {
				// The synthetic CLIP fixture (ClipSyntheticReaderTest) drives the JDBC driver directly
				// to generate its database.  jvmMain already carries the driver, but the test's use is
				// its own - declared here so it survives the driver ever moving out of jvmMain.
				implementation(libs.sqldelightSqliteDriver)
				// JDOM as the differential ORACLE for the common XML layer (XmlDifferentialOracleTest)
				// and the parser inside ModelGenerator.  Declared here in its own right so the tests
				// survive jdom leaving the main source sets (it currently also arrives transitively
				// via jvmAndroidMain, which Workstream 4 of the commonMain migration removes).
				implementation(libs.jdom)
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

// Corpus wiring for the sample-gated tests (the `umamo.test-corpus` convention plugin). Explicit `-D`
// wins, the local golden corpus is the default, and absent both the test self-skips — see
// org.umamo.buildlogic.resolveCorpusSample in gradle/build-logic for the rules and why they are what
// they are.
//
// Most art readers (kra/clip/psd/tiff/jpeg/png/webp) additionally auto-discover test/corpus by walking
// up from the test working directory, so they need no default here — their entries exist only so an
// explicit `-D` can override. `bmp.sample` was dropped: nothing reads it.
umamoTestCorpus {
	maxHeap("4g")
	sample(
		"cmo3.sample",
		// Forwarded so AllVersionsGateTest can run at all — it was omitted here, so the
		// cross-version parity gate could never see its property and always skipped.
		"cmo3.probe",
		// The MOC3 decode/lowering/bake/probe gates walk the whole moc3 corpus - without this they
		// silently self-skipped on a plain `:format:jvmTest`.
		"moc3.samples",
		"kra.sample",
		"clip.sample",
		"psd.sample",
		"png.sample",
		"tiff.sample",
		"webp.sample",
		"jpeg.sample",
		// ModelGenerator's input set. Defaults to the whole cmo3/ corpus so the generated model is the
		// union across every sample (see sharedCorpusDefault).
		"cmo3.gensample",
		// Cmo3ResaveDumpTest's input list. Explicit-only (the shared table has no entry), so the resave
		// dump never floods build/ on a plain test run.
		"cmo3.resave",
	)
	// ModelGenerator's on-switch — a boolean, not a path, so it goes through `flag` and skips the
	// file-existence check. Without the forwarding the generator reads a property nobody set and
	// returns immediately: a @Test reporting PASSED while generating nothing.
	flag("cmo3.generate")
}