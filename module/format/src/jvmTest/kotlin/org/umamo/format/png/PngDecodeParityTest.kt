package org.umamo.format.png

import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * The art-sourcing roadmap's named Phase-A validation: decode a real PNG through the pure-Kotlin
 * [PngCodec] and through javax.imageio (the decoder the retired `decodePngToRgba` used), and assert
 * the straight-alpha RGBA byte streams are pixel-identical.  This is the drop-in-replacement proof
 * for the CMO3 atlas path.
 *
 * Corpus-gated: point `-Dpng.sample=/path/to/atlas.png` at a real 8-bit PNG, or drop `.png` files
 * anywhere under `test/corpus/` — the atlas pages that ship beside a model's `.moc3` are exactly the
 * intended sample.  Absent → the test self-skips, so CI stays green without a committed corpus.
 */
class PngDecodeParityTest {
	/**
	 * Locates every PNG sample: `-Dpng.sample` when set, else every `.png` anywhere under
	 * `test/corpus`.
	 *
	 * Searches the whole corpus rather than one `png/` directory because the PNGs worth this test are
	 * the atlas pages that sit beside the model they belong to, not loose files in a per-format folder.
	 *
	 * @return List<File> The samples, empty when none are configured.
	 */
	private fun locateSamples(): List<File> {
		System.getProperty("png.sample")?.let { path ->
			return listOfNotNull(File(path).takeIf(File::isFile))
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus")
			if (corpus.isDirectory) {
				return corpus.walkTopDown()
					.filter { candidate -> candidate.isFile && candidate.extension.equals("png", ignoreCase = true) }
					.sortedBy { it.path }
					.toList()
			}
			directory = directory.parentFile
		}
		return emptyList()
	}

	@Test
	fun pngCodecMatchesImageIoPixelForPixel() {
		val samples = locateSamples()
		if (samples.isEmpty()) {
			println("no png.sample and no test/corpus PNGs; skipping PNG parity test")
			return
		}
		for (sample in samples) {
			checkSample(sample)
		}
	}

	/**
	 * Asserts one PNG decodes identically through [PngCodec] and javax.imageio.
	 *
	 * @param File sample The `.png` to compare.
	 */
	private fun checkSample(sample: File) {
		val bytes = sample.readBytes()

		val reference = ImageIO.read(ByteArrayInputStream(bytes))
		if (reference == null) {
			println("${sample.name}: ImageIO could not decode; skipping parity test")
			return
		}
		val width = reference.width
		val height = reference.height
		val argb = IntArray(width * height)
		reference.getRGB(0, 0, width, height, argb, 0, width)
		val expected = ByteArray(width * height * 4)
		for (pixelIndex in argb.indices) {
			val packed = argb[pixelIndex]
			val base = pixelIndex * 4
			expected[base] = (packed ushr 16).toByte()
			expected[base + 1] = (packed ushr 8).toByte()
			expected[base + 2] = packed.toByte()
			expected[base + 3] = (packed ushr 24).toByte()
		}

		val decoded = PngCodec.read(bytes)
		assertContentEquals(expected, decoded.rgba, "${sample.name}: PngCodec decode must match javax.imageio")
		println("parity ok ${sample.name}: ${decoded.width}x${decoded.height}")
	}
}
