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
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.visibleDrawableIds
import java.io.File
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The puppet entry's round-trip gate over the corpus (docs/format/UMA.md §4): every CMO3, every MOC3, and
 * every layered artwork file imports, saves as UMA, reopens, and compares identical and bit-exact across every
 * field the entry carries; the saved file rewrites byte for byte; and the CPU evaluation of the reopened model
 * matches the original's bit for bit at the default pose, the all-minimum and all-maximum poses, and eight
 * seeded random ones.  The two models named for measurement print their save and open costs.
 *
 * Corpus-gated: `cmo3.probe` (every corpus .cmo3 by default), `moc3.samples` (the moc3 corpus directory), and
 * the PSD / CLIP / KRA files under test/corpus found by walking up from the working directory.  Each format
 * self-skips with a printed line when its samples are absent.
 */
class UmaPuppetRoundTripCorpusTest {
	private val writer = UmaWriterInfo("Umamo", "corpus-test")

	private val evaluator = CpuDeformationEvaluator()

	// World position floats the default-pose evaluations produced, so a run can show it compared real geometry.
	private var evaluatedPositionCount = 0L

	// The models whose save and open costs are recorded in the design plan.
	private val measuredSamples = setOf("EricaTamamo.cmo3", "modelF.cmo3", "EricaTamamo.moc3", "modelF.moc3")

	/**
	 * Saves [model]'s puppet entry, reopens it, and fails on any difference, an unstable rewrite, or an
	 * evaluation that no longer matches.
	 *
	 * @param String      label The sample's name, for failures.
	 * @param PuppetModel model The imported model.
	 */
	private fun assertRoundTrips(label: String, model: PuppetModel) {
		val writeStart = System.nanoTime()
		val bytes = Uma.write(UmaModel.create(writer).withPuppet(UmaPuppetExport.puppetOf(model)))
		val writeNanos = System.nanoTime() - writeStart
		val readStart = System.nanoTime()
		val reopenedDocument = Uma.read(bytes)
		val reopened = UmaPuppetImport.modelOf(assertNotNull(reopenedDocument.puppet, "$label: the puppet entry reads back"))
		val readNanos = System.nanoTime() - readStart
		val differences = puppetStructureDifferences(model, reopened)
		assertTrue(differences.isEmpty(), "$label: ${differences.size} differences, first ${differences.take(20)}")
		assertContentEquals(bytes, Uma.write(reopenedDocument), "$label: an unedited reopen rewrites byte for byte")
		assertEvaluationsMatch(label, model, reopened)
		if (label in measuredSamples) {
			println("[Umamo][uma] $label: ${"%.2f".format(bytes.size / (1024.0 * 1024.0))} MiB puppet file, save ${writeNanos / 1_000_000} ms, open ${readNanos / 1_000_000} ms")
		}
	}

	/**
	 * Fails unless both models evaluate to the same bits at every sampled pose.
	 *
	 * @param String      label    The sample's name, for failures.
	 * @param PuppetModel expected The model before the round trip.
	 * @param PuppetModel actual   The model after it.
	 */
	private fun assertEvaluationsMatch(label: String, expected: PuppetModel, actual: PuppetModel) {
		val random = Random(20260917)
		val poses =
			listOf(
				emptyMap(),
				expected.parameters.associate { parameter -> parameter.id to parameter.min },
				expected.parameters.associate { parameter -> parameter.id to parameter.max },
			) + List(8) { expected.parameters.associate { parameter -> parameter.id to parameter.min + random.nextFloat() * (parameter.max - parameter.min) } }
		for ((poseIndex, pose) in poses.withIndex()) {
			val expectedGeometry = evaluator.evaluate(expected, pose)
			if (poseIndex == 0) {
				// Two empty evaluations would match without comparing anything, so a model that draws something must
				// evaluate to something.
				val visible = expected.visibleDrawableIds()
				val drawsSomething = expected.drawables.any { drawable -> drawable.mesh != null && drawable.id in visible }
				assertTrue(!drawsSomething || expectedGeometry.worldPositions.values.any { positions -> positions.isNotEmpty() }, "$label: the evaluation is not empty")
				evaluatedPositionCount += expectedGeometry.worldPositions.values.sumOf { positions -> positions.size.toLong() }
			}
			assertSameGeometry("$label pose $poseIndex", expectedGeometry, evaluator.evaluate(actual, pose))
		}
	}

	/**
	 * Fails unless two evaluations hold the same drawables with the same bits.
	 *
	 * @param String           label    What is compared, for failures.
	 * @param DeformedGeometry expected The original model's evaluation.
	 * @param DeformedGeometry actual   The reopened model's evaluation.
	 */
	private fun assertSameGeometry(label: String, expected: DeformedGeometry, actual: DeformedGeometry) {
		assertEquals(expected.worldPositions.keys, actual.worldPositions.keys, "$label: visible drawables")
		for ((drawableId, positions) in expected.worldPositions) {
			val reopenedPositions = actual.worldPositions.getValue(drawableId)
			assertTrue(
				positions.size == reopenedPositions.size && positions.indices.all { valueIndex -> positions[valueIndex].toRawBits() == reopenedPositions[valueIndex].toRawBits() },
				"$label: ${drawableId.raw} world positions",
			)
			assertEquals(expected.drawOrder[drawableId]?.toRawBits(), actual.drawOrder[drawableId]?.toRawBits(), "$label: ${drawableId.raw} draw order")
			assertEquals(expected.opacity[drawableId]?.toRawBits(), actual.opacity[drawableId]?.toRawBits(), "$label: ${drawableId.raw} opacity")
		}
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
		println("[Umamo][uma] ${samples.size} CMO3 samples round-trip their puppet entry and evaluate identically ($evaluatedPositionCount default-pose world floats compared)")
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
		println("[Umamo][uma] ${samples.size} MOC3 samples round-trip their puppet entry and evaluate identically ($evaluatedPositionCount default-pose world floats compared)")
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
		println("[Umamo][uma] ${samples.size} artwork samples round-trip their puppet entry and evaluate identically ($evaluatedPositionCount default-pose world floats compared)")
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