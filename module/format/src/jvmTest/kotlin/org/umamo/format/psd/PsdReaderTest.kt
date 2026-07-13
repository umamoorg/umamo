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
	private fun locateSample(): File? {
		System.getProperty("psd.sample")?.let { path ->
			return File(path).takeIf(File::isFile)
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			for (subdirectory in listOf("test/corpus", "test")) {
				val corpus = File(directory, subdirectory)
				val sample =
					corpus.takeIf(File::isDirectory)
						?.listFiles { file -> file.extension.equals("psd", ignoreCase = true) }
						?.minByOrNull { it.name }
				if (sample != null) {
					return sample
				}
			}
			directory = directory.parentFile
		}
		return null
	}

	@Test
	fun readsLayersFromRealPsd() {
		val sample = locateSample()
		if (sample == null) {
			println("no psd.sample and no test/corpus PSD sample; skipping PSD reader test")
			return
		}

		val art = PsdReader.read(sample.readBytes())

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
	}
}
