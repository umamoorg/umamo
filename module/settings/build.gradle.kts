// :settings — JSON settings engine over :storage: bundled defaults ← user overrides (← vendor later),
// dotted-key get/set, persistence, and a change-event Flow. → :storage, kotlinx-serialization, coroutines.
// :settings — :storage 上の JSON 設定エンジン。デフォルト←ユーザー設定の重ね合わせ、ドットキー get/set、変更通知。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	alias(libs.plugins.kotlinSerialization)
}

kotlin {
	jvmToolchain(21)

	jvm()

	android {
		namespace = "org.umamo.settings"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// `api`: AppStorage + JsonElement appear in the Settings surface, so consumers see them.
				api(project(":storage"))
				api(libs.kotlinxSerializationJson)
				implementation(libs.kotlinxCoroutinesCore)
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
				// In-memory FileSystem so settings load/merge/persist is tested with no real disk.
				implementation(libs.okio.fakefilesystem)
				// runTest for the change-event Flow assertion.
				implementation(libs.kotlinxCoroutinesTest)
			}
		}
	}
}
