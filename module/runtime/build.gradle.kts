// :runtime — the pure puppet runtime: the immutable PuppetModel and its typed ids, the keyform grid
// algebra, and the sampling eval. Zero format knowledge — CMO3/MOC3 conversion lives in :interop,
// which depends on this module and :format. (Deformation eval and the renderer live in :render,
// which depends on :runtime — not the reverse.)
// :runtime — 純粋なパペットランタイム：不変の PuppetModel と型付き ID、キーフォームグリッド代数、
// サンプリング評価。フォーマット知識ゼロ — CMO3/MOC3 変換は :interop にある。（変形評価・レンダラは
// :render にあり、:runtime に依存する。）

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
