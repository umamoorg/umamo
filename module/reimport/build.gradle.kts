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
				api(project(":runtime"))
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}