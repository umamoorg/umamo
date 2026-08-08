// :interop — format↔runtime conversion. CMO3/MOC3 ingest into the PuppetModel, the CMO3 export
// reconcile, the MOC3 export lowering, the model diff, and the RuntimeTarget↔format-version
// mapping. Depends on BOTH
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
// the whole corpus by default when the local (gitignored) corpus is present; moc3.samples points at the
// whole moc3 tree, which CompositeImportTest joins against ingested CMO3s and the MOC3 export/downgrade
// round-trip gates walk in full. moc3.sample (singular, read by Moc3ImportTest's golden-count test) has
// no default and only takes effect when passed as -D.
umamoTestCorpus {
	// The probe loop inflates every corpus CMO3 (Model C's main.xml alone is ~10 MB of XML DOM).  Raised
	// from 4g when modelF joined the corpus: at 57.8 MB and ~6.5 M keyform vertex positions its
	// MOC3→CMO3 conversion in Cmo3ConversionRoundTripTest OOMs at 4g and passes at 8g, because the
	// conversion holds the PuppetModel and the whole synthesized document graph at once.  Do not trim
	// this back without re-running that test against modelF.
	maxHeap("8g")
	sample("cmo3.sample", "cmo3.probe", "moc3.sample", "moc3.samples")
}