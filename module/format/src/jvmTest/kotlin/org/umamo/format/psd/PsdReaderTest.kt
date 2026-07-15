package org.umamo.format.psd

import org.umamo.format.art.isEffectivelyVisible
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end reader test against a real .psd. Corpus-gated: it uses -Dpsd.sample=<file> when given,
 * else auto-discovers a sample under test/corpus/ (or test/) by walking up from the working
 * directory; if none is present it self-skips so CI stays green without a committed corpus.
 *
 * Asserts the structural invariants that matter to the render and re-import paths: a positive canvas
 * size, at least one decoded layer, every layer's RGBA buffer sized to its bounds, non-blank ids,
 * and - the visibility/folder work this guards - that every layer's groupPath resolves to an emitted
 * group, the order values form a 0..n-1 permutation, and the visibility cascade runs cleanly.
 */
class PsdReaderTest {
	/**
	 * Locates every PSD sample: `-Dpsd.sample` when set, else every `.psd` under `test/corpus/psd`
	 * (with `test/corpus` and `test` kept as fallbacks for a loose sample).
	 *
	 * @return List<File> The samples, empty when none are configured.
	 */
	private fun locateSamples(): List<File> {
		System.getProperty("psd.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			for (subdirectory in listOf("test/corpus/psd", "test/corpus", "test")) {
				val samples =
					File(directory, subdirectory).takeIf(File::isDirectory)
						?.listFiles { file -> file.extension.equals("psd", ignoreCase = true) }
						?.sortedBy { it.name }
						.orEmpty()
				if (samples.isNotEmpty()) {
					return samples
				}
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun readsLayersFromRealPsd() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no psd.sample and no test/corpus PSD samples; skipping PSD reader test")
			return
		}
		for (sample in samples) {
			checkSample(sample)
		}
	}

	/**
	 * Asserts the reader's structural invariants for one sample.
	 *
	 * @param File sample The `.psd` to read.
	 */
	private fun checkSample(sample: File) {
		val bytes = sample.readBytes()
		// Detection is asserted with the decode so matches() cannot rot untested (see KraReader).
		assertTrue(PsdReader.matches(bytes), "${sample.name}: detected as PSD by magic")
		val art = PsdReader.read(bytes)

		assertTrue(art.widthPx > 0 && art.heightPx > 0, "${sample.name}: positive canvas size")
		assertTrue(art.layers.isNotEmpty(), "${sample.name}: at least one layer")

		for (layer in art.layers) {
			assertTrue(layer.id.raw.isNotBlank(), "${sample.name}: layer '${layer.name}' has a stable id")
			assertTrue(
				layer.raster.rgba.size == layer.raster.width * layer.raster.height * 4,
				"${sample.name}: layer '${layer.name}' raster sized to its dimensions",
			)
		}

		// Order must be a 0..n-1 permutation (folder markers are not emitted, so emitted layers renumber).
		val orderValues = art.layers.map { layer -> layer.order }.sorted()
		assertTrue(
			orderValues == (0 until art.layers.size).toList(),
			"${sample.name}: layer order values form a 0..n-1 permutation",
		)

		// Folder surfacing: every group has a non-blank path, and every layer that names an enclosing
		// folder resolves to one - the section-divider stack and groupPath assignment must stay aligned.
		val groupPaths = art.groups.map { group -> group.path }.toSet()
		for (group in art.groups) {
			assertTrue(group.path.isNotBlank(), "${sample.name}: group '${group.name}' has a path")
		}
		for (layer in art.layers) {
			assertTrue(
				layer.groupPath.isEmpty() || layer.groupPath in groupPaths,
				"${sample.name}: layer '${layer.name}' groupPath '${layer.groupPath}' resolves to a group",
			)
			art.isEffectivelyVisible(layer)
		}
		println("checked ${sample.name}: ${art.widthPx}x${art.heightPx}, ${art.layers.size} layers, ${art.groups.size} groups")
	}
}
