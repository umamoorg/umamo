package org.umamo.buildlogic

import java.io.File

/**
 * One path-valued system property a module forwards to its test JVMs.
 *
 * @param String name The system property name, e.g. "cmo3.sample".
 * @param String? corpusRelativeDefault A module-specific default, relative to test/corpus, or null to
 *                take whatever [sharedCorpusDefault] has for this name (which may itself be nothing,
 *                leaving the property explicit-`-D`-only).
 */
data class CorpusSampleProperty(
	val name: String,
	val corpusRelativeDefault: String?,
)

/**
 * Declares which corpus-backed system properties a module's test JVMs receive.
 *
 * Registered by the `umamo.test-corpus` convention plugin as `umamoTestCorpus`; see that plugin for
 * the resolution rules, and org.umamo.buildlogic.sharedCorpusDefault for the shared default table.
 */
abstract class TestCorpusExtension {
	/** The declared path-valued properties, in declaration order. Read by the convention plugin. */
	val sampleProperties: MutableList<CorpusSampleProperty> = mutableListOf()

	/** The declared plain flags. Read by the convention plugin. */
	val flagProperties: MutableList<String> = mutableListOf()

	/** The test JVM heap, or null to leave Gradle's default. Read by the convention plugin. */
	var testMaxHeap: String? = null
		private set

	/**
	 * Forwards path-valued sample properties, defaulted from the shared corpus table.
	 *
	 * A name the shared table has no entry for is still forwarded — it simply has no default, so it
	 * only reaches the test JVM when an explicit `-D` is passed.
	 *
	 * @param String propertyNames The system property names.
	 */
	fun sample(vararg propertyNames: String) {
		for (propertyName in propertyNames) {
			sampleProperties += CorpusSampleProperty(propertyName, null)
		}
	}

	/**
	 * Forwards one path-valued sample property with a default this module alone wants.
	 *
	 * Use this when the shared table's answer is wrong HERE — :ui's moc3.sample is the case: the other
	 * modules deliberately leave it explicit-only, and giving it a table entry would silently switch
	 * their gated tests on.
	 *
	 * @param String propertyName        The system property name.
	 * @param String corpusRelativePath  The default, relative to test/corpus. File or directory; it is
	 *                                   used only when it exists, so a partial corpus still self-skips.
	 */
	fun sampleWithCorpusDefault(
		propertyName: String,
		corpusRelativePath: String,
	) {
		sampleProperties += CorpusSampleProperty(propertyName, corpusRelativePath)
	}

	/**
	 * Forwards on/off flags verbatim — no path resolution, no existence check.
	 *
	 * A flag is a switch, not a location (`-Dcmo3.generate=true`, `-Dumamo.requireGl=true`), so putting
	 * one through the sample path would reject it for not naming a file that exists.  Forwarding is
	 * still the part that matters: a flag the build forgets to pass through is a test that reads a
	 * property nobody set and returns immediately, reporting PASSED while doing nothing.
	 *
	 * @param String propertyNames The system property names.
	 */
	fun flag(vararg propertyNames: String) {
		flagProperties += propertyNames
	}

	/**
	 * Sets the heap for this module's test JVMs.
	 *
	 * @param String size A JVM heap size, e.g. "4g".
	 */
	fun maxHeap(size: String) {
		testMaxHeap = size
	}
}

/**
 * The shared corpus default for [propertyName], or null when the table has no entry for it.
 *
 * These are the defaults that mean the same thing in every module, so they live in one place rather
 * than being re-derived per build script (which is how :interop and :render came to disagree with
 * :format about whether an explicit `-D` gets checked at all).
 *
 * @param File corpusDirectory The local golden corpus root. Gitignored, so absent on CI and on a
 *             fresh clone — every lookup here is allowed to come back null.
 * @param String propertyName The system property name.
 * @return String? The default value, or null when there is none.
 */
fun sharedCorpusDefault(
	corpusDirectory: File,
	propertyName: String,
): String? {
	if (!corpusDirectory.isDirectory) {
		return null
	}
	return when (propertyName) {
		// Pinned to one model on purpose, for two independent reasons: :format's facade/CAFF/document
		// tests assert exact counts (180 image resources, 926 CAFF entries, 158 import PIs) that only
		// this model satisfies, and it is the corpus model that actually carries glue affecters, which
		// is what makes :render's glue gates meaningful rather than vacuously green.
		"cmo3.sample" -> corpusDirectory.resolve("cmo3/EricaTamamo.cmo3").takeIf { it.isFile }?.absolutePath

		// Every .cmo3 in the corpus, spanning Cubism 3.x/4.x/5.3. The cross-version gate wants the
		// whole set, and ModelGenerator wants it for the same reason its own docblock gives: the
		// generated model is the UNION over every sample, so a field or enum constant that only one
		// project exercises still gets covered. Generating from one sample would silently drop the rest.
		"cmo3.probe", "cmo3.gensample" ->
			corpusDirectory
				.resolve("cmo3")
				.listFiles { candidate -> candidate.isFile && candidate.extension == "cmo3" }
				?.sortedBy { it.name }
				?.joinToString(",") { it.absolutePath }
				?.takeIf { it.isNotEmpty() }

		// The whole moc3 tree — the decode/lowering/bake/probe gates walk it.
		"moc3.samples" -> corpusDirectory.resolve("moc3").takeIf { it.isDirectory }?.absolutePath

		// Deliberately absent, and worth stating: `moc3.sample` (singular). :ui defaults it to the one
		// corpus family that is complete on disk, while :interop and :render want it explicit-only —
		// a table entry here would switch their gated tests on as a side effect.
		else -> null
	}
}

/**
 * Resolves [sampleProperty] to the value handed to the test JVM, or null to leave the test skipping.
 *
 * Two ways a path is found, in order:
 *   1. An explicit `-D` on the Gradle command line. A RELATIVE value resolves against the REPO ROOT —
 *      not the test JVM's working directory, which is the module. That distinction is the whole point:
 *      a gated test that cannot find its sample does not fail, it prints and returns, so it reports
 *      PASSED while doing nothing. A relative path therefore used to disable the gate in total silence.
 *      An explicit path that resolves to nothing fails the build outright instead.
 *   2. The local golden corpus, when present — the module-specific default if it declared one, else the
 *      shared table. This is what makes the mandatory CMO3 round-trip gate run BY DEFAULT for anyone
 *      who has the corpus, rather than only when they remember the flag.
 *
 * Absent entirely (a fresh clone, CI) → null, the tests self-skip, and the build stays green without a
 * multi-gigabyte corpus in git. That is deliberate; CI passes no sample flags.
 *
 * @param File repositoryRoot  The repo root, which relative `-D` values resolve against.
 * @param File corpusDirectory The local golden corpus root (test/corpus).
 * @param CorpusSampleProperty sampleProperty The property being resolved.
 * @return String? The absolute path (or comma-separated paths), or null.
 */
fun resolveCorpusSample(
	repositoryRoot: File,
	corpusDirectory: File,
	sampleProperty: CorpusSampleProperty,
): String? {
	val explicitValue =
		System.getProperty(sampleProperty.name)
			?: return moduleOrSharedDefault(corpusDirectory, sampleProperty)

	// Some properties (cmo3.probe) carry a comma-separated list; resolve each element. File.resolve
	// returns an absolute argument unchanged, so this handles both forms.
	val resolvedValue =
		explicitValue
			.split(',')
			.filter { it.isNotBlank() }
			.joinToString(",") { path -> repositoryRoot.resolve(path.trim()).absolutePath }
	// Fail loudly rather than let the gated test skip-and-pass on a typo.
	for (path in resolvedValue.split(',')) {
		require(File(path).exists()) {
			"-D${sampleProperty.name}=$explicitValue resolves to '$path', which does not exist. " +
				"Relative values resolve against the repo root ($repositoryRoot)."
		}
	}
	return resolvedValue
}

/**
 * The default for [sampleProperty]: its own corpus-relative path when it declared one, else the table.
 *
 * @param File corpusDirectory The local golden corpus root.
 * @param CorpusSampleProperty sampleProperty The property being defaulted.
 * @return String? The absolute path, or null when there is no default or no corpus.
 */
private fun moduleOrSharedDefault(
	corpusDirectory: File,
	sampleProperty: CorpusSampleProperty,
): String? {
	val corpusRelativeDefault =
		sampleProperty.corpusRelativeDefault
			?: return sharedCorpusDefault(corpusDirectory, sampleProperty.name)
	return corpusDirectory.resolve(corpusRelativeDefault).takeIf { it.exists() }?.absolutePath
}
