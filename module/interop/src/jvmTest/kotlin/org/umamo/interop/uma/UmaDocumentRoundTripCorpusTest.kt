package org.umamo.interop.uma

import org.umamo.format.FormatRegistry
import org.umamo.format.art.SourceArt
import org.umamo.format.binary.contentHashOf
import org.umamo.format.cmo3.Cmo3
import org.umamo.format.cmo3.model.custom.CModelSource
import org.umamo.format.moc3.Moc3
import org.umamo.format.png.PngCodec
import org.umamo.format.raster.RasterImage
import org.umamo.format.uma.Uma
import org.umamo.format.uma.UmaModel
import org.umamo.format.uma.UmaWriterInfo
import org.umamo.format.uma.textures.UmaPixelSource
import org.umamo.format.uma.textures.UmaRenderPagePixels
import org.umamo.interop.AtlasPageSet
import org.umamo.interop.art.ArtSourceDescriptor
import org.umamo.interop.art.SourceArtImport
import org.umamo.interop.cmo3.Cmo3Import
import org.umamo.interop.cmo3.cmo3AtlasPages
import org.umamo.interop.moc3.Moc3Sidecars
import org.umamo.interop.moc3.import.Moc3Import
import org.umamo.interop.moc3.import.moc3AtlasPages
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.render.eval.DeformedGeometry
import org.umamo.runtime.model.AtlasTileId
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
 * The native format's whole-document gate over the corpus (docs/format/UMA.md §4-§6): every CMO3, every MOC3,
 * and every layered artwork file imports, saves as UMA with its pixels, reopens, and compares identical and
 * bit-exact across the puppet, the atlas, and the linked source art; every tile's PNG comes back byte for byte,
 * and each drawable samples the same image bytes the source format's loader gave it; the saved file rewrites byte
 * for byte; and the CPU evaluation of the reopened model matches the original's bit for bit at the default
 * pose, the all-minimum and all-maximum poses, and eight seeded random ones.  The models named for measurement
 * print their save and open costs.
 *
 * Corpus-gated: `cmo3.probe` (every corpus .cmo3 by default), `moc3.samples` (the moc3 corpus directory, each
 * moc's pages read beside it through its model3.json), and the PSD / CLIP / KRA files under test/corpus found by
 * walking up from the working directory.  Each format self-skips with a printed line when its samples are absent.
 */
class UmaDocumentRoundTripCorpusTest {
	private val writer = UmaWriterInfo("Umamo", "corpus-test")

	private val evaluator = CpuDeformationEvaluator()

	// World position floats the default-pose evaluations produced, so a run can show it compared real geometry.
	private var evaluatedPositionCount = 0L

	// Tile and page PNGs compared byte for byte after the reopen, so a run can show it compared real pixels.
	private var comparedPngCount = 0L

	// Every sample's failure, so one run reports the whole corpus rather than its first broken sample.
	private val failures = ArrayList<String>()

	// The models whose save and open costs are recorded in the design plan.
	private val measuredSamples = setOf("EricaTamamo.cmo3", "modelF.cmo3", "EricaTamamo.moc3", "modelF.moc3")

	/**
	 * Saves [model] as a whole document, reopens it, and fails on any difference, an unstable rewrite, a tile that
	 * did not come back, a drawable that samples other pixels, or an evaluation that no longer matches.
	 *
	 * @param String         label         The sample's name, for failures.
	 * @param PuppetModel    model         The imported model.
	 * @param UmaPixelSource pixels        The pixels the save writes.
	 * @param AtlasPageSet?  expectedPages The page set the source format's loader builds, or null when the pages
	 *   derive.
	 */
	private fun assertRoundTrips(label: String, model: PuppetModel, pixels: UmaPixelSource, expectedPages: AtlasPageSet?) {
		val writeStart = System.nanoTime()
		val bytes = Uma.write(UmaDocumentBridge.documentOf(UmaModel.create(writer), model, pixels))
		val writeNanos = System.nanoTime() - writeStart
		val readStart = System.nanoTime()
		val document = Uma.read(bytes)
		val reopened = UmaDocumentBridge.modelOf(document)
		val readNanos = System.nanoTime() - readStart
		val differences = documentDifferences(model, reopened)
		assertTrue(differences.isEmpty(), "$label: ${differences.size} differences, first ${differences.take(20)}")
		assertContentEquals(bytes, Uma.write(document), "$label: an unedited reopen rewrites byte for byte")

		val documentPages = UmaDocumentBridge.pagesOf(document)
		for (tile in model.atlas.tiles) {
			assertContentEquals(pixels.tile(tile.id.raw), documentPages.tilePng(tile.id), "$label: tile ${tile.id.raw}'s PNG")
			comparedPngCount++
		}
		if (expectedPages == null) {
			assertEquals(null, documentPages.pageSet, "$label: derived pages store no page set")
		} else {
			assertSamePagesPerDrawable(label, expectedPages, assertNotNull(documentPages.pageSet, "$label: the render pages come back"))
		}
		assertEvaluationsMatch(label, model, reopened)
		if (label in measuredSamples) {
			println("[Umamo][uma] $label: ${"%.2f".format(bytes.size / (1024.0 * 1024.0))} MiB document, save ${writeNanos / 1_000_000} ms, open ${readNanos / 1_000_000} ms")
		}
	}

	/**
	 * Fails unless every drawable the source format's page set maps samples a page with the same bytes after the
	 * reopen, and the reopened set maps no drawable the source did not.
	 *
	 * @param String       label    The sample's name, for failures.
	 * @param AtlasPageSet expected The source format's page set.
	 * @param AtlasPageSet actual   The reopened document's.
	 */
	private fun assertSamePagesPerDrawable(label: String, expected: AtlasPageSet, actual: AtlasPageSet) {
		comparedPngCount += expected.pageBytes.size
		val mismatched =
			expected.atlasIndexByDrawableId.filter { (drawableId, pageIndex) ->
				val reopenedIndex = actual.atlasIndexByDrawableId[drawableId]
				reopenedIndex == null || !expected.pageBytes[pageIndex].contentEquals(actual.pageBytes[reopenedIndex])
			}.keys
		assertTrue(mismatched.isEmpty(), "$label: ${mismatched.size} drawables sample other pixels after the reopen, first ${mismatched.take(10)}")
		val extra = actual.atlasIndexByDrawableId.keys - expected.atlasIndexByDrawableId.keys
		assertTrue(extra.isEmpty(), "$label: ${extra.size} drawables sample a page they did not before, first ${extra.take(10)}")
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
	 * Runs [check] for one sample, recording its failure rather than stopping the corpus.
	 *
	 * @param String   label The sample's name.
	 * @param Function check The sample's round trip.
	 */
	private fun recording(label: String, check: () -> Unit) {
		try {
			check()
		} catch (failure: AssertionError) {
			failures += "$label: ${failure.message}"
		} catch (failure: Exception) {
			failures += "$label: ${failure::class.simpleName}: ${failure.message}"
		}
	}

	/**
	 * Fails with every recorded sample failure, one per line.
	 *
	 * @param String corpus The corpus's name.
	 */
	private fun assertNoFailures(corpus: String) {
		assertTrue(failures.isEmpty(), "${failures.size} $corpus samples failed:\n${failures.joinToString("\n") { failure -> failure.take(600) }}")
	}

	/**
	 * Every corpus CMO3: tiles from its model images, and the images its drawables sample stored as its render pages.
	 */
	@Test
	fun cmo3Corpus() {
		val samples = System.getProperty("cmo3.probe")?.split(',')?.map(::File)?.filter { file -> file.isFile }.orEmpty()
		if (samples.isEmpty()) {
			println("no cmo3.probe samples; skipping the CMO3 document round trip")
			return
		}
		for (sample in samples) {
			recording(sample.name) {
				val cmo3 = Cmo3.read(sample)
				val root = cmo3.root as? CModelSource ?: error("${sample.name}: root is not a CModelSource")
				val imported = Cmo3Import.importModelSource(root)
				val resourceByTile = imported.atlasIngest.imageResourceByTile
				val loaderPages = cmo3AtlasPages(root, cmo3::extractLayerPng)
				val pixels = UmaPixelSource({ tileId -> resourceByTile[AtlasTileId(tileId)]?.let(cmo3::extractLayerPng) }, UmaRenderPagePixels.Stored(loaderPages.pageBytes, loaderPages.atlasIndexByDrawableId), null)
				assertRoundTrips(sample.name, imported.puppet, pixels, loaderPages)
			}
		}
		assertNoFailures("CMO3")
		println(
			"[Umamo][uma] ${samples.size} CMO3 samples round-trip as documents ($comparedPngCount PNGs and " +
				"$evaluatedPositionCount default-pose world floats compared)",
		)
	}

	/**
	 * Every corpus MOC3: no tiles, its pages stored as render pages from the files its model3.json names when they are
	 * there.
	 */
	@Test
	fun moc3Corpus() {
		val samples =
			System.getProperty("moc3.samples")?.let(::File)?.takeIf { directory -> directory.isDirectory }
				?.walkTopDown()?.filter { file -> file.isFile && file.extension == "moc3" }?.sortedBy { file -> file.path }?.toList().orEmpty()
		if (samples.isEmpty()) {
			println("no moc3.samples; skipping the MOC3 document round trip")
			return
		}
		var pagedSamples = 0
		for (sample in samples) {
			recording(sample.name) {
				val mocDocument = Moc3.read(sample.readBytes())
				val model = Moc3Import.fromMocDocument(mocDocument, null)
				val manifest = File(sample.parentFile, "${Moc3Sidecars.basenameFor(sample.name)}.model3.json").takeIf { file -> file.isFile }?.let { file -> Moc3.readModel3(file.readText()) }
				val pageFiles = manifest?.fileReferences?.textures?.map { reference -> File(sample.parentFile, reference) }.orEmpty()
				if (pageFiles.isEmpty() || pageFiles.any { file -> !file.isFile }) {
					println("[Umamo][uma] ${sample.name}: its pages are not beside it, so only the model round-trips")
					assertRoundTrips(sample.name, model, UmaPixelSource({ null }, UmaRenderPagePixels.Derived, null), null)
					return@recording
				}
				pagedSamples++
				val loaderPages = moc3AtlasPages(mocDocument, pageFiles.map { file -> file.readBytes() })
				val pixels = UmaPixelSource({ null }, UmaRenderPagePixels.Stored(loaderPages.pageBytes, loaderPages.atlasIndexByDrawableId), null)
				assertRoundTrips(sample.name, model, pixels, loaderPages)
			}
		}
		assertNoFailures("MOC3")
		assertTrue(pagedSamples > 0, "no MOC3 sample had its pages beside it, so the page leg compared nothing")
		println("[Umamo][uma] ${samples.size} MOC3 samples round-trip as documents ($pagedSamples with pages; $comparedPngCount PNGs and $evaluatedPositionCount default-pose world floats compared)")
	}

	/**
	 * Every layered artwork file in the corpus, imported as a fresh unpacked rig with its hashes.
	 */
	@Test
	fun artworkCorpus() {
		val samples = locateArtworkSamples()
		if (samples.isEmpty()) {
			println("no test/corpus artwork samples; skipping the artwork document round trip")
			return
		}
		for (sample in samples) {
			recording(sample.name) {
				val bytes = sample.readBytes()
				val codec = assertNotNull(FormatRegistry.detect(bytes, sample.name), "${sample.name}: detected")
				val art = codec.read(bytes) as SourceArt
				val result = SourceArtImport.fromSourceArt(art, ArtSourceDescriptor(sample.name, sample.path, codec.kind.extension, contentHashOf(bytes), sample.lastModified()))
				val pngByTile = result.rasterByTile.mapKeys { (tileId, _) -> tileId.raw }.mapValues { (_, raster) -> PngCodec.write(RasterImage(raster.width, raster.height, raster.rgba)) }
				assertTrue(result.puppet.sources.single().layers.isNotEmpty(), "${sample.name}: the inventory is not empty")
				assertRoundTrips(sample.name, result.puppet, UmaPixelSource({ tileId -> pngByTile[tileId] }, UmaRenderPagePixels.Derived, null), null)
			}
		}
		assertNoFailures("artwork")
		println("[Umamo][uma] ${samples.size} artwork samples round-trip as documents ($comparedPngCount PNGs and $evaluatedPositionCount default-pose world floats compared)")
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