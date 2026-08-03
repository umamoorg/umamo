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

// Corpus paths for the export round-trip gate, mirroring :runtime's forwarding (see
// module/runtime/build.gradle.kts for the full rationale): explicit -D wins, the local golden
// corpus is the default, and CI (no corpus, no flags) self-skips.
val corpusDirectory: File = rootDir.resolve("test/corpus")

/**
 * The corpus default for [propertyName], or null when there is none (or no corpus).
 *
 * @param String propertyName The system property name.
 * @return String? The default path value, or null.
 */
fun corpusDefaultFor(propertyName: String): String? {
	if (!corpusDirectory.isDirectory) {
		return null
	}
	return when (propertyName) {
		"cmo3.sample" -> corpusDirectory.resolve("cmo3/EricaTamamo.cmo3").takeIf { it.isFile }?.absolutePath
		"cmo3.probe" ->
			corpusDirectory
				.resolve("cmo3")
				.listFiles { candidate -> candidate.isFile && candidate.extension == "cmo3" }
				?.sortedBy { it.name }
				?.joinToString(",") { it.absolutePath }
				?.takeIf { it.isNotEmpty() }
		else -> null
	}
}

tasks.withType<Test>().configureEach {
	// The round-trip gate inflates corpus CMO3s (multi-megabyte JDOM); match :format's test heap.
	maxHeapSize = "4g"
	for (property in listOf("cmo3.sample", "cmo3.probe")) {
		(System.getProperty(property) ?: corpusDefaultFor(property))?.let { value ->
			systemProperty(property, value)
		}
	}
}
