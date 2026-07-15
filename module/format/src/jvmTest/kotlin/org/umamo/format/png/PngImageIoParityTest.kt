package org.umamo.format.png

import org.umamo.format.raster.RasterImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Exercises [PngCodec] against javax.imageio as an independent reference encoder: PNGs ImageIO writes
 * use whatever scanline filters it chooses, plus Adam7 interlace, so decoding them proves the
 * unfilter and de-interlace paths against an encoder that is not ours.
 *
 * This is the half of the PNG suite that genuinely needs the JVM (ImageIO is desktop-only, absent on
 * Android and Kotlin/Native), so it stays in jvmTest; the hand-built, target-independent cases live in
 * commonTest's PngCodecTest.
 */
class PngImageIoParityTest {
	@Test
	fun decodesImageIoEncodedRgbaAcrossFilters() {
		val image = gradientArgbImage(width = 17, height = 11)
		val decoded = PngCodec.read(encodePng(image, interlaced = false))
		assertArgbImageMatches(image, decoded)
	}

	@Test
	fun decodesImageIoInterlacedRgba() {
		val image = gradientArgbImage(width = 19, height = 13)
		val png = encodePng(image, interlaced = true)
		if ((png[28].toInt() and 0xFF) != 1) {
			println("ImageIO did not produce an interlaced PNG on this JDK; skipping Adam7 assertion")
			return
		}
		val decoded = PngCodec.read(png)
		assertArgbImageMatches(image, decoded)
	}

	@Test
	fun readsWidthAndHeightFromRealImageIoPng() {
		// A quick guard that a standard single-IDAT ImageIO PNG decodes via the same public path.
		val image = gradientArgbImage(4, 4)
		val out = ByteArrayOutputStream()
		ImageIO.write(image, "png", out)
		val reread = ImageIO.read(ByteArrayInputStream(out.toByteArray()))
		assertEquals(reread.width, PngCodec.read(out.toByteArray()).width)
	}

	/**
	 * Builds a TYPE_INT_ARGB test image with a deterministic gradient; alpha stays in 64..255 so
	 * ImageIO reliably preserves the RGB of every pixel (a fully-transparent pixel is where encoders
	 * are free to drop colour - covered separately by the hand-built tRNS tests).
	 *
	 * @param Int width  Image width.
	 * @param Int height Image height.
	 * @return BufferedImage The gradient image.
	 */
	private fun gradientArgbImage(width: Int, height: Int): BufferedImage {
		val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
		for (y in 0 until height) {
			for (x in 0 until width) {
				val red = (x * 13) and 0xFF
				val green = (y * 17) and 0xFF
				val blue = ((x + y) * 7) and 0xFF
				val alpha = 64 + ((x * y) % 192)
				image.setRGB(x, y, (alpha shl 24) or (red shl 16) or (green shl 8) or blue)
			}
		}
		return image
	}

	/**
	 * Encodes a BufferedImage to PNG bytes via javax.imageio, optionally requesting Adam7 interlace.
	 *
	 * @param BufferedImage image The image to encode.
	 * @param Boolean interlaced   Whether to request progressive (interlaced) output.
	 * @return ByteArray The PNG bytes.
	 */
	private fun encodePng(image: BufferedImage, interlaced: Boolean): ByteArray {
		val out = ByteArrayOutputStream()
		if (!interlaced) {
			ImageIO.write(image, "png", out)
			return out.toByteArray()
		}
		val writer = ImageIO.getImageWritersByFormatName("png").next()
		val param = writer.defaultWriteParam
		if (param.canWriteProgressive()) {
			param.progressiveMode = ImageWriteParam.MODE_DEFAULT
		}
		ImageIO.createImageOutputStream(out).use { stream ->
			writer.output = stream
			writer.write(null, IIOImage(image, null, null), param)
		}
		writer.dispose()
		return out.toByteArray()
	}

	/**
	 * Asserts a decoded [RasterImage] matches the reference image pixel-for-pixel (straight RGBA).
	 *
	 * @param BufferedImage reference The source TYPE_INT_ARGB image.
	 * @param RasterImage decoded      The codec's decode.
	 */
	private fun assertArgbImageMatches(reference: BufferedImage, decoded: RasterImage) {
		assertEquals(reference.width, decoded.width, "width")
		assertEquals(reference.height, decoded.height, "height")
		for (y in 0 until reference.height) {
			for (x in 0 until reference.width) {
				val packed = reference.getRGB(x, y)
				val base = (y * reference.width + x) * 4
				assertEquals((packed ushr 16) and 0xFF, decoded.rgba[base].toInt() and 0xFF, "R @ ($x,$y)")
				assertEquals((packed ushr 8) and 0xFF, decoded.rgba[base + 1].toInt() and 0xFF, "G @ ($x,$y)")
				assertEquals(packed and 0xFF, decoded.rgba[base + 2].toInt() and 0xFF, "B @ ($x,$y)")
				assertEquals((packed ushr 24) and 0xFF, decoded.rgba[base + 3].toInt() and 0xFF, "A @ ($x,$y)")
			}
		}
	}
}
