// :edit — editing session, undo/redo history, and the pure model-mutation transforms. Depends on
// :runtime (the immutable PuppetModel it snapshots and transforms). Holds no Compose: it is the
// platform-neutral editing core that both :ui and the apps drive, so it stays Android-sharable.
// :edit — 編集セッション・取り消し/やり直し履歴・純粋なモデル変換。:runtime に依存。Compose は持たない。

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// The corpus/-D forwarding for the export round-trip gate, configured by `umamoTestCorpus` below.
	id("umamo.test-corpus")
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
		jvmTest {
			dependencies {
				// The CMO3 export round-trip gate drives real :edit session ops over a corpus model,
				// then re-reads the re-emitted file through the JVM-only codec. The conversion entry
				// points (Cmo3Import / Cmo3Export) live in :interop, whose api(:format) also supplies
				// the jvmAndroidMain codec surface transitively.
				implementation(kotlin("test"))
				implementation(project(":interop"))
			}
		}
	}
}

// Corpus paths for the export round-trip gate (the `umamo.test-corpus` convention plugin): explicit -D
// wins, the local golden corpus is the default, and CI (no corpus, no flags) self-skips.
umamoTestCorpus {
	// The round-trip gate inflates corpus CMO3s (multi-megabyte JDOM); match :format's test heap.
	maxHeap("4g")
	sample("cmo3.sample", "cmo3.probe")
}