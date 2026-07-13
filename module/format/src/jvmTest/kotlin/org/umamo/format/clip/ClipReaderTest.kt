package org.umamo.format.clip

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * End-to-end reader test against a real .clip. Corpus-gated: it uses -Dclip.sample=<file> when given,
 * else auto-discovers a sample under test/corpus/clip/ by walking up from the working directory; if
 * neither is present it self-skips so CI stays green without a committed corpus.
 *
 * Asserts the structural invariants that matter to the render and re-import paths: a positive canvas
 * size, at least one layer, stable non-blank ids, positive bounds, an RGBA buffer sized to each
 * layer's dimensions, a top-most-first order permutation, and - when the document has decodable
 * raster layers - at least one with real (non-transparent) pixels.
 *
 * The clipformat prototype repo holds the exhaustive feature-specific tests (blend modes, clipping,
 * masks, grayscale/monochrome, layer kinds, visibility); here a single structural test guards the
 * ported reader against any real .clip the developer points it at.
 */
class ClipReaderTest {
	private fun locateSample(): File? {
		System.getProperty("clip.sample")?.let { path ->
			return File(path).takeIf(File::isFile)
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus/clip")
			if (corpus.isDirectory) {
				return corpus.listFiles { file -> file.extension.equals("clip", ignoreCase = true) }
					?.minByOrNull { it.name }
			}
			directory = directory.parentFile
		}
		return null
	}

	@Test
	fun readsLayerTreeFromRealClip() {
		val sample = locateSample()
		if (sample == null) {
			println("no clip.sample and no test/corpus/clip sample; skipping CLIP reader test")
			return
		}

		val art = ClipReader.read(sample.readBytes())

		assertTrue(art.widthPx > 0 && art.heightPx > 0, "${sample.name}: positive canvas size")
		assertTrue(art.layers.isNotEmpty(), "${sample.name}: at least one layer")

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

		val orderValues = art.layers.map { layer -> layer.order }.sorted()
		assertTrue(
			orderValues == (0 until art.layers.size).toList(),
			"${sample.name}: layer order values form a 0..n-1 permutation",
		)

		// If any layer decoded to a real raster (not a 1x1 placeholder), at least one should carry pixels.
		val decodedRasters = art.layers.filter { layer -> layer.raster.width > 1 && layer.raster.height > 1 }
		if (decodedRasters.isNotEmpty()) {
			val anyVisible =
				decodedRasters.any { layer ->
					var byteIndex = 3
					var hasAlpha = false
					while (byteIndex < layer.raster.rgba.size) {
						if (layer.raster.rgba[byteIndex].toInt() != 0) {
							hasAlpha = true
							break
						}
						byteIndex += 4
					}
					hasAlpha
				}
			assertTrue(anyVisible, "${sample.name}: a decoded raster layer has non-transparent pixels")
		}
	}
}
