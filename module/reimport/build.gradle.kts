// :reimport — non-destructive reconcile over the model's source bindings. Depends on :format (the
// re-read art) and :runtime (the bindings live on the model's atlas tiles).

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
				api(project(":runtime"))
			}
		}
	}
}