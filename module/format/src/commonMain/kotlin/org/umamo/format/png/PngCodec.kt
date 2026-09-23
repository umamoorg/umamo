package org.umamo.format.png

import okio.Buffer
import org.umamo.format.FileKind
import org.umamo.format.binary.ZlibStreamReader
import org.umamo.format.raster.RasterCodec
import org.umamo.format.raster.RasterImage

/**
 * Pure-Kotlin PNG codec, read and write.
 *
 * Decodes every PNG color type (grayscale, RGB, palette, grayscale+alpha, RGBA) at bit depths
 * 1/2/4/8/16, including tRNS transparency and Adam7 interlace, to the neutral straight-alpha
 * RGBA8888 top-first [RasterImage].  16-bit samples are reduced to 8 bits by the spec's most
 * accurate rescaling, the linear equation of PNG spec §13.12, rather than by dropping the low byte.
 * Encodes 8-bit RGBA (color type 6, non-interlaced) - the form the atlas / thumbnail pipeline
 * needs.  DEFLATE is the codec's only platform dependency, reached through the zlib bridge in
 * org.umamo.format.binary, so it decodes byte-identically on every target (no javax.imageio /
 * BitmapFactory, which is exactly what this replaces).
 *
 * Decoding streams: the IDAT data is inflated one scanline at a time straight out of the file
 * bytes, so a decode holds the file and the output image and little else.  Neither the compressed
 * nor the decompressed stream is ever held whole, which is what lets a 16384-square atlas page
 * decode in little more than the 1 GiB its pixels need.
 *
 * Spec citations are to the PNG specification, second edition (ISO/IEC 15948:2003, W3C
 * REC-PNG-20031110).
 */
public object PngCodec : RasterCodec {
	override val kind: FileKind = FileKind.Png

	// PNG spec §11.2.2 IHDR color types.
	private const val COLOR_GRAYSCALE = 0
	private const val COLOR_RGB = 2
	private const val COLOR_PALETTE = 3
	private const val COLOR_GRAYSCALE_ALPHA = 4
	private const val COLOR_RGBA = 6

	private const val OPAQUE = 0xFF.toByte()

	// A tRNS key no sample equals, for an image without one: raw samples are never negative.
	private const val NO_KEY = -1

	/** Adam7 per-pass origin and step (PNG spec §8.2): {startColumn, startRow, columnStep, rowStep}. */
	private val ADAM7_PASSES =
		arrayOf(
			intArrayOf(0, 0, 8, 8),
			intArrayOf(4, 0, 8, 8),
			intArrayOf(0, 4, 4, 8),
			intArrayOf(2, 0, 4, 4),
			intArrayOf(0, 2, 2, 4),
			intArrayOf(1, 0, 2, 2),
			intArrayOf(0, 1, 1, 2),
		)

	/**
	 * True if [candidateBytes] opens with the PNG signature.
	 *
	 * @param ByteArray candidateBytes Candidate file contents.
	 * @return Boolean Whether the leading bytes are the PNG magic.
	 */
	override fun matches(candidateBytes: ByteArray): Boolean = matchesPngSignature(candidateBytes)

	/**
	 * Decodes a `.png` into a straight-alpha RGBA8888 [RasterImage], top row first.
	 *
	 * @param ByteArray bytes The complete `.png` file.
	 * @return RasterImage The decoded image.
	 */
	override fun read(bytes: ByteArray): RasterImage {
		val chunks = readChunks(bytes)
		val ihdrChunk = chunks.firstOrNull { it.type == "IHDR" } ?: throw IllegalArgumentException("PNG has no IHDR")
		val header = parseHeader(ihdrChunk.copyData())

		val palette = chunks.firstOrNull { it.type == "PLTE" }?.copyData()
		val transparency = chunks.firstOrNull { it.type == "tRNS" }?.copyData()
		// PNG spec §11.2.2 Table 11.1: an indexed-color image shall carry a PLTE chunk.
		require(header.colorType != COLOR_PALETTE || palette != null) { "PNG color type 3 has no PLTE chunk" }
		val expander = ScanlineExpander(header, palette, transparency)

		val rgba = ByteArray(header.width * header.height * 4)
		// Only the scanlines IHDR describes are ever requested from the inflate, which is also its
		// bound: a corrupt or hostile stream stops at the size the header promised instead of
		// expanding until the process dies.  A stream that ends early leaves the rows it never
		// reached transparent black (best-effort, mirroring the PSD/CLIP readers).
		val scanlines = ZlibStreamReader(IdatSource(chunks.filter { it.type == "IDAT" }))
		try {
			if (header.interlace == 1) {
				for (pass in ADAM7_PASSES) {
					val passWidth = passExtent(header.width, pass[0], pass[2])
					val passHeight = passExtent(header.height, pass[1], pass[3])
					if (passWidth == 0 || passHeight == 0) {
						continue
					}
					decodePass(scanlines, rgba, header, expander, passWidth, passHeight, pass[0], pass[1], pass[2], pass[3])
				}
			} else {
				decodePass(scanlines, rgba, header, expander, header.width, header.height, 0, 0, 1, 1)
			}
		} finally {
			scanlines.close()
		}
		return RasterImage(header.width, header.height, rgba)
	}

	/**
	 * Encodes a [RasterImage] as an 8-bit RGBA (color type 6), non-interlaced PNG.
	 *
	 * Every scanline uses filter type 0 (None) - the simplest valid choice; DEFLATE still compresses
	 * it well.  Round-trip is not byte-identical (zlib output varies), but decoding the result
	 * reproduces the input pixels exactly.
	 *
	 * @param RasterImage model The image to encode.
	 * @return ByteArray The complete `.png` file.
	 */
	override fun write(model: RasterImage): ByteArray {
		val out = Buffer()
		out.write(PNG_SIGNATURE)

		// PNG spec §11.2.2 IHDR: width, height, bitDepth=8, colourType=6 (RGBA), compression=0, filter=0, interlace=0.
		val ihdr = Buffer()
		ihdr.writeInt(model.width)
		ihdr.writeInt(model.height)
		ihdr.writeByte(8)
		ihdr.writeByte(COLOR_RGBA)
		ihdr.writeByte(0)
		ihdr.writeByte(0)
		ihdr.writeByte(0)
		writeChunk(out, "IHDR", ihdr.readByteArray())

		// Raw filtered scanlines: a leading filter byte (0=None) then width*4 RGBA bytes per row.
		val rowBytes = model.width * 4
		val filtered = ByteArray(model.height * (1 + rowBytes))
		var destination = 0
		var source = 0
		for (rowIndex in 0 until model.height) {
			filtered[destination++] = FILTER_NONE.toByte()
			model.rgba.copyInto(filtered, destination, source, source + rowBytes)
			destination += rowBytes
			source += rowBytes
		}
		writeChunk(out, "IDAT", deflateIdat(filtered))
		writeChunk(out, "IEND", ByteArray(0))
		return out.readByteArray()
	}

	/**
	 * The IHDR fields the decoder needs.
	 *
	 * @param Int width      Image width in pixels.
	 * @param Int height     Image height in pixels.
	 * @param Int bitDepth   Bits per sample (per palette index for color type 3).
	 * @param Int colorType  The PNG color type.
	 * @param Int interlace  0 for none, 1 for Adam7.
	 */
	private class PngHeader(
		val width: Int,
		val height: Int,
		val bitDepth: Int,
		val colorType: Int,
		val interlace: Int,
	) {
		/** Samples stored per pixel. */
		val channels: Int = channelCount(colorType)

		/** Bits one pixel occupies in a scanline. */
		val bitsPerPixel: Int = channels * bitDepth
	}

	/**
	 * Parses the 13-byte IHDR data (PNG spec §11.2.2).
	 *
	 * @param ByteArray data The IHDR chunk data.
	 * @return PngHeader The parsed header, validated against the spec's allowed values.
	 */
	private fun parseHeader(data: ByteArray): PngHeader {
		require(data.size >= 13) { "PNG IHDR too short" }
		// PNG spec §11.2.2 IHDR: width(4) | height(4) | bit depth(1) | color type(1) |
		// compression method(1) | filter method(1) | interlace method(1).
		val width = readU32BE(data, 0).toInt()
		val height = readU32BE(data, 4).toInt()
		val bitDepth = data[8].toInt() and 0xFF
		val colorType = data[9].toInt() and 0xFF
		val compressionMethod = data[10].toInt() and 0xFF
		val filterMethod = data[11].toInt() and 0xFF
		val interlace = data[12].toInt() and 0xFF
		require(width > 0 && height > 0) { "PNG has non-positive dimensions ${width}x$height" }
		// IHDR allows dimensions up to 2^31-1 each, whose product overflows the Int sizing the RGBA
		// buffer; reject rather than wrap to a negative (or plausible-but-wrong) allocation.
		require(width.toLong() * height * 4 <= Int.MAX_VALUE) { "PNG is too large to decode: ${width}x$height" }
		require(colorType == COLOR_GRAYSCALE || colorType == COLOR_RGB || colorType == COLOR_PALETTE || colorType == COLOR_GRAYSCALE_ALPHA || colorType == COLOR_RGBA) {
			"unsupported PNG color type $colorType"
		}
		require(bitDepthAllowed(colorType, bitDepth)) { "PNG color type $colorType does not allow bit depth $bitDepth" }
		// Method 0 is the only compression method and the only filter method the spec defines.
		require(compressionMethod == 0) { "unsupported PNG compression method $compressionMethod" }
		require(filterMethod == 0) { "unsupported PNG filter method $filterMethod" }
		require(interlace == 0 || interlace == 1) { "unsupported PNG interlace method $interlace" }
		// A 16-bit RGBA scanline is twice the width of its RGBA8888 row, so it can outgrow an array
		// even when the image itself fits.
		require((width.toLong() * channelCount(colorType) * bitDepth + 7) / 8 < Int.MAX_VALUE) { "PNG scanline is too long to decode: ${width}x$height" }
		return PngHeader(width, height, bitDepth, colorType, interlace)
	}

	/**
	 * Whether the spec allows [bitDepth] for [colorType] (PNG spec §11.2.2 Table 11.1).
	 *
	 * @param Int colorType A color type already checked to be one of the five defined ones.
	 * @param Int bitDepth  The IHDR bit depth.
	 * @return Boolean True for an allowed pairing.
	 */
	private fun bitDepthAllowed(colorType: Int, bitDepth: Int): Boolean =
		when (colorType) {
			COLOR_GRAYSCALE -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8 || bitDepth == 16
			COLOR_PALETTE -> bitDepth == 1 || bitDepth == 2 || bitDepth == 4 || bitDepth == 8
			else -> bitDepth == 8 || bitDepth == 16
		}

	/** Channels stored per pixel for a color type (palette and grayscale store one sample). */
	private fun channelCount(colorType: Int): Int =
		when (colorType) {
			COLOR_GRAYSCALE, COLOR_PALETTE -> 1
			COLOR_GRAYSCALE_ALPHA -> 2
			COLOR_RGB -> 3
			COLOR_RGBA -> 4
			else -> throw IllegalArgumentException("unsupported PNG color type $colorType")
		}

	/**
	 * The number of pixels a reduced Adam7 pass spans along one axis (PNG spec §8.2).
	 *
	 * @param Int total The full image extent (width or height).
	 * @param Int start The pass origin on this axis.
	 * @param Int step  The pass step on this axis.
	 * @return Int The count of samples this pass contributes on this axis (0 if the pass is empty).
	 */
	private fun passExtent(total: Int, start: Int, step: Int): Int =
		if (total <= start) {
			0
		} else {
			(total - start + step - 1) / step
		}

	/**
	 * Decodes one filtered sub-image (the whole image when non-interlaced, one Adam7 pass otherwise),
	 * pulling its scanlines from [scanlines] and scattering its pixels into [rgba] at their
	 * interlaced positions.
	 *
	 * @param ZlibStreamReader scanlines  The inflating IDAT stream, positioned at this sub-image.
	 * @param ByteArray rgba               The destination full-image RGBA buffer.
	 * @param PngHeader header             The IHDR parameters.
	 * @param ScanlineExpander expander    Turns a reconstructed scanline into RGBA.
	 * @param Int passWidth                Pixels per row in this sub-image.
	 * @param Int passHeight               Rows in this sub-image.
	 * @param Int startColumn              Adam7 column origin (0 for the full image).
	 * @param Int startRow                 Adam7 row origin (0 for the full image).
	 * @param Int columnStep               Adam7 column step (1 for the full image).
	 * @param Int rowStep                  Adam7 row step (1 for the full image).
	 */
	private fun decodePass(
		scanlines: ZlibStreamReader,
		rgba: ByteArray,
		header: PngHeader,
		expander: ScanlineExpander,
		passWidth: Int,
		passHeight: Int,
		startColumn: Int,
		startRow: Int,
		columnStep: Int,
		rowStep: Int,
	) {
		val bytesPerPixel = maxOf(1, (header.bitsPerPixel + 7) / 8)
		val rowBytes = ((header.bitsPerPixel.toLong() * passWidth + 7) / 8).toInt()
		val filterTypeByte = ByteArray(1)
		// PNG spec §9.2: the first scanline of each reduced image filters against an all-zero row.
		var previous = ByteArray(rowBytes)
		var current = ByteArray(rowBytes)
		for (rowIndex in 0 until passHeight) {
			// PNG spec §9.2: each scanline opens with its filter-type byte.  Past the end of a stream
			// that stopped early, rows read as unfiltered zeros.
			val filterType =
				if (scanlines.readFully(filterTypeByte, 0, 1) == 1) {
					filterTypeByte[0].toInt() and 0xFF
				} else {
					FILTER_NONE
				}
			val available = scanlines.readFully(current, 0, rowBytes)
			if (available < rowBytes) {
				current.fill(0, available, rowBytes)
			}

			unfilterScanline(filterType, current, previous, bytesPerPixel)

			val imageY = startRow + rowIndex * rowStep
			expander.expand(current, passWidth, rgba, (imageY * header.width + startColumn) * 4, columnStep * 4)

			val swap = previous
			previous = current
			current = swap
		}
	}

	/**
	 * Turns reconstructed scanlines into straight-alpha RGBA8888, with everything that depends only on
	 * IHDR, PLTE, and tRNS worked out once per image instead of once per sample.
	 *
	 * @param PngHeader header        The IHDR parameters.
	 * @param ByteArray? palette      The PLTE bytes (3 per entry), or null.
	 * @param ByteArray? transparency The tRNS bytes, or null.
	 */
	private class ScanlineExpander(private val header: PngHeader, palette: ByteArray?, transparency: ByteArray?) {
		// PNG spec §11.3.2.1 tRNS for grayscale and truecolor: the single gray or RGB value, in the
		// image's own sample range, that marks a pixel fully transparent.
		private val keyGray = keySample(COLOR_GRAYSCALE, transparency, 0)
		private val keyRed = keySample(COLOR_RGB, transparency, 0)
		private val keyGreen = keySample(COLOR_RGB, transparency, 2)
		private val keyBlue = keySample(COLOR_RGB, transparency, 4)

		/**
		 * RGBA for every possible sample value of the one-sample layouts at 8 bits or fewer (palette,
		 * and grayscale at 1/2/4/8 bits), where a table of at most 256 entries replaces all per-pixel
		 * work; null for every other layout.
		 */
		private val sampleColors: ByteArray? = buildSampleColors(palette, transparency)

		/**
		 * Reads one sample of a tRNS key, or [NO_KEY] when the image is not of [colorType] or its
		 * tRNS chunk is missing or too short to hold the key.
		 *
		 * @param Int colorType           The color type the key belongs to.
		 * @param ByteArray? transparency The tRNS bytes, or null.
		 * @param Int offset              Offset of the key sample's 2-byte big-endian value.
		 * @return Int The raw key sample, or [NO_KEY].
		 */
		private fun keySample(colorType: Int, transparency: ByteArray?, offset: Int): Int {
			val keyBytes = if (colorType == COLOR_RGB) 6 else 2
			if (header.colorType != colorType || transparency == null || transparency.size < keyBytes) {
				return NO_KEY
			}
			return readU16(transparency, offset)
		}

		/**
		 * Builds the per-sample RGBA table for palette and low-depth grayscale images.
		 *
		 * @param ByteArray? palette      The PLTE bytes, or null.
		 * @param ByteArray? transparency The tRNS bytes, or null.
		 * @return ByteArray? Four RGBA bytes per sample value, or null when the layout has no table.
		 */
		private fun buildSampleColors(palette: ByteArray?, transparency: ByteArray?): ByteArray? {
			if (header.bitDepth > 8) {
				return null
			}
			val entries = 1 shl header.bitDepth
			return when (header.colorType) {
				COLOR_PALETTE -> {
					val table = ByteArray(entries * 4)
					for (index in 0 until entries) {
						val paletteOffset = index * 3
						// An index past the palette's end stays black.
						if (palette != null && paletteOffset + 2 < palette.size) {
							table[index * 4] = palette[paletteOffset]
							table[index * 4 + 1] = palette[paletteOffset + 1]
							table[index * 4 + 2] = palette[paletteOffset + 2]
						}
						// PNG spec §11.3.2.1 tRNS for palette: one alpha byte per entry; unlisted entries are opaque.
						table[index * 4 + 3] = if (transparency != null && index < transparency.size) transparency[index] else OPAQUE
					}
					table
				}

				COLOR_GRAYSCALE -> {
					val table = ByteArray(entries * 4)
					val maximumSample = entries - 1
					for (sample in 0 until entries) {
						// PNG spec §12.5 linear scaling up to 8 bits; 255 is a multiple of 1, 3, and 15,
						// so the equation's rounding never comes into play.
						val gray = (sample * 255 / maximumSample).toByte()
						table[sample * 4] = gray
						table[sample * 4 + 1] = gray
						table[sample * 4 + 2] = gray
						table[sample * 4 + 3] = if (sample == keyGray) 0 else OPAQUE
					}
					table
				}

				else -> null
			}
		}

		/**
		 * Writes one reconstructed scanline's pixels into [rgba].
		 *
		 * @param ByteArray scanline   The reconstructed row bytes.
		 * @param Int pixelCount       Pixels in the row.
		 * @param ByteArray rgba       The destination full-image RGBA buffer.
		 * @param Int destinationStart Byte offset in [rgba] of the row's first pixel.
		 * @param Int destinationStep  Bytes between the row's consecutive pixels in [rgba]: 4, or a
		 *                             multiple of it for an Adam7 pass.
		 */
		fun expand(scanline: ByteArray, pixelCount: Int, rgba: ByteArray, destinationStart: Int, destinationStep: Int) {
			val table = sampleColors
			if (table != null) {
				expandFromTable(table, scanline, pixelCount, rgba, destinationStart, destinationStep)
			} else if (header.colorType == COLOR_RGBA && header.bitDepth == 8) {
				expandRgba8(scanline, pixelCount, rgba, destinationStart, destinationStep)
			} else {
				expandSamples(scanline, pixelCount, rgba, destinationStart, destinationStep)
			}
		}

		/**
		 * Expands a palette or low-depth grayscale scanline by table lookup.
		 *
		 * @param ByteArray table      Four RGBA bytes per sample value.
		 * @param ByteArray scanline   The reconstructed row bytes.
		 * @param Int pixelCount       Pixels in the row.
		 * @param ByteArray rgba       The destination full-image RGBA buffer.
		 * @param Int destinationStart Byte offset in [rgba] of the row's first pixel.
		 * @param Int destinationStep  Bytes between the row's consecutive pixels in [rgba].
		 */
		private fun expandFromTable(table: ByteArray, scanline: ByteArray, pixelCount: Int, rgba: ByteArray, destinationStart: Int, destinationStep: Int) {
			val bitDepth = header.bitDepth
			val sampleMask = (1 shl bitDepth) - 1
			var destination = destinationStart
			for (pixelIndex in 0 until pixelCount) {
				val sample =
					if (bitDepth == 8) {
						scanline[pixelIndex].toInt() and 0xFF
					} else {
						// PNG spec §7.2: samples under 8 bits pack leftmost-first from each byte's high-order bits.
						val bitIndex = pixelIndex * bitDepth
						(scanline[bitIndex ushr 3].toInt() ushr (8 - bitDepth - (bitIndex and 7))) and sampleMask
					}
				val tableOffset = sample * 4
				rgba[destination] = table[tableOffset]
				rgba[destination + 1] = table[tableOffset + 1]
				rgba[destination + 2] = table[tableOffset + 2]
				rgba[destination + 3] = table[tableOffset + 3]
				destination += destinationStep
			}
		}

		/**
		 * Expands an 8-bit RGBA scanline, which is already in the output layout: one copy for a full
		 * row, four bytes per pixel for an Adam7 pass.
		 *
		 * @param ByteArray scanline   The reconstructed row bytes.
		 * @param Int pixelCount       Pixels in the row.
		 * @param ByteArray rgba       The destination full-image RGBA buffer.
		 * @param Int destinationStart Byte offset in [rgba] of the row's first pixel.
		 * @param Int destinationStep  Bytes between the row's consecutive pixels in [rgba].
		 */
		private fun expandRgba8(scanline: ByteArray, pixelCount: Int, rgba: ByteArray, destinationStart: Int, destinationStep: Int) {
			if (destinationStep == 4) {
				scanline.copyInto(rgba, destinationStart, 0, pixelCount * 4)
				return
			}
			var source = 0
			var destination = destinationStart
			for (pixelIndex in 0 until pixelCount) {
				rgba[destination] = scanline[source]
				rgba[destination + 1] = scanline[source + 1]
				rgba[destination + 2] = scanline[source + 2]
				rgba[destination + 3] = scanline[source + 3]
				source += 4
				destination += destinationStep
			}
		}

		/**
		 * Expands the remaining layouts sample by sample: 8-bit grayscale+alpha and RGB, and every
		 * 16-bit layout.  tRNS keys compare against the raw samples before any rescaling, as PNG spec
		 * §13.12 requires.
		 *
		 * @param ByteArray scanline   The reconstructed row bytes.
		 * @param Int pixelCount       Pixels in the row.
		 * @param ByteArray rgba       The destination full-image RGBA buffer.
		 * @param Int destinationStart Byte offset in [rgba] of the row's first pixel.
		 * @param Int destinationStep  Bytes between the row's consecutive pixels in [rgba].
		 */
		private fun expandSamples(scanline: ByteArray, pixelCount: Int, rgba: ByteArray, destinationStart: Int, destinationStep: Int) {
			val channels = header.channels
			val sixteenBit = header.bitDepth == 16
			val sampleBytes = if (sixteenBit) 2 else 1
			val pixelBytes = channels * sampleBytes
			var source = 0
			var destination = destinationStart
			for (pixelIndex in 0 until pixelCount) {
				val first = sampleAt(scanline, source, sixteenBit)
				if (channels >= 3) {
					val green = sampleAt(scanline, source + sampleBytes, sixteenBit)
					val blue = sampleAt(scanline, source + 2 * sampleBytes, sixteenBit)
					rgba[destination] = toEightBits(first, sixteenBit)
					rgba[destination + 1] = toEightBits(green, sixteenBit)
					rgba[destination + 2] = toEightBits(blue, sixteenBit)
					rgba[destination + 3] =
						if (channels == 4) {
							toEightBits(sampleAt(scanline, source + 3 * sampleBytes, sixteenBit), sixteenBit)
						} else if (first == keyRed && green == keyGreen && blue == keyBlue) {
							0
						} else {
							OPAQUE
						}
				} else {
					val gray = toEightBits(first, sixteenBit)
					rgba[destination] = gray
					rgba[destination + 1] = gray
					rgba[destination + 2] = gray
					rgba[destination + 3] =
						if (channels == 2) {
							toEightBits(sampleAt(scanline, source + sampleBytes, sixteenBit), sixteenBit)
						} else if (first == keyGray) {
							0
						} else {
							OPAQUE
						}
				}
				source += pixelBytes
				destination += destinationStep
			}
		}
	}

	/**
	 * Reads one raw 8- or 16-bit sample from a reconstructed scanline.
	 *
	 * @param ByteArray scanline  The reconstructed row bytes.
	 * @param Int offset          Offset of the sample's first byte.
	 * @param Boolean sixteenBit  Whether samples are 16-bit (big-endian, PNG spec §7.2) rather than 8-bit.
	 * @return Int The raw sample value.
	 */
	private fun sampleAt(scanline: ByteArray, offset: Int, sixteenBit: Boolean): Int =
		if (sixteenBit) {
			((scanline[offset].toInt() and 0xFF) shl 8) or (scanline[offset + 1].toInt() and 0xFF)
		} else {
			scanline[offset].toInt() and 0xFF
		}

	/**
	 * Converts a raw 8- or 16-bit sample to the 8-bit output value.
	 *
	 * A 16-bit sample is rescaled by PNG spec §13.12's linear equation,
	 * floor(sample * 255 / 65535 + 0.5).  The multiply-and-shift form below equals it for every 16-bit
	 * input, and needs no division.
	 *
	 * @param Int sample          The raw sample value.
	 * @param Boolean sixteenBit  Whether [sample] is 16-bit.
	 * @return Byte The 8-bit value.
	 */
	private fun toEightBits(sample: Int, sixteenBit: Boolean): Byte =
		if (sixteenBit) {
			((sample * 255 + 32895) ushr 16).toByte()
		} else {
			sample.toByte()
		}

	/**
	 * Reads a big-endian unsigned 16-bit value (tRNS stores each key sample this way).
	 *
	 * @param ByteArray bytes The buffer.
	 * @param Int at          Offset of the most-significant byte.
	 * @return Int The value in 0..65535.
	 */
	private fun readU16(bytes: ByteArray, at: Int): Int =
		((bytes[at].toInt() and 0xFF) shl 8) or (bytes[at + 1].toInt() and 0xFF)
}