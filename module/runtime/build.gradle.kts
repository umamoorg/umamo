// :runtime — puppet model + CMO3 ingest. Depends on :format. (Deformation eval and the renderer live
// in :render, which depends on :runtime — not the reverse.)
// :runtime — パペットモデルと CMO3 取り込み。:format に依存。（変形評価・レンダラは :render にあり、:runtime に依存する。）

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
}

kotlin {
	jvmToolchain(21)

	jvm()

	android {
		namespace = "org.umamo.runtime"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// `api` (not `implementation`): :format types appear in :runtime's
				// public surface, so consumers (:ui, apps) see them transitively.
				api(project(":format"))
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
	}
}

// Forward corpus + differential-oracle paths to the test JVM so the gated tests can run:
// `./gradlew :runtime:jvmTest -Dcmo3.sample=… -Drelive.dumpModel=… -Drelive.coreLib=…`.
// Absent properties are skipped, so CI (which sets none) self-skips the gated tests — no committed
// corpus or external oracle needed. (cmo3.sample mirrors the same forwarding in :format)
tasks.withType<Test>().configureEach {
	for (property in listOf("cmo3.sample", "relive.dumpModel", "relive.coreLib")) {
		System.getProperty(property)?.let { value ->
			systemProperty(property, value)
		}
	}
}
