// :runtime — puppet model + CMO3 ingest. Depends on :format. (Deformation eval and the renderer live
// in :render, which depends on :runtime — not the reverse.)
// :runtime — パペットモデルと CMO3 取り込み。:format に依存。（変形評価・レンダラは :render にあり、:runtime に依存する。）

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

kotlin {
	jvmToolchain(21)

	jvm()

	// The iPadOS ship target, mirroring :format (see its docblock for the full rationale). Two jobs:
	// it makes commonMain purity a COMPILER GUARANTEE rather than the root regex gate's convention, and
	// it is what lets :render declare the same target — :render/commonMain does api(:runtime), so the
	// renderer the Metal engineer builds against could not compile for iOS until this module did.
	// Compiles on Linux/CI (klib only, no Xcode linker); a device target has no runnable test task, so
	// `check` is wired to the compiles explicitly below.
	iosArm64()

	android {
		namespace = "org.umamo.runtime"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// `api` (not `implementation`): :format types appear in :runtime's
				// public surface, so consumers (:ui, apps) see them transitively.
				api(project(":format"))
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
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

// Forward corpus + differential-oracle paths to the test JVM so the gated tests can run:
// `./gradlew :runtime:jvmTest -Dcmo3.sample=… -Dmoc3.sample=… -Drelive.dumpModel=… -Drelive.coreLib=…`.
// Absent properties are skipped, so CI (which sets none) self-skips the gated tests — no committed
// corpus or external oracle needed. (mirrors the same forwarding + corpus defaulting in :format)
//
// cmo3.sample defaults to the corpus's default sample (the golden-count sample) and cmo3.probe to every
// corpus .cmo3, so Cmo3ImportTest's probe loop exercises the whole corpus by default when the local
// golden corpus is present (it is gitignored, so CI still self-skips).
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
		// The blend-shape delta-frame probe joins ingested CMO3s against their baked moc3s.
		"moc3.samples" -> corpusDirectory.resolve("moc3").takeIf { it.isDirectory }?.absolutePath
		else -> null
	}
}

tasks.withType<Test>().configureEach {
	// The probe loop inflates every corpus CMO3 (Model C's main.xml alone is ~10 MB of JDOM); match
	// :format's test heap so the loop does not OOM.
	maxHeapSize = "4g"
	for (property in listOf("cmo3.sample", "cmo3.probe", "moc3.samples", "relive.dumpModel", "relive.coreLib")) {
		(System.getProperty(property) ?: corpusDefaultFor(property))?.let { value ->
			systemProperty(property, value)
		}
	}
}
