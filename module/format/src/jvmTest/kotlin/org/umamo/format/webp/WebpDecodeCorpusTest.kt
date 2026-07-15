package org.umamo.format.webp

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corpus-gated end-to-end WebP decode: every `.webp` under `test/corpus/` (or the single file named by
 * `-Dwebp.sample=/path/to.webp`) must decode to a correctly sized RGBA buffer.  The corpus is not
 * committed, so this self-skips when absent; the committed [WebpVp8lDecodeTest] fixtures carry the
 * per-feature regression coverage.  Lossy VP8 files are out of scope and rejected, so pick lossless
 * (VP8L) samples.
 */
class WebpDecodeCorpusTest {
	/**
	 * Locates every WebP sample: `-Dwebp.sample` when set, else the `test/corpus` directory (and its
	 * `webp` subdirectory) found by walking up from the working directory.
	 *
	 * @return List<File> The sample files, empty when none are configured.
	 */
	private fun locateSamples(): List<File> {
		System.getProperty("webp.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return listOf(corpus, File(corpus, "webp"))
					.filter(File::isDirectory)
					.flatMap { folder -> folder.listFiles { file -> file.isFile && file.extension.equals("webp", ignoreCase = true) }?.toList() ?: emptyList() }
					.sortedBy { it.name }
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun decodesEveryCorpusWebp() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no webp.sample and no test/corpus WebPs; skipping WebP corpus test")
			return
		}
		for (sample in samples) {
			val bytes = sample.readBytes()
			assertTrue(WebPReader.matches(bytes), "${sample.name}: detected as WebP by magic")
			val decoded = WebPReader.read(bytes)
			assertTrue(decoded.width > 0 && decoded.height > 0, "${sample.name}: positive dimensions")
			assertEquals(decoded.width * decoded.height * 4, decoded.rgba.size, "${sample.name}: RGBA buffer sized to the image")
			println("decoded ${sample.name}: ${decoded.width}x${decoded.height}")
		}
	}
}
