// :interop — format↔runtime conversion. CMO3/MOC3 ingest into the PuppetModel, the CMO3 export
// reconcile, the model diff, and the RuntimeTarget↔format-version mapping. Depends on BOTH
// :format and :runtime so that neither has to know the other: :format stays a standalone codec
// library and :runtime stays a pure puppet runtime.

plugins {
	alias(libs.plugins.kotlinMultiplatform)
	alias(libs.plugins.androidKmpLibrary)
	// Umamo's own conventions (gradle/build-logic): the shared jvmAndroidMain source-set group, the
	// iosArm64 → `check` wiring, and the corpus/-D forwarding configured by `umamoTestCorpus` below.
	id("umamo.kmp-jvmandroid")
	id("umamo.kmp-ios-gate")
	id("umamo.test-corpus")
}

kotlin {
	jvmToolchain(21)

	// The `jvmAndroidMain` group comes from the `umamo.kmp-jvmandroid` convention plugin. This module
	// needs it because the CMO3 export lowering (Cmo3Export) mutates a Cmo3Model graph, which lives in
	// :format's own jvmAndroidMain (the JDOM-backed codec) — so it can't sit in commonMain.

	jvm()

	// The iPadOS ship target, mirroring :format and :runtime (see :format's docblock for the full
	// rationale). It keeps the commonMain conversions (MOC3 ingest for a future iOS viewer, the CMO3
	// ingest and model diff) a compiler-checked iOS citizen rather than the root regex gate's
	// convention. Compiles on Linux/CI (klib only, no Xcode linker); a device target has no runnable
	// test task, so `umamo.kmp-ios-gate` wires `check` to the compiles.
	iosArm64()

	android {
		namespace = "org.umamo.interop"
		compileSdk = libs.versions.android.compileSdk.get().toInt()
		minSdk = libs.versions.android.minSdk.get().toInt()
	}

	sourceSets {
		commonMain {
			dependencies {
				// Both `api` (not `implementation`): the conversion surface exposes both sides —
				// CModelSource/Cmo3Model/MocDocument parameters from :format, PuppetModel and the
				// diff types from :runtime — so consumers (:ui, tests) see them transitively.
				api(project(":format"))
				api(project(":runtime"))
			}
		}
		commonTest {
			dependencies {
				implementation(kotlin("test"))
			}
		}
		jvmTest {
			dependencies {
				// The skeleton gate compares emitted XML structure against the corpus blank; JDOM is
				// :format's own (implementation-scoped) backend, so the test declares it directly.
				implementation(libs.jdom)
				// TEST-ONLY: the MOC3 conversion round trip mirrors the app's document loader, which
				// normalizes rest meshes to canvas space through :render's evaluator before export
				// (production :interop code never depends on :render - siblings over :runtime).
				implementation(project(":render"))
			}
		}
	}
}

// Corpus wiring for the gated tests (the `umamo.test-corpus` convention plugin):
// `./gradlew :interop:jvmTest -Dcmo3.sample=… -Dmoc3.sample=… -Dmoc3.samples=…`.
//
// cmo3.sample and cmo3.probe take the shared corpus defaults, so Cmo3ImportTest's probe loop exercises
// the whole corpus by default when the local (gitignored) corpus is present; moc3.samples is where
// CompositeImportTest joins ingested CMO3s against their baked moc3s. moc3.sample (singular, read by
// Moc3ImportTest's golden-count test) has no default and only takes effect when passed as -D.
umamoTestCorpus {
	// The probe loop inflates every corpus CMO3 (Model C's main.xml alone is ~10 MB of JDOM); match
	// :format's test heap so the loop does not OOM.
	maxHeap("4g")
	sample("cmo3.sample", "cmo3.probe", "moc3.sample", "moc3.samples")
}
