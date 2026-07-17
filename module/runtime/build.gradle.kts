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
// corpus or external oracle needed. The two ingest sample properties fall back to the local
// (gitignored) corpus when it is checked out, mirroring :format's corpusDefaultFor, so a plain
// `./gradlew :runtime:jvmTest` exercises the CMO3/MOC3 ingest + parity tests locally.
tasks.withType<Test>().configureEach {
	val corpusDefaults =
		mapOf(
			"cmo3.sample" to "test/corpus/cmo3/EricaTamamo.cmo3",
			"moc3.sample" to "test/corpus/moc3/EricaTamamo/EricaTamamo.moc3",
		)
	for (property in listOf("cmo3.sample", "moc3.sample", "relive.dumpModel", "relive.coreLib")) {
		val explicit = System.getProperty(property)
		val resolved =
			if (explicit != null) {
				// An explicit -D resolves against the repo root and must exist: a gated test that cannot
				// find its sample skips rather than fails, so a typo would silently disable the gate while
				// reporting PASSED (mirrors :render's resolveSampleProperty).
				val explicitFile = rootDir.resolve(explicit.trim())
				require(explicitFile.exists()) {
					"-D$property=$explicit resolves to '${explicitFile.absolutePath}', which does not exist. " +
						"Relative values resolve against the repo root ($rootDir)."
				}
				explicitFile.absolutePath
			} else {
				corpusDefaults[property]
					?.let { relativePath -> rootProject.layout.projectDirectory.file(relativePath).asFile }
					?.takeIf { it.isFile }
					?.absolutePath
			}
		resolved?.let { value ->
			systemProperty(property, value)
		}
	}
}
