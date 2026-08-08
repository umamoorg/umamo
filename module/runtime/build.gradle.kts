// :runtime — the pure puppet runtime: the immutable PuppetModel and its typed ids, the keyform grid
// algebra, and the sampling eval. Zero format knowledge — CMO3/MOC3 conversion lives in :interop,
// which depends on this module and :format. (Deformation eval and the renderer live in :render,
// which depends on :runtime — not the reverse.)

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// Wires the iosArm64 compiles into `check` (a device target has no runnable test task of its own).
	id("umamo.kmp-ios-gate")
}

kotlin {
	jvmToolchain(21)

	jvm()

	// The iPadOS ship target, mirroring :format (see its docblock for the full rationale). Two jobs:
	// it makes commonMain purity a COMPILER GUARANTEE rather than the root regex gate's convention, and
	// it is what lets :render declare the same target — :render/commonMain does api(:runtime), so the
	// renderer the Metal engineer builds against could not compile for iOS until this module did.
	// Compiles on Linux/CI (klib only, no Xcode linker); a device target has no runnable test task, so
	// `umamo.kmp-ios-gate` wires `check` to the compiles.
	iosArm64()

	android {
		namespace = "org.umamo.runtime"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}