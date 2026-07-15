package org.umamo.format.jpeg

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corpus-gated end-to-end JPEG decode: every `.jpg`/`.jpeg` under `test/corpus/` (or the single file
 * named by `-Djpeg.sample=/path/to.jpg`) must decode to a correctly sized RGBA buffer.  The corpus is
 * not committed, so this self-skips when absent; the committed [JpegDecodeTest] fixtures carry the
 * per-feature regression coverage.  Progressive JPEGs are out of scope, so pick baseline samples.
 */
class JpegDecodeCorpusTest {
	/**
	 * Locates every JPEG sample: `-Djpeg.sample` when set, else the `test/corpus` directory (and its
	 * `jpeg` subdirectory) found by walking up from the working directory.
	 *
	 * @return List<File> The sample files, empty when none are configured.
	 */
	private fun locateSamples(): List<File> {
		System.getProperty("jpeg.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return listOf(corpus, File(corpus, "jpeg"))
					.filter(File::isDirectory)
					.flatMap { folder -> folder.listFiles { file -> file.isFile && isJpeg(file) }?.toList() ?: emptyList() }
					.sortedBy { it.name }
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	/**
	 * Whether [file] carries a JPEG extension.
	 *
	 * @param File file The candidate file.
	 * @return Boolean True for `.jpg` / `.jpeg`.
	 */
	private fun isJpeg(file: File): Boolean = file.extension.equals("jpg", ignoreCase = true) || file.extension.equals("jpeg", ignoreCase = true)

	@Test
	fun decodesEveryCorpusJpeg() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no jpeg.sample and no test/corpus JPEGs; skipping JPEG corpus test")
			return
		}
		for (sample in samples) {
			val bytes = sample.readBytes()
			assertTrue(JpegReader.matches(bytes), "${sample.name}: detected as JPEG by magic")
			val decoded = JpegReader.read(bytes)
			assertTrue(decoded.width > 0 && decoded.height > 0, "${sample.name}: positive dimensions")
			assertEquals(decoded.width * decoded.height * 4, decoded.rgba.size, "${sample.name}: RGBA buffer sized to the image")
			println("decoded ${sample.name}: ${decoded.width}x${decoded.height}")
		}
	}
}
