package org.umamo.interop.uma

import org.umamo.format.FormatRegistry
import org.umamo.format.art.SourceArt
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.runtime.model.PuppetModel
import java.io.File
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The puppet entry's round-trip gate over the corpus (docs/format/UMA.md §4): every CMO3, every MOC3, and
 * every layered artwork file imports, saves as UMA, reopens, and compares structurally identical and
 * bit-exact, and the saved file rewrites byte for byte.
 *
 * Corpus-gated: `cmo3.probe` (every corpus .cmo3 by default), `moc3.samples` (the moc3 corpus directory), and
 * the PSD / CLIP / KRA files under test/corpus found by walking up from the working directory.  Each format
 * self-skips with a printed line when its samples are absent.
 */
class UmaPuppetRoundTripCorpusTest {
	private val writer = UmaWriterInfo("Umamo", "corpus-test")

	/**
	 * Saves [model]'s puppet entry, reopens it, and fails on any structural difference or unstable rewrite.
	 *
	 * @param String      label The sample's name, for failures.
	 * @param PuppetModel model The imported model.
	 */
	private fun assertRoundTrips(label: String, model: PuppetModel) {
		val bytes = Uma.write(UmaModel.create(writer).withPuppet(UmaPuppetExport.puppetOf(model)))
		val reopened = Uma.read(bytes)
		val differences = puppetStructureDifferences(model, UmaPuppetImport.modelOf(assertNotNull(reopened.puppet, "$label: the puppet entry reads back")))
		assertTrue(differences.isEmpty(), "$label: ${differences.size} differences, first ${differences.take(20)}")
		assertContentEquals(bytes, Uma.write(reopened), "$label: an unedited reopen rewrites byte for byte")
	}

	/**
	 * Every corpus CMO3.
	 */
	@Test
	fun cmo3Corpus() {
		val samples = System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { file -> file.isFile }.orEmpty()
		if (samples.isEmpty()) {
			println("no cmo3.probe samples; skipping the CMO3 puppet round trip")
			return
		}
		for (sample in samples) {
			val root = Cmo3.read(sample).root as? CModelSource ?: error("${sample.name}: root is not a CModelSource")
			assertRoundTrips(sample.name, Cmo3Import.fromModelSource(root))
		}
		println("[Umamo][uma] ${samples.size} CMO3 samples round-trip their puppet structure")
	}

	/**
	 * Every corpus MOC3.
	 */
	@Test
	fun moc3Corpus() {
		val samples =
			System.getProperty("moc3.samples")?.let(::File)?.takeIf { directory -> directory.isDirectory }
				?.walkTopDown()?.filter { file -> file.isFile && file.extension == "moc3" }?.sortedBy { file -> file.path }?.toList().orEmpty()
		if (samples.isEmpty()) {
			println("no moc3.samples; skipping the MOC3 puppet round trip")
			return
		}
		for (sample in samples) {
			assertRoundTrips(sample.name, Moc3Import.fromMocDocument(Moc3.read(sample.readBytes()), null))
		}
		println("[Umamo][uma] ${samples.size} MOC3 samples round-trip their puppet structure")
	}

	/**
	 * Every layered artwork file in the corpus, imported as a fresh rig.
	 */
	@Test
	fun artworkCorpus() {
		val samples = locateArtworkSamples()
		if (samples.isEmpty()) {
			println("no test/corpus artwork samples; skipping the artwork puppet round trip")
			return
		}
		for (sample in samples) {
			val bytes = sample.readBytes()
			val codec = assertNotNull(FormatRegistry.detect(bytes, sample.name), "${sample.name}: detected")
			val art = codec.read(bytes) as SourceArt
			val result = SourceArtImport.fromSourceArt(art, ArtSourceDescriptor(sample.name, sample.path, codec.kind.extension))
			assertRoundTrips(sample.name, result.puppet)
		}
		println("[Umamo][uma] ${samples.size} artwork samples round-trip their puppet structure")
	}

	/**
	 * Every PSD, CLIP, and KRA under test/corpus, found by walking up from the working directory.
	 *
	 * @return List<File> The samples, empty when there is no corpus.
	 */
	private fun locateArtworkSamples(): List<File> {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return listOf("psd", "clip", "krita")
					.flatMap { folder -> File(corpus, folder).listFiles { file -> file.isFile }.orEmpty().toList() }
					.filter { file -> file.extension.lowercase() in setOf("psd", "clip", "kra") }
					.sortedBy { file -> file.name }
			}
			directory = directory.parentFile
		}
		return emptyList()
	}
}