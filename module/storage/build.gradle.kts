// :storage — multiplatform app-file foundation: per-OS config/data directories, file IO (okio), and a
// native open/save dialog contract. The low-level base :settings (and later the editor UI) build on.
// :storage — マルチプラットフォームのアプリ保存基盤：OS 別の設定/データ場所、ファイル IO（okio）、ファイルダイアログ契約。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

kotlin {
	jvmToolchain(21)

	jvm()

	android {
		namespace = "org.umamo.storage"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// `api` (not `implementation`): okio's `Path`/`FileSystem` appear in AppStorage's public
				// surface, so consumers (:settings, apps) see them transitively.
				api(libs.okio)
				// `api` again: FileKit's `PlatformFile` is the FilePicker contract's return type, and the
				// apps call `FileKit.init`, so both must reach consumers transitively. Pulls filekit-core.
				api(libs.filekitDialogs)
				// `api` because UmamoLog's retained-log buffer exposes a StateFlow in its public surface, so
				// consumers (the UI's Logs panel) see kotlinx-coroutines transitively - same rule as okio/FileKit.
				api(libs.kotlinxCoroutinesCore)
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
				// okio's in-memory FileSystem, so OkioAppStorage's tests run with no real disk.
				implementation(libs.okio.fakefilesystem)
			}
		}
	}
}
