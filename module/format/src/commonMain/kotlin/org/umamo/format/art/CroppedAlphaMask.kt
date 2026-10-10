package org.umamo.format.art

/**
 * A full-resolution, bit-packed alpha mask over a rectangle of raster coordinates that may reach
 * past the raster on every side - the exact pixel set an [AlphaField]'s clearance and cap checks
 * read.
 *
 * Pixel (column, row) in raster coordinates occupies the unit square [column, column + 1] x [row,
 * row + 1]; the mask holds one bit per pixel of its rectangle, row-major.  Packed LongArray, as
 * [AlphaMask] is: an 8192^2 layer's mask is 8.4 MB this way.
 *
 * @property Int       left   The rectangle's left edge, raster px (may be negative).
 * @property Int       top    The rectangle's top edge, raster px (may be negative).
 * @property Int       width  The rectangle's width in pixels.
 * @property Int       height The rectangle's height in pixels.
 * @property LongArray words  The bits, row-major from the rectangle's top-left pixel.
 */
internal class CroppedAlphaMask(
	val left: Int,
	val top: Int,
	val width: Int,
	val height: Int,
	val words: LongArray,
) {
	/**
	 * Whether a pixel is opaque.  Pixels outside the rectangle are transparent.
	 *
	 * @param Int column The pixel's raster column.
	 * @param Int row    The pixel's raster row.
	 * @return Boolean True for an opaque (or seeded) pixel.
	 */
	fun isOpaque(column: Int, row: Int): Boolean {
		val localColumn = column - left
		val localRow = row - top

		if (localColumn < 0 || localRow < 0 || localColumn >= width || localRow >= height) {
			return false
		}

		val bitIndex = localRow.toLong() * width + localColumn

		return ((words[(bitIndex ushr 6).toInt()] ushr (bitIndex and 63L).toInt()) and 1L) != 0L
	}

	/**
	 * Calls an action for every opaque pixel, in row-major order, skipping empty words.
	 *
	 * @param (Int, Int) -> Unit action Receives each opaque pixel's raster column and row.
	 */
	inline fun forEachOpaquePixel(action: (Int, Int) -> Unit) {
		for (wordIndex in words.indices) {
			var word = words[wordIndex]

			while (word != 0L) {
				val bitIndex = wordIndex.toLong() * 64 + word.countTrailingZeroBits()
				action(left + (bitIndex % width).toInt(), top + (bitIndex / width).toInt())
				word = word and (word - 1)
			}
		}
	}

	companion object {
		/**
		 * Builds the mask of a raster's pixels at or above a threshold over a rectangle, plus seed
		 * pixels marked opaque wherever they fall in it.
		 *
		 * @param LayerRaster raster         The source raster.
		 * @param Int         alphaThreshold The minimum alpha byte counted as opaque (1..255).
		 * @param Int         left           The rectangle's left edge, raster px.
		 * @param Int         top            The rectangle's top edge, raster px.
		 * @param Int         width          The rectangle's width.
		 * @param Int         height         The rectangle's height.
		 * @param IntArray    seedPixels     Seed pixels as column, row pairs, raster px.
		 * @return CroppedAlphaMask The mask.
		 */
		fun build(raster: LayerRaster, alphaThreshold: Int, left: Int, top: Int, width: Int, height: Int, seedPixels: IntArray): CroppedAlphaMask {
			val words = LongArray(((width.toLong() * height + 63) ushr 6).toInt())
			val firstColumn = maxOf(left, 0)
			val lastColumn = minOf(left + width, raster.width) - 1
			val firstRow = maxOf(top, 0)
			val lastRow = minOf(top + height, raster.height) - 1

			for (row in firstRow..lastRow) {
				for (column in firstColumn..lastColumn) {
					// The `and 0xFF` is load-bearing: Kotlin Byte is signed.
					val alpha = raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF

					if (alpha >= alphaThreshold) {
						setBit(words, width, column - left, row - top)
					}
				}
			}

			for (seed in 0 until seedPixels.size / 2) {
				val localColumn = seedPixels[2 * seed] - left
				val localRow = seedPixels[2 * seed + 1] - top

				if (localColumn in 0 until width && localRow in 0 until height) {
					setBit(words, width, localColumn, localRow)
				}
			}

			return CroppedAlphaMask(left, top, width, height, words)
		}

		/**
		 * Sets one pixel's bit.
		 *
		 * @param LongArray words       The bits.
		 * @param Int       width       The rectangle's width.
		 * @param Int       localColumn The pixel's column within the rectangle.
		 * @param Int       localRow    The pixel's row within the rectangle.
		 */
		private fun setBit(words: LongArray, width: Int, localColumn: Int, localRow: Int) {
			val bitIndex = localRow.toLong() * width + localColumn
			val wordIndex = (bitIndex ushr 6).toInt()
			words[wordIndex] = words[wordIndex] or (1L shl (bitIndex and 63L).toInt())
		}
	}
}