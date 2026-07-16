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
// `./gradlew :runtime:jvmTest -Dcmo3.sample=… -Drelive.dumpModel=… -Drelive.coreLib=…`.
// Absent properties are skipped, so CI (which sets none) self-skips the gated tests — no committed
// corpus or external oracle needed. (cmo3.sample mirrors the same forwarding in :format)
tasks.withType<Test>().configureEach {
	for (property in listOf("cmo3.sample", "relive.dumpModel", "relive.coreLib")) {
		System.getProperty(property)?.let { value ->
			systemProperty(property, value)
		}
	}
}
