// :reimport — non-destructive reconcile over the model's source bindings. Depends on :format (the
// re-read art), :runtime (the bindings live on the model's atlas tiles), and :interop (the bridge that
// births a layer's mesh and mints the additions for the layers a file gained).

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

kotlin {
	jvmToolchain(21)

	jvm()

	android {
		namespace = "org.umamo.reimport"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				implementation(project(":format"))
				implementation(project(":interop"))
				// The content hash the watcher compares a save against, and okio (its `api`), which the
				// polling watcher stats files through - the one file-system API that runs on every
				// Kotlin target, so nothing here is JVM-bound.
				implementation(project(":storage"))
				api(project(":runtime"))
				// The watch coordinator is coroutine-driven (settle timers, the idle wait); `api` because
				// its scope and flows are its public surface.
				api(libs.kotlinxCoroutinesCore)
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
				// runTest + the virtual clock the coordinator's settle and the watcher's polling are tested on.
				implementation(libs.kotlinxCoroutinesTest)
				// An in-memory file system for the polling watcher's test.
				implementation(libs.okio.fakefilesystem)
			}
		}
	}
}