// :edit — editing session, undo/redo history, and the pure model-mutation transforms. Depends on
// :runtime (the immutable PuppetModel it snapshots and transforms). Holds no Compose: it is the
// platform-neutral editing core that both :ui and the apps drive, so it stays Android-sharable.
// :edit — 編集セッション・取り消し/やり直し履歴・純粋なモデル変換。:runtime に依存。Compose は持たない。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

kotlin {
	jvmToolchain(21)

	jvm()

	android {
		namespace = "org.umamo.edit"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// `api` (not `implementation`): PuppetModel and the typed ids appear in :edit's public
				// surface (EditorSession exposes StateFlow<PuppetModel>, Change carries ids), so consumers
				// (:ui, apps) see :runtime — and transitively :format — without re-declaring it.
				api(project(":runtime"))
				// `api`: StateFlow / SharedFlow are part of EditorSession's public surface, so a consumer
				// that collects them needs the coroutines types visible transitively.
				api(libs.kotlinxCoroutinesCore)
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}
