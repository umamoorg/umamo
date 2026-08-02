// :interop — format↔runtime conversion. CMO3/MOC3 ingest into the PuppetModel, the CMO3 export
// reconcile, the model diff, and the RuntimeTarget↔format-version mapping. Depends on BOTH
// :format and :runtime so that neither has to know the other: :format stays a standalone codec
// library and :runtime stays a pure puppet runtime.
// :interop — フォーマット↔ランタイム変換。CMO3/MOC3 の PuppetModel への取り込み、CMO3 エクスポートの
// 照合、モデル差分、RuntimeTarget とフォーマットバージョンの対応付け。:format と :runtime の橋渡しとして
// その両方に依存する（:format は独立したコーデック層、:runtime は純粋なパペットランタイムのまま）。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

kotlin {
	jvmToolchain(21)

	// [kmp-jvmandroid] Keep identical across module/format, module/ui, module/render, module/interop
	// build scripts. Customise the default source-set hierarchy to add a `jvmAndroidMain` group shared
	// by the two JVM-based targets (desktop JVM + Android/ART). The CMO3 export lowering (Cmo3Export)
	// mutates a Cmo3Model graph, which lives in :format's jvmAndroidMain (the JDOM-backed codec) — so
	// it can't sit in commonMain. Promoting this block to a convention plugin is tracked in TODO.md.
	applyDefaultHierarchyTemplate {
		common {
			group("jvmAndroid") {
				withJvm()
				withAndroidTarget()
			}
		}
	}

	jvm()

	// The iPadOS ship target, mirroring :format and :runtime (see :format's docblock for the full
	// rationale). It keeps the commonMain conversions (MOC3 ingest for a future iOS viewer, the CMO3
	// ingest and model diff) a compiler-checked iOS citizen rather than the root regex gate's
	// convention. Compiles on Linux/CI (klib only, no Xcode linker); a device target has no runnable
	// test task, so `check` is wired to the compiles explicitly below.
	iosArm64()

	android {
		namespace = "org.umamo.interop"
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
				// Both `api` (not `implementation`): the conversion surface exposes both sides —
				// CModelSource/Cmo3Model/MocDocument parameters from :format, PuppetModel and the
				// diff types from :runtime — so consumers (:ui, tests) see them transitively.
				api(project(":format"))
				api(project(":runtime"))
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
		jvmTest {
			dependencies {
				// The skeleton gate compares emitted XML structure against the corpus blank; JDOM is
				// :format's own (implementation-scoped) backend, so the test declares it directly.
				implementation(libs.jdom)
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

// Forward corpus paths to the test JVM so the gated tests can run:
// `./gradlew :interop:jvmTest -Dcmo3.sample=… -Dmoc3.sample=… -Dmoc3.samples=…`.
// Absent properties are skipped, so CI (which sets none) self-skips the gated tests — no committed
// corpus needed. (mirrors the same forwarding + corpus defaulting in :format)
//
// cmo3.sample defaults to the corpus's default sample (the golden-count sample) and cmo3.probe to every
// corpus .cmo3, so Cmo3ImportTest's probe loop exercises the whole corpus by default when the local
// golden corpus is present (it is gitignored, so CI still self-skips). moc3.sample (singular, read by
// Moc3ImportTest's golden-count test) has no corpus default and only takes effect when passed as -D.
val corpusDirectory: File = rootDir.resolve("test/corpus")

/**
 * The corpus default for [propertyName], or null when there is none (or no corpus).
 *
 * @param String propertyName The system property name.
 * @return String? The default path value, or null.
 */
fun corpusDefaultFor(propertyName: String): String? {
	if (!corpusDirectory.isDirectory) {
		return null
	}
	return when (propertyName) {
		"cmo3.sample" -> corpusDirectory.resolve("cmo3/EricaTamamo.cmo3").takeIf { it.isFile }?.absolutePath
		"cmo3.probe" ->
			corpusDirectory
				.resolve("cmo3")
				.listFiles { candidate -> candidate.isFile && candidate.extension == "cmo3" }
				?.sortedBy { it.name }
				?.joinToString(",") { it.absolutePath }
				?.takeIf { it.isNotEmpty() }
		// CompositeImportTest joins ingested CMO3s against their baked moc3s from this directory.
		"moc3.samples" -> corpusDirectory.resolve("moc3").takeIf { it.isDirectory }?.absolutePath
		else -> null
	}
}

tasks.withType<Test>().configureEach {
	// The probe loop inflates every corpus CMO3 (Model C's main.xml alone is ~10 MB of JDOM); match
	// :format's test heap so the loop does not OOM.
	maxHeapSize = "4g"
	for (property in listOf("cmo3.sample", "cmo3.probe", "moc3.sample", "moc3.samples")) {
		(System.getProperty(property) ?: corpusDefaultFor(property))?.let { value ->
			systemProperty(property, value)
		}
	}
}
