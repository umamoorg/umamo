package org.umamo.format.art

import org.umamo.format.clip.ClipReader
import org.umamo.format.kra.KraReader
import org.umamo.format.psd.PsdReader
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Alpha-analysis invariants over every raster layer of every layered corpus sample (PSD, CLIP,
 * KRA).  Corpus-gated: auto-discovers test/corpus by walking up from the working directory and
 * self-skips when absent so CI stays green without a committed corpus.
 *
 * The exact-ring run (epsilon 0) asserts the full contract against independent rescans of the
 * pixels: null exactly when nothing is opaque (the CLIP/KRA 1x1 transparent placeholders
 * exercise this on real files), a matching recount, no opaque pixel outside the trimmed
 * bounds, and the structural contour invariants including the signed-area sum.  A second run
 * at the default epsilon asserts the simplified structure and that simplification only ever
 * drops vertices.
 */
class AlphaAnalysisCorpusTest {
	/**
	 * Locates every layered corpus sample with the reader that decodes it.
	 *
	 * @return List<Pair<File, ArtReader>> The samples, empty when no corpus is present.
	 */
	private fun locateSamples(): List<Pair<File, ArtReader>> {
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				val samples = mutableListOf<Pair<File, ArtReader>>()
				for ((subdirectory, reader) in listOf("psd" to PsdReader, "clip" to ClipReader, "krita" to KraReader)) {
					File(corpus, subdirectory).listFiles { file -> file.isFile }
						?.sortedBy { file -> file.name }
						?.forEach { file -> samples.add(file to reader) }
				}
				return samples
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun analysisInvariantsHoldOnCorpusLayers() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no test/corpus directory; skipping alpha-analysis corpus test")
			return
		}
		for ((sample, reader) in samples) {
			checkSample(sample, reader)
		}
	}

	/**
	 * Runs the analysis over every raster layer of one sample and asserts the invariants.
	 *
	 * @param File sample The layered source file to read.
	 * @param ArtReader reader The reader that decodes it.
	 */
	private fun checkSample(sample: File, reader: ArtReader) {
		val art = reader.read(sample.readBytes())
		var analyzedLayerCount = 0
		var emptyLayerCount = 0
		val startNanos = System.nanoTime()
		for (layer in art.layers) {
			if (layer.kind != SourceLayerKind.Raster) {
				continue
			}
			val raster = layer.raster
			val expectedCount = countOpaquePixels(raster, DEFAULT_ALPHA_THRESHOLD)
			val exact = raster.analyzeAlpha(contourEpsilon = 0f)
			if (exact == null) {
				assertEquals(
					0,
					expectedCount,
					"${sample.name}: '${layer.name}' null analysis only for fully-transparent rasters",
				)
				emptyLayerCount++
				continue
			}
			analyzedLayerCount++
			assertTrue(expectedCount > 0, "${sample.name}: '${layer.name}' non-null analysis has opaque pixels")
			assertEquals(
				expectedCount,
				exact.opaquePixelCount,
				"${sample.name}: '${layer.name}' count matches an independent recount",
			)
			val bounds = exact.opaqueBounds
			assertTrue(
				bounds.left >= 0 &&
					bounds.top >= 0 &&
					bounds.left + bounds.width <= raster.width &&
					bounds.top + bounds.height <= raster.height,
				"${sample.name}: '${layer.name}' trimmed bounds $bounds within raster ${raster.width}x${raster.height}",
			)
			assertNoOpaquePixelOutside(raster, bounds, DEFAULT_ALPHA_THRESHOLD)
			assertAnalysisInvariants(exact, exactRings = true)

			val simplified =
				assertNotNull(
					raster.analyzeAlpha(),
					"${sample.name}: '${layer.name}' simplified run is non-null too",
				)
			assertAnalysisInvariants(simplified, exactRings = false)
			assertEquals(
				exact.contours.size,
				simplified.contours.size,
				"${sample.name}: '${layer.name}' simplification never adds or drops contours",
			)
			for (contourIndex in exact.contours.indices) {
				assertSimplifiedIsVertexSubset(
					exact.contours[contourIndex].points,
					simplified.contours[contourIndex].points,
					"${sample.name}: '${layer.name}' contour $contourIndex",
				)
			}
		}
		val elapsedMillis = (System.nanoTime() - startNanos) / 1_000_000
		println(
			"checked ${sample.name}: $analyzedLayerCount layers analyzed, " +
				"$emptyLayerCount empty, $elapsedMillis ms",
		)
	}
}
