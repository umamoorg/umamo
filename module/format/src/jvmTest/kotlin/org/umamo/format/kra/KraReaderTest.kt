package org.umamo.format.kra

import org.umamo.format.art.isEffectivelyVisible
import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end reader test against a real .kra. Corpus-gated: it uses -Dkra.sample=<file> when given,
 * else auto-discovers a sample under test/corpus/krita/ by walking up from the working directory; if
 * neither is present it self-skips so CI stays green without a committed corpus.
 *
 * Asserts the structural invariants that matter to the render and re-import paths: a positive canvas
 * size, at least one decoded paint layer, every layer's RGBA buffer sized to its bounds, stable
 * non-blank ids, and some actual pixel content.
 */
class KraReaderTest {
	private fun locateSample(): File? {
		System.getProperty("kra.sample")?.let { path ->
			return File(path).takeIf(File::isFile)
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus/krita")
			if (corpus.isDirectory) {
				return corpus.listFiles { file -> file.extension.equals("kra", ignoreCase = true) }
					?.minByOrNull { it.name }
			}
			directory = directory.parentFile
		}
		return null
	}

	@Test
	fun readsLayersFromRealKra() {
		val sample = locateSample()
		if (sample == null) {
			println("no kra.sample and no test/corpus/krita sample; skipping KRA reader test")
			return
		}

		val art = KraReader.read(sample.readBytes())

		assertTrue(art.widthPx > 0 && art.heightPx > 0, "${sample.name}: positive canvas size")
		assertTrue(art.layers.isNotEmpty(), "${sample.name}: at least one paint layer")

		for (layer in art.layers) {
			assertTrue(layer.id.raw.isNotBlank(), "${sample.name}: layer '${layer.name}' has a stable id")
			assertTrue(
				layer.bounds.width > 0 && layer.bounds.height > 0,
				"${sample.name}: layer '${layer.name}' has positive bounds",
			)
			assertTrue(
				layer.raster.rgba.size == layer.raster.width * layer.raster.height * 4,
				"${sample.name}: layer '${layer.name}' raster sized to its dimensions",
			)
		}

		// At least one layer should carry visible pixels (a non-zero alpha somewhere) - proves the
		// tile, LZF, and color pipeline actually produced content, not just transparent placeholders.
		val anyVisible =
			art.layers.any { layer ->
				var hasAlpha = false
				var byteIndex = 3
				while (byteIndex < layer.raster.rgba.size) {
					if (layer.raster.rgba[byteIndex].toInt() != 0) {
						hasAlpha = true
						break
					}
					byteIndex += 4
				}
				hasAlpha
			}
		assertTrue(anyVisible, "${sample.name}: at least one layer has non-transparent pixels")

		// Group surfacing: every emitted folder has a non-blank path, and every layer that claims an
		// enclosing folder resolves to one - proving the group list and groupPath wiring stay aligned.
		val groupPaths = art.groups.map { group -> group.path }.toSet()
		for (group in art.groups) {
			assertTrue(group.path.isNotBlank(), "${sample.name}: group '${group.name}' has a path")
		}
		for (layer in art.layers) {
			assertTrue(
				layer.groupPath.isEmpty() || layer.groupPath in groupPaths,
				"${sample.name}: layer '${layer.name}' groupPath '${layer.groupPath}' resolves to a group",
			)
			// The cascade helper must run cleanly over real data (no path/parse surprises).
			art.isEffectivelyVisible(layer)
		}
	}
}
