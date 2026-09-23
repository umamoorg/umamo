package org.umamo.format.png

import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import java.util.Arrays
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The art-sourcing roadmap's named Phase-A validation: decode a real PNG through the pure-Kotlin
 * [PngCodec] and through javax.imageio (the decoder the retired `decodePngToRgba` used), and assert
 * the straight-alpha RGBA byte streams are pixel-identical.  This is the drop-in-replacement proof
 * for the CMO3 atlas path.
 *
 * The one reference serves every bit depth: ImageIO's ColorModel reduces a 16-bit sample with the same
 * linear equation PngCodec applies (PNG spec §13.12), so a 16-bit file is compared exactly too.
 *
 * Corpus-gated: point `-Dpng.sample=/path/to/atlas.png` at a real PNG, or drop `.png` files anywhere
 * under `test/corpus/` — the atlas pages that ship beside a model's `.moc3` are exactly the intended
 * sample.  Absent → the test self-skips, so CI stays green without a committed corpus.
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
		val failures = samples.mapNotNull(::checkSample)
		assertTrue(failures.isEmpty(), "PngCodec decode must match javax.imageio:\n" + failures.joinToString("\n"))
	}

	/**
	 * Compares one PNG's decode through [PngCodec] against javax.imageio, one row at a time.
	 *
	 * The reference is built one row at a time, so a 16384-square atlas never holds more than the two
	 * decoded images at once (1 GiB each).  A whole-image reference copy would add two more, past the
	 * test heap.
	 *
	 * @param File sample The `.png` to compare.
	 * @return String? The mismatch description, or null when the decodes agree or ImageIO cannot read the file.
	 */
	private fun checkSample(sample: File): String? {
		val bytes = sample.readBytes()
		val decoded = PngCodec.read(bytes)
		val reference = ImageIO.read(ByteArrayInputStream(bytes))
		if (reference == null) {
			println("${sample.name}: ImageIO could not decode; skipping parity test")
			return null
		}
		if (reference.width != decoded.width || reference.height != decoded.height) {
			return "${sample.path}: PngCodec decoded ${decoded.width}x${decoded.height}, javax.imageio ${reference.width}x${reference.height}"
		}

		val width = decoded.width
		val rowBytes = width * 4
		val expectedRow = ByteArray(rowBytes)
		val argbRow = IntArray(width)
		var mismatchedPixels = 0L
		var firstMismatch: String? = null
		for (rowIndex in 0 until decoded.height) {
			referenceRow(reference, rowIndex, argbRow, expectedRow)
			val rowStart = rowIndex * rowBytes
			if (Arrays.equals(expectedRow, 0, rowBytes, decoded.rgba, rowStart, rowStart + rowBytes)) {
				continue
			}
			for (columnIndex in 0 until width) {
				val pixelOffset = columnIndex * 4
				if (Arrays.equals(expectedRow, pixelOffset, pixelOffset + 4, decoded.rgba, rowStart + pixelOffset, rowStart + pixelOffset + 4)) {
					continue
				}
				mismatchedPixels++
				if (firstMismatch == null) {
					val expectedPixel = rgbaHex(expectedRow, pixelOffset)
					val decodedPixel = rgbaHex(decoded.rgba, rowStart + pixelOffset)
					firstMismatch = "first at ($columnIndex, $rowIndex): expected $expectedPixel, decoded $decodedPixel"
				}
			}
		}
		if (firstMismatch != null) {
			return "${sample.path}: $mismatchedPixels of ${width.toLong() * decoded.height} pixels differ, $firstMismatch"
		}
		println("parity ok ${sample.name}: ${width}x${decoded.height}, ${reference.sampleModel.getSampleSize(0)}-bit")
		return null
	}

	/**
	 * Builds one row of the reference through ImageIO's ColorModel, as the retired `decodePngToRgba`
	 * did, unpacking each packed ARGB pixel to straight-alpha RGBA.
	 *
	 * @param BufferedImage reference The ImageIO decode.
	 * @param Int rowIndex             The image row to read.
	 * @param IntArray scratch         At least `width` ints, overwritten with the row's packed ARGB.
	 * @param ByteArray expectedRow    Receives the row's `width * 4` RGBA bytes.
	 */
	private fun referenceRow(reference: BufferedImage, rowIndex: Int, scratch: IntArray, expectedRow: ByteArray) {
		val width = reference.width
		reference.getRGB(0, rowIndex, width, 1, scratch, 0, width)
		for (columnIndex in 0 until width) {
			val packed = scratch[columnIndex]
			val base = columnIndex * 4
			expectedRow[base] = (packed ushr 16).toByte()
			expectedRow[base + 1] = (packed ushr 8).toByte()
			expectedRow[base + 2] = packed.toByte()
			expectedRow[base + 3] = (packed ushr 24).toByte()
		}
	}

	/**
	 * Formats the four RGBA bytes at [offset] as hex, for a mismatch report.
	 *
	 * @param ByteArray rgba The buffer.
	 * @param Int offset     The pixel's first byte.
	 * @return String The pixel as `RRGGBBAA`.
	 */
	private fun rgbaHex(rgba: ByteArray, offset: Int): String =
		(0 until 4).joinToString("") { channelIndex -> (rgba[offset + channelIndex].toInt() and 0xFF).toString(16).padStart(2, '0') }
}