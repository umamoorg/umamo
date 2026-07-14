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
 * Corpus-gated: point `-Dpng.sample=/path/to/atlas.png` at a real 8-bit PNG (e.g. a CMO3 atlas page
 * exported to disk), or drop `.png` files under `test/corpus/png/`.  Absent → the test self-skips,
 * so CI stays green without a committed corpus.
 */
class PngDecodeParityTest {
	/**
	 * Locates a PNG sample from `-Dpng.sample` or a `test/corpus/png/` directory walked up from the
	 * working directory.
	 *
	 * @return File? The sample file, or null when none is configured.
	 */
	private fun locateSample(): File? {
		System.getProperty("png.sample")?.let { path ->
			return File(path).takeIf(File::isFile)
		}
		var directory: File? = File(System.getProperty("user.dir"))
		while (directory != null) {
			val corpus = File(directory, "test/corpus/png")
			if (corpus.isDirectory) {
				return corpus.listFiles { file -> file.extension.equals("png", ignoreCase = true) }?.minByOrNull { it.name }
			}
			directory = directory.parentFile
		}
		return null
	}

	@Test
	fun pngCodecMatchesImageIoPixelForPixel() {
		val sample = locateSample()
		if (sample == null) {
			println("no png.sample and no test/corpus/png sample; skipping PNG parity test")
			return
		}
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
	}
}
