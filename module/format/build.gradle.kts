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

	// EN: Opt in to expect/actual classes (objects, interfaces, enums, annotations). The compiler
	//     still flags these Beta (KT-61573) and re-emits a warning per declaration on every build.
	//     CaffZip — our platform DEFLATE bridge — is an expect/actual OBJECT, so we hit it. This is a
	//     project-wide compiler flag, NOT a per-file @Suppress: the warning has no file-level opt-out,
	//     and keeping the opt-in in one build-script line stops it being silently edited back out.
	// JA: 期待/実際クラス(オブジェクト等)を明示的に許可。CaffZip のプラットフォーム DEFLATE ブリッジで使用。
	compilerOptions {
		freeCompilerArgs.add("-Xexpect-actual-classes")
	}

	// [kmp-jvmandroid] Keep identical across module/format, module/ui, module/render build scripts.
	// Customise the default source-set hierarchy to add a `jvmAndroidMain` group shared by
	// the two JVM-based targets (desktop JVM + Android/ART). CMO3 read/write is a JDOM +
	// Kotlin-reflection XML serializer (NOT Java object serialization — see docs/formats/CMO3.md)
	// and those are JVM-only APIs — so that code lives in src/jvmAndroidMain and is shared verbatim
	// by both, honouring CLAUDE.md's intent.
	// NOTE: while every target here is JVM-based, commonMain CAN resolve `java.*` — the compiler does
	// not enforce the split, so keeping JVM-only code out of commonMain is a convention, not a
	// guarantee. It is what preserves the option of a non-JVM target (Kotlin/Native, for iPadOS);
	// the root build's `checkCommonSourcePurity` task is what actually holds the line.
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

// Forward sample paths to the test JVM so the corpus-gated tests can run:
//   `./gradlew :format:jvmTest -Dcmo3.sample=/path/to/Sample.cmo3 -Dmoc3.samples=/path/to/dir`
//   `./gradlew :format:jvmTest -Dkra.sample=/path/to/Sample.kra`
// `cmo3.sample` is a single CAFF/facade file; `moc3.samples` is a directory walked for `.moc3`
// (decode/bake/lowering/round-trip); `kra.sample` is a single `.kra` exercised by the reader.
// Absent → those tests self-skip, so CI stays green without committing a multi-megabyte corpus.
tasks.withType<Test>().configureEach {
	maxHeapSize = "4g"
	for (samplePropertyName in listOf("cmo3.sample", "moc3.samples", "kra.sample", "clip.sample", "psd.sample", "png.sample", "bmp.sample", "tiff.sample", "webp.sample", "jpeg.sample")) {
		System.getProperty(samplePropertyName)?.let { samplePath ->
			systemProperty(samplePropertyName, samplePath)
		}
	}
}
