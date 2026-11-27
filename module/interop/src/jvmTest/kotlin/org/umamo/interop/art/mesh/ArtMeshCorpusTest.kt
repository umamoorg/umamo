package org.umamo.interop.art.mesh

import org.umamo.format.FormatRegistry
import org.umamo.format.art.SourceArt
import org.umamo.format.art.SourceLayerKind
import org.umamo.geometry.mesh.measureQuality
import org.umamo.testkit.skipTest
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The art mesher over every raster layer of every real layered file in the local corpus (PSD, CLIP,
 * KRA), at every preset and once more at Standard with holes filled, each mesh checked by the same
 * independent oracle the synthetic tests use: orientation, manifold edges, every art pixel covered,
 * every boundary edge clear of the art, and with holes filled one disc per outline ring.  Every
 * preset refines toward its minimum angle, so each mesh is also held to refinement's promise: what
 * stays below the angle is what refinement could not help, unless the vertex budget ran out.  Real
 * art is where the shapes the synthetic tests never imagined live - ragged antialiasing, hair tips,
 * lace, specks of eraser residue - and it reports counts, quality (smallest angles in 5-degree
 * buckets), the vertices refinement added, and timing per sample.
 *
 * Corpus-gated the way the readers' own tests are: every sample under `test/corpus/{psd,clip,krita}`
 * found by walking up from the working directory, self-skipping when none exist.
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
			skipTest("no test/corpus artwork samples; skipping the art mesh corpus test")
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
		var steinerTotal = 0
		var unfixableTotal = 0
		var budgetReachedCount = 0
		var smallestAngle = Double.POSITIVE_INFINITY
		val histogram = IntArray(HISTOGRAM_BUCKETS)
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
					assertValidArtMesh(layer.raster, settings, result)
				} catch (failure: AssertionError) {
					throw AssertionError("$context: ${failure.message}", failure)
				}

				val quality = mesh.measureQuality(HISTOGRAM_BUCKET_DEGREES)
				val statistics = result.statistics!!
				val budgetReached = result.notices.any { notice -> notice is ArtMeshNotice.RefinementBudgetReached }
				// The refiner decides by sine squared and acos can land a hair away from it, so the count is held to a quarter degree either side.
				val fineQuality = mesh.measureQuality(QUALITY_CHECK_BUCKET_DEGREES)
				val fewest = fineQuality.countBelow(settings.minimumAngle - QUALITY_CHECK_BUCKET_DEGREES)
				val most = fineQuality.countBelow(settings.minimumAngle + QUALITY_CHECK_BUCKET_DEGREES)
				assertTrue(statistics.underTargetCount in fewest..most, "$context: the reported count ${statistics.underTargetCount} matches the mesh ($fewest to $most below ${settings.minimumAngle} degrees)")

				if (!budgetReached) {
					assertEquals(statistics.underTargetCount, statistics.unfixableCount, "$context: everything below the minimum angle is beyond refinement")
				}

				meshedOnce = true
				vertexTotal += mesh.vertexCount
				steinerTotal += statistics.steinerVertexCount
				unfixableTotal += statistics.unfixableCount
				budgetReachedCount += if (budgetReached) 1 else 0
				smallestAngle = minOf(smallestAngle, quality.minimumAngleDegrees)

				for (bucket in histogram.indices) {
					histogram[bucket] += quality.smallestAngleHistogram[bucket]
				}
			}

			if (meshedOnce) {
				meshedCount++
			}
		}

		val elapsedMilliseconds = (System.nanoTime() - startNanos) / 1_000_000
		println(
			"checked ${sample.name}: $meshedCount layers meshed across ${configurations.size} configurations, " +
				"$nothingOpaqueCount empty, $overBudgetCount over budget, $vertexTotal vertices ($steinerTotal added by refinement), " +
				"smallest angle ${"%.2f".format(smallestAngle)}, $unfixableTotal triangles refinement could not help, " +
				"$budgetReachedCount meshes out of budget for it, $elapsedMilliseconds ms",
		)
		println("  smallest angles: " + histogram.indices.joinToString(", ") { bucket -> "[${bucket * 5}, ${(bucket + 1) * 5}) ${histogram[bucket]}" })

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

	private companion object {
		/** The histogram's bucket width, degrees. */
		const val HISTOGRAM_BUCKET_DEGREES = 5.0

		/** The band, either side of a preset's minimum angle, within which the reported count must land; exact in binary, so the bucket edges are too. */
		const val QUALITY_CHECK_BUCKET_DEGREES = 0.25

		/** Its bucket count, up to 60 degrees. */
		const val HISTOGRAM_BUCKETS = 12
	}
}