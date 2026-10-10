// :geometry — pure vector geometry with zero project dependencies: Shewchuk's robust orient2d /
// incircle predicates and the Delaunay triangulation the auto-mesh is built on. It knows no pixels,
// puppets, or formats; :interop (the mesher) and :edit (mesh tools) consume it.

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// Wires the iosArm64 compiles into `check` (a device target has no runnable test task of its own).
	id("umamo.kmp-ios-gate")
}

kotlin {
	jvmToolchain(21)

	jvm()

	// The iPadOS ship target, mirroring :format (see its docblock for the full rationale): it makes
	// commonMain purity a compiler guarantee. Compiles on Linux/CI (klib only, no Xcode linker); a
	// device target has no runnable test task, so `umamo.kmp-ios-gate` wires `check` to the compiles.
	iosArm64()

	// RUNS the commonTest suite under Kotlin/Native, in addition to iosArm64 - not as a stand-in for it.
	// The predicates' exactness rests on every multiply and add rounding on its own (no fused
	// multiply-add), which the JVM guarantees and Kotlin/Native has to be shown to keep; iosArm64 only
	// compiles, so without a runnable native target that assumption would never be tested. A host
	// target, so `check` runs it on its own (linuxX64Test).
	linuxX64()

	android {
		namespace = "org.umamo.geometry"
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