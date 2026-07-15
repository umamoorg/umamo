package org.umamo.format.tiff

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Corpus-gated end-to-end TIFF decode: every `.tif`/`.tiff` under `test/corpus/` (or the single file
 * named by `-Dtiff.sample=/path/to.tiff`) must decode to a correctly sized RGBA buffer.  The corpus is
 * not committed (it holds multi-hundred-megabyte artwork exports), so this self-skips when absent and
 * CI stays green; the committed [TiffDecodeTest] and [TiffCcittDecodeTest] fixtures carry the
 * per-feature regression coverage.
 */
class TiffDecodeCorpusTest {
	/**
	 * Locates every TIFF sample: `-Dtiff.sample` when set, else the `test/corpus` directory (and its
	 * `tiff` subdirectory) found by walking up from the working directory.
	 *
	 * @return List<File> The sample files, empty when none are configured.
	 */
	private fun locateSamples(): List<File> {
		System.getProperty("tiff.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return listOf(corpus, File(corpus, "tiff"))
					.filter(File::isDirectory)
					.flatMap { folder -> folder.listFiles { file -> file.isFile && isTiff(file) }?.toList() ?: emptyList() }
					.sortedBy { it.name }
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	/**
	 * Whether [file] carries a TIFF extension.
	 *
	 * @param File file The candidate file.
	 * @return Boolean True for `.tif` / `.tiff`.
	 */
	private fun isTiff(file: File): Boolean = file.extension.equals("tiff", ignoreCase = true) || file.extension.equals("tif", ignoreCase = true)

	@Test
	fun decodesEveryCorpusTiff() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no tiff.sample and no test/corpus TIFFs; skipping TIFF corpus test")
			return
		}
		for (sample in samples) {
			val bytes = sample.readBytes()
			assertTrue(TiffReader.matches(bytes), "${sample.name}: detected as TIFF by magic")
			val decoded = TiffReader.read(bytes)
			assertTrue(decoded.width > 0 && decoded.height > 0, "${sample.name}: positive dimensions")
			assertEquals(decoded.width * decoded.height * 4, decoded.rgba.size, "${sample.name}: RGBA buffer sized to the image")
			println("decoded ${sample.name}: ${decoded.width}x${decoded.height}")
		}
	}
}
