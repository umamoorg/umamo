// :reimport — source-art binding + non-destructive reconcile. Depends on :format.
// :reimport — ソースアート紐付けと非破壊リインポート。:format に依存。

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
			}
		}
	}
}
