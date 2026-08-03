// Convention plugin: forward the golden corpus and its `-D` overrides to a module's test JVMs.
//
// Five build scripts each grew their own copy of this — the same corpusDefaultFor / sample-resolution
// pair, drifted three different ways (:interop and :edit forwarded an explicit `-D` verbatim while
// :format and :render resolved and existence-checked it; the corpus default table disagreed about
// which properties even had one). Since the failure mode of getting it wrong is a gated test that
// reports PASSED while doing nothing, one implementation is worth more here than in most places.
//
// A module declares what it wants and nothing else:
//
//     umamoTestCorpus {
//         maxHeap("4g")
//         sample("cmo3.sample", "cmo3.probe")
//         sampleWithCorpusDefault("moc3.sample", "moc3/modelA/modelA.moc3")
//         flag("umamo.requireGl")
//     }
//
// Resolution rules (explicit `-D` wins, corpus default second, else the test self-skips) are in
// org.umamo.buildlogic.resolveCorpusSample, and the shared default table in
// org.umamo.buildlogic.sharedCorpusDefault. Read those before adding a property.
// 規約プラグイン：ゴールデンコーパスと `-D` 上書きをテスト JVM に受け渡す。

import org.umamo.buildlogic.TestCorpusExtension
import org.umamo.buildlogic.resolveCorpusSample

val umamoTestCorpus = extensions.create<TestCorpusExtension>("umamoTestCorpus")

// Captured as plain Files so the configuration cache stores values, not a project reference.
val repositoryRoot = rootDir

/** The local golden corpus root. Gitignored, so absent on CI and on a fresh clone. */
val corpusDirectory = rootDir.resolve("test/corpus")

tasks.withType<Test>().configureEach {
	// Read at task-realization time, which is after the module's `umamoTestCorpus { }` block has run.
	umamoTestCorpus.testMaxHeap?.let { heapSize ->
		maxHeapSize = heapSize
	}
	for (sampleProperty in umamoTestCorpus.sampleProperties) {
		resolveCorpusSample(repositoryRoot, corpusDirectory, sampleProperty)?.let { resolvedValue ->
			systemProperty(sampleProperty.name, resolvedValue)
		}
	}
	for (flagName in umamoTestCorpus.flagProperties) {
		System.getProperty(flagName)?.let { flagValue ->
			systemProperty(flagName, flagValue)
		}
	}
}
