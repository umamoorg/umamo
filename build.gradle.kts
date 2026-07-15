// Root build script. Plugins are declared here with `apply false` purely to pin their
// versions for the whole build; each module opts in by `alias(...)`-ing the ones it needs.
// This avoids every module repeating version numbers (the version catalog holds those).
// ルートビルドスクリプト。プラグインは `apply false` でバージョン固定のみを行い、各モジュールが
// 必要なものを `alias(...)` で取り込む。

plugins {
	alias(libs.plugins.kotlinMultiplatform) apply false
	alias(libs.plugins.composeCompiler) apply false
	alias(libs.plugins.composeMultiplatform) apply false
	alias(libs.plugins.androidApplication) apply false
	alias(libs.plugins.androidKmpLibrary) apply false
	alias(libs.plugins.ktlint)
}

// Apply ktlint to every subproject so tab-based style (see .editorconfig) is enforced
// uniformly. ktlint reads .editorconfig, so no rule config is needed here.
subprojects {
	apply(plugin = "org.jlleitschuh.gradle.ktlint")
	// Compose Multiplatform generates Kotlin under build/generated (JetBrains space-indent style); lint
	// our own sources, not generated output (e.g. the resources `Res` collectors).
	configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
		filter {
			exclude { element -> element.file.path.contains("/generated/") }
		}
	}
}

// Keeps the common source sets free of JVM-only APIs.
//
// This is NOT redundant with the compiler.  Every target today is JVM-based (desktop JVM +
// Android/ART), so the common source set resolves `java.*` and the `kotlin.jvm.*` default import
// perfectly happily — a commonMain file can call java.util.zip and still compile.  That only
// stops being true the day a non-JVM target (Kotlin/Native, for iPadOS) is added, at which point
// every accumulated leak surfaces at once, in code nobody has touched for months.  This task
// turns that latent debt into an immediate failure instead.
//
// commonTest is in scope too, and deliberately: it has to compile for every target its commonMain
// does, so a JVM-only API there is the same latent break, and a test source set is exactly where one
// slips in unnoticed (a test that only ever runs on the JVM still has to *build* everywhere).  It is
// also where the leaks were: `"ABCD".toByteArray(Charsets.US_ASCII)` in a shared test reads as
// harmless until the day it isn't.
//
// Three rules, each from a real leak found in this tree. Note that none of them is only about imports:
// the JVM's default imports (`java.lang.*`, `kotlin.jvm.*`, `kotlin.text.Charsets`) mean JVM-only APIs
// can appear with no import line to grep for, and even a non-default API can be written out in full at
// the use site — `catch (_: java.util.zip.DataFormatException)` needs no import and was exactly how the
// old ClipRaster reached zlib.
//   1. No `java.*` / `javax.*`, whether imported or spelled out at the use site — put the code in
//      jvmAndroidMain, or hide the platform behind an expect/actual (see
//      org.umamo.format.clip.useClipDatabase for the pattern).  Reach for a common-code library
//      before either: okio covers DEFLATE and buffers, which is how org.umamo.format.binary.Deflate
//      retired the expect/actual it used to need.
//   2. `@Jvm*` annotations must be imported explicitly from `kotlin.jvm`.  They are optional
//      expectations, so non-JVM targets ignore them — but `kotlin.jvm.*` is a default import only
//      on JVM, so an unimported `@JvmInline` is an unresolved reference off-JVM.
//   3. No `Charsets` — `kotlin.text.Charsets` is JVM-only, so `String(bytes, US_ASCII)` and
//      `toByteArray(UTF_16BE)` do not exist off-JVM.  Use org.umamo.format.binary.BinaryText, or
//      `encodeToByteArray()` when the bytes really are meant to be UTF-8.
//      (`decodeToString()` is NOT a general substitute: it is UTF-8 only.)
//
// This check is a net, not a proof: it catches the leak classes we have actually hit.  The definitive
// check is compiling against a real non-JVM target — and `:format` now does exactly that: it carries
// an `iosArm64()` target, so its commonMain purity is a compiler guarantee and this task is only a
// faster pre-check there.  (The two blockers this note used to name — CArrayList/CHashMap extending
// java.util collections — were resolved by delegating instead of inheriting.)  Every other module is
// still JVM-only, and for those this task remains the only thing holding the line; the way to upgrade
// one is to copy :format's target, not to grow this regex.
val commonSources =
	fileTree(rootDir) {
		include(
			"module/*/src/commonMain/**/*.kt",
			"module/*/src/commonTest/**/*.kt",
			"app/*/src/commonMain/**/*.kt",
			"app/*/src/commonTest/**/*.kt",
		)
	}

val checkCommonSourcePurity by
	tasks.registering {
		group = "verification"
		description = "Fails if any module's commonMain/commonTest uses a JVM-only API, which would break a future Kotlin/Native target."
		inputs.files(commonSources).withPropertyName("commonSources")
		// A marker output, so a green run stays UP-TO-DATE instead of re-reading every common source
		// file on every `check`.  The task's real product is the failure, not the file.
		val report = layout.buildDirectory.file("reports/commonSourcePurity.txt")
		outputs.file(report).withPropertyName("report")
		val sourceFiles = commonSources
		val repositoryRoot = rootDir
		doLast {
			val jvmImport = Regex("""^import (java|javax)\.""")
			// Qualified use with no import: `catch (_: java.util.zip.DataFormatException)`.  The
			// lookbehind keeps `org.foo.java.Bar` and an identifier ending in "java" from matching.
			val jvmQualifiedUse = Regex("""(?<![A-Za-z0-9_.$])(java|javax)\.[a-z][A-Za-z0-9_]*\.""")
			val jvmAnnotationUse = Regex("""@(Jvm[A-Za-z]+)""")
			val jvmAnnotationImport = Regex("""^import kotlin\.jvm\.(Jvm[A-Za-z]+)""")
			val charsetsUse = Regex("""\bCharsets\.""")
			val violations = mutableListOf<String>()
			for (sourceFile in sourceFiles.files.sortedBy { it.path }) {
				val lines = sourceFile.readLines()
				val relativePath = sourceFile.relativeTo(repositoryRoot).path
				val importedJvmAnnotations =
					lines.mapNotNull { line ->
						jvmAnnotationImport.find(line.trim())?.groupValues?.get(1)
					}.toSet()
				lines.forEachIndexed { lineIndex, line ->
					val trimmed = line.trim()
					if (jvmImport.containsMatchIn(trimmed)) {
						violations += "$relativePath:${lineIndex + 1}: a common source set must not import a JVM-only API -> $trimmed"
						return@forEachIndexed
					}
					// Only flag real code, not prose in comments or docblocks.
					if (!trimmed.startsWith("*") && !trimmed.startsWith("//") && !trimmed.startsWith("/*")) {
						if (jvmQualifiedUse.containsMatchIn(line)) {
							violations +=
								"$relativePath:${lineIndex + 1}: a common source set must not reference a JVM-only API, even fully " +
								"qualified (a qualified use needs no import, so rule 1 alone would miss it) -> $trimmed"
						}
						jvmAnnotationUse.findAll(line).forEach { match ->
							val annotation = match.groupValues[1]
							if (annotation !in importedJvmAnnotations) {
								violations +=
									"$relativePath:${lineIndex + 1}: @$annotation needs an explicit `import kotlin.jvm.$annotation` " +
									"(kotlin.jvm.* is a default import only on JVM targets)"
							}
						}
						if (charsetsUse.containsMatchIn(line)) {
							violations +=
								"$relativePath:${lineIndex + 1}: Charsets is JVM-only (kotlin.text.Charsets); use " +
								"encodeToByteArray()/decodeToString() when the bytes are UTF-8, else " +
								"org.umamo.format.binary.decodeAscii / encodeUtf16Be -> $trimmed"
						}
					}
				}
			}
			if (violations.isNotEmpty()) {
				throw GradleException(
					"common-source purity check failed (${violations.size} violation(s)):\n" +
						violations.joinToString("\n") { "  $it" },
				)
			}
			val reportFile = report.get().asFile
			reportFile.parentFile.mkdirs()
			reportFile.writeText("common-source purity: ${sourceFiles.files.size} file(s) checked, no violations.\n")
		}
	}

// Wire into `check` so the guard runs with the normal verification lifecycle rather than needing to be
// remembered. Gradle runs the task once however many subprojects depend on it.
subprojects {
	tasks.matching { task -> task.name == "check" }.configureEach {
		dependsOn(checkCommonSourcePurity)
	}
}
