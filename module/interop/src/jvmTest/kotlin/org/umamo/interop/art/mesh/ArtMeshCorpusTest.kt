package org.umamo.interop.art.mesh

import org.umamo.format.FormatRegistry
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayerKind
import org.umamo.geometry.mesh.measureQuality
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The art mesher over every raster layer of every real layered file in the local corpus (PSD, CLIP,
 * KRA), at every preset and once more at Standard with holes filled, each mesh checked by the same
 * independent oracle the synthetic tests use: orientation, manifold edges, every art pixel covered,
 * every boundary edge clear of the art, and with holes filled one disc per outline ring.  Real
 * art is where the shapes the synthetic tests never imagined live - ragged antialiasing, hair tips,
 * lace, specks of eraser residue - and it reports counts, quality, and timing per sample.
 *
 * Corpus-gated the way the readers' own tests are: every sample under `test/corpus/{psd,clip,krita}`
 * found by walking up from the working directory, self-skipping with a printed line when none exist.
 */
class ArtMeshCorpusTest {
	/** Every preset, and Standard again with holes filled. */
	private val configurations: List<Pair<String, ArtMeshSettings>> =
		ArtMeshPreset.entries.map { preset -> preset.name to preset.settings } +
			("Standard with holes filled" to ArtMeshPreset.Standard.settings.copy(fillHoles = true))

	@Test
	fun everyCorpusLayerMeshesWithCoverage() {
		val samples = locateSamples()

		if (samples.isEmpty()) {
			println("no test/corpus artwork samples; skipping the art mesh corpus test")

			return
		}

		var layerTotal = 0

		for (sample in samples) {
			layerTotal += checkSample(sample)
		}

		assertTrue(layerTotal > 0, "the corpus must hold raster layers, or this test checked nothing")
	}

	/**
	 * Meshes every raster layer of one sample in every configuration and checks each mesh.
	 *
	 * @param File sample The artwork file.
	 * @return Int The number of layers meshed at least once.
	 */
	private fun checkSample(sample: File): Int {
		val bytes = sample.readBytes()
		val codec = FormatRegistry.detect(bytes, sample.name) ?: fail("${sample.name}: not detected")
		val art = codec.read(bytes) as SourceArt
		var meshedCount = 0
		var nothingOpaqueCount = 0
		var overBudgetCount = 0
		var vertexTotal = 0
		var smallestAngle = Double.POSITIVE_INFINITY
		var sliverTotal = 0
		val startNanos = System.nanoTime()

		for (layer in art.layers.filter { it.kind == SourceLayerKind.Raster }) {
			var meshedOnce = false

			for ((configurationName, settings) in configurations) {
				val result = generateArtMesh(layer.raster, settings)
				val context = "${sample.name}: '${layer.name}' (order ${layer.order}, ${layer.raster.width}x${layer.raster.height}) at $configurationName"

				if (result.notices.any { it is ArtMeshNotice.StructureCheckFailed }) {
					fail("$context: mesh withheld - ${result.notices}")
				}

				val mesh = result.mesh

				if (mesh == null) {
					when {
						result.notices.contains(ArtMeshNotice.NothingOpaque) -> nothingOpaqueCount++
						result.notices.any { it is ArtMeshNotice.OverBudget } -> overBudgetCount++
						else -> fail("$context: no mesh and no reason - ${result.notices}")
					}

					continue
				}

				try {
					assertArtCovered(layer.raster, mesh, settings.alphaThreshold)
					assertBoundaryClearsArt(layer.raster, mesh, settings.alphaThreshold, settings.minimumMargin)
					assertTrue(mesh.vertexCount <= settings.vertexBudget, "over the vertex budget")

					if (settings.fillHoles) {
						assertEquals(result.statistics!!.outlineRingCount, eulerCharacteristic(mesh), "a mesh with its holes filled is one disc per outline ring")
					}
				} catch (failure: AssertionError) {
					throw AssertionError("$context: ${failure.message}", failure)
				}

				val quality = mesh.measureQuality()
				meshedOnce = true
				vertexTotal += mesh.vertexCount
				smallestAngle = minOf(smallestAngle, quality.minimumAngleDegrees)
				sliverTotal += quality.smallestAngleHistogram[0]
			}

			if (meshedOnce) {
				meshedCount++
			}
		}

		val elapsedMilliseconds = (System.nanoTime() - startNanos) / 1_000_000
		println(
			"checked ${sample.name}: $meshedCount layers meshed across ${configurations.size} configurations, " +
				"$nothingOpaqueCount empty, $overBudgetCount over budget, $vertexTotal vertices, " +
				"smallest angle ${"%.2f".format(smallestAngle)}, $sliverTotal triangles under 10 degrees, $elapsedMilliseconds ms",
		)

		return meshedCount
	}

	/**
	 * Every layered artwork sample in the local corpus, found by walking up to `test/corpus`.
	 *
	 * @return List<File> The samples, by name; empty without a corpus.
	 */
	private fun locateSamples(): List<File> {
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