// :reimport — non-destructive reconcile over the model's source bindings. Depends on :format (the
// re-read art), :runtime (the bindings live on the model's atlas tiles), and :interop (the bridge that
// births a layer's mesh and mints the additions for the layers a file gained).

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// The shared jvmAndroidMain source-set group (gradle/build-logic): the file watcher is one
	// java.nio implementation for both the desktop JVM and Android (minSdk 26 has WatchService).
	id("umamo.kmp-jvmandroid")
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
				// The content hash the watcher compares a save against, and the file system it reads.
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
				// runTest + the virtual clock the coordinator's settle and idle timing is tested on.
				implementation(libs.kotlinxCoroutinesTest)
			}
		}
		getByName("jvmTest") {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}