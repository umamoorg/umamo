package org.umamo.format.png

import org.umamo.format.raster.RasterImage
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.imageio.IIOImage
import javax.imageio.ImageIO
import javax.imageio.ImageWriteParam
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Exercises [PngCodec] without a committed corpus.  Two strategies: hand-built PNGs (via the codec's
 * own chunk writer) pin every colour type, bit depth, tRNS transparency, and sub-byte packing to a
 * known-exact expected RGBA; and PNGs encoded by javax.imageio (an independent reference encoder,
 * available on the desktop-JVM test target) exercise the scanline unfilter across whatever filters
 * ImageIO chooses, plus the Adam7 interlace path.
 */
class PngCodecTest {
	/** Builds a ByteArray from int literals (so values > 127 read cleanly). */
	private fun bytes(vararg values: Int): ByteArray = ByteArray(values.size) { values[it].toByte() }

	/**
	 * Assembles a minimal, non-interlaced PNG (filter type 0 on every row) from raw sample bytes,
	 * reusing the codec's own internal chunk framing so the test controls the exact bytes.
	 *
	 * @param Int width         Image width.
	 * @param Int height        Image height.
	 * @param Int bitDepth      Bits per sample.
	 * @param Int colorType     PNG colour type.
	 * @param ByteArray samples The raw, row-major sample bytes (height * rowBytes).
	 * @param Int rowBytes      Bytes per scanline (excluding the filter byte).
	 * @param ByteArray? palette The PLTE bytes, or null.
	 * @param ByteArray? trns    The tRNS bytes, or null.
	 * @return ByteArray The complete `.png` file.
	 */
	private fun buildPng(width: Int, height: Int, bitDepth: Int, colorType: Int, samples: ByteArray, rowBytes: Int, palette: ByteArray? = null, trns: ByteArray? = null): ByteArray {
		val filtered = ByteArrayOutputStream()
		for (rowIndex in 0 until height) {
			filtered.write(0) // filter type None
			filtered.write(samples, rowIndex * rowBytes, rowBytes)
		}
		val ihdr = ByteArrayOutputStream()
		writeU32BE(ihdr, width)
		writeU32BE(ihdr, height)
		ihdr.write(bitDepth)
		ihdr.write(colorType)
		ihdr.write(0) // compression
		ihdr.write(0) // filter method
		ihdr.write(0) // interlace: none

		val out = ByteArrayOutputStream()
		out.write(PNG_SIGNATURE)
		writeChunk(out, "IHDR", ihdr.toByteArray())
		if (palette != null) {
			writeChunk(out, "PLTE", palette)
		}
		if (trns != null) {
			writeChunk(out, "tRNS", trns)
		}
		writeChunk(out, "IDAT", deflateZlib(filtered.toByteArray()))
		writeChunk(out, "IEND", ByteArray(0))
		return out.toByteArray()
	}

	@Test
	fun writeThenReadRoundTripsRgba() {
		val source =
			RasterImage(
				width = 2,
				height = 2,
				rgba = bytes(10, 20, 30, 40, 50, 60, 70, 255, 80, 90, 100, 128, 200, 210, 220, 64),
			)
		val decoded = PngCodec.read(PngCodec.write(source))
		assertEquals(2, decoded.width)
		assertEquals(2, decoded.height)
		assertContentEquals(source.rgba, decoded.rgba, "RGBA survives write -> read")
	}

	@Test
	fun writeThenReadRoundTripsOddWidth() {
		// A width that is not a power of two exercises the scanline byte math on both ends.
		val width = 5
		val height = 3
		val rgba = ByteArray(width * height * 4) { (it * 7 % 256).toByte() }
		val decoded = PngCodec.read(PngCodec.write(RasterImage(width, height, rgba)))
		assertContentEquals(rgba, decoded.rgba, "odd-width RGBA survives write -> read")
	}

	@Test
	fun decodesGrayscale8WithTransparentKey() {
		// tRNS gray key = 255, so the single 255-valued pixel decodes transparent; the rest opaque.
		val png = buildPng(width = 3, height = 2, bitDepth = 8, colorType = 0, samples = bytes(10, 20, 200, 30, 255, 40), rowBytes = 3, trns = bytes(0, 255))
		val decoded = PngCodec.read(png)
		val expected = bytes(10, 10, 10, 255, 20, 20, 20, 255, 200, 200, 200, 255, 30, 30, 30, 255, 255, 255, 255, 0, 40, 40, 40, 255)
		assertContentEquals(expected, decoded.rgba)
	}

	@Test
	fun decodesPalette8WithTrns() {
		val palette = bytes(255, 0, 0, 0, 255, 0, 0, 0, 255, 128, 128, 128)
		val trns = bytes(255, 128) // idx0 opaque, idx1 half; idx2/idx3 default opaque
		val png = buildPng(width = 2, height = 2, bitDepth = 8, colorType = 3, samples = bytes(0, 1, 2, 3), rowBytes = 2, palette = palette, trns = trns)
		val decoded = PngCodec.read(png)
		val expected = bytes(255, 0, 0, 255, 0, 255, 0, 128, 0, 0, 255, 255, 128, 128, 128, 255)
		assertContentEquals(expected, decoded.rgba)
	}

	@Test
	fun decodesRgb8WithTransparentKey() {
		val trns = bytes(0, 200, 0, 100, 0, 50) // key (200,100,50) as three 16-bit big-endian samples
		val png = buildPng(width = 2, height = 1, bitDepth = 8, colorType = 2, samples = bytes(10, 20, 30, 200, 100, 50), rowBytes = 6, trns = trns)
		val decoded = PngCodec.read(png)
		assertContentEquals(bytes(10, 20, 30, 255, 200, 100, 50, 0), decoded.rgba)
	}

	@Test
	fun decodes16BitGrayscaleKeepingHighByte() {
		val png = buildPng(width = 2, height = 1, bitDepth = 16, colorType = 0, samples = bytes(0x12, 0x34, 0xAB, 0xCD), rowBytes = 4)
		val decoded = PngCodec.read(png)
		assertContentEquals(bytes(0x12, 0x12, 0x12, 255, 0xAB, 0xAB, 0xAB, 255), decoded.rgba)
	}

	@Test
	fun decodesOneBitGrayscaleScaledToFullRange() {
		// 0b10110001 MSB-first: 1,0,1,1,0,0,0,1 -> 255,0,255,255,0,0,0,255.
		val png = buildPng(width = 8, height = 1, bitDepth = 1, colorType = 0, samples = bytes(0xB1), rowBytes = 1)
		val decoded = PngCodec.read(png)
		val expected =
			bytes(
				255,
				255,
				255,
				255,
				0,
				0,
				0,
				255,
				255,
				255,
				255,
				255,
				255,
				255,
				255,
				255,
				0,
				0,
				0,
				255,
				0,
				0,
				0,
				255,
				0,
				0,
				0,
				255,
				255,
				255,
				255,
				255,
			)
		assertContentEquals(expected, decoded.rgba)
	}

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

	@Test
	fun decodedSignatureIsRejectedWhenCorrupt() {
		val png = PngCodec.write(RasterImage(1, 1, bytes(1, 2, 3, 4)))
		assertTrue(PngCodec.matches(png), "valid PNG matches")
		png[1] = 0
		assertTrue(!PngCodec.matches(png), "corrupt signature does not match")
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
}
