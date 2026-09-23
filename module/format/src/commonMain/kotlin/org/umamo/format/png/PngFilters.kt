package org.umamo.format.png

/*
 * PNG scanline filtering (PNG spec §9 Filtering).  Each decoded scanline is preceded by a filter-type
 * byte; the filter predicts each byte from already-reconstructed neighbours and stores the residual,
 * so decoding adds the prediction back.  All arithmetic is modulo 256 (byte truncation).
 */

// PNG spec §9.2 Filter types.
internal const val FILTER_NONE = 0
internal const val FILTER_SUB = 1
internal const val FILTER_UP = 2
internal const val FILTER_AVERAGE = 3
internal const val FILTER_PAETH = 4

/**
 * Reconstructs one scanline in place, undoing the filter of type [filterType].
 *
 * The bytes of the first pixel have no left neighbor (PNG spec §9.2 treats it as zero), so each
 * filter handles them in a loop of their own and the main loop runs without a per-byte bounds test.
 *
 * @param Int filterType     The filter byte (0=None, 1=Sub, 2=Up, 3=Average, 4=Paeth).
 * @param ByteArray current   The filtered bytes of this scanline; overwritten with the reconstruction.
 * @param ByteArray previous  The already-reconstructed previous scanline (all zero for the first row).
 * @param Int bytesPerPixel   The filter stride: bytes per complete pixel, rounded up to at least 1.
 */
internal fun unfilterScanline(filterType: Int, current: ByteArray, previous: ByteArray, bytesPerPixel: Int) {
	val length = current.size
	val firstPixelEnd = minOf(bytesPerPixel, length)
	when (filterType) {
		FILTER_NONE -> {
			// Recon(x) = Filt(x).
		}

		FILTER_SUB -> {
			// Recon(x) = Filt(x) + Recon(a); the first pixel's left neighbor is zero.
			for (index in bytesPerPixel until length) {
				current[index] = (current[index] + current[index - bytesPerPixel]).toByte()
			}
		}

		FILTER_UP -> {
			// Recon(x) = Filt(x) + Recon(b).
			for (index in 0 until length) {
				current[index] = (current[index] + previous[index]).toByte()
			}
		}

		FILTER_AVERAGE -> {
			// Recon(x) = Filt(x) + floor((Recon(a) + Recon(b)) / 2), over unsigned bytes.
			for (index in 0 until firstPixelEnd) {
				current[index] = (current[index] + ((previous[index].toInt() and 0xFF) ushr 1)).toByte()
			}
			for (index in bytesPerPixel until length) {
				val left = current[index - bytesPerPixel].toInt() and 0xFF
				val above = previous[index].toInt() and 0xFF
				current[index] = (current[index] + ((left + above) ushr 1)).toByte()
			}
		}

		FILTER_PAETH -> {
			// Recon(x) = Filt(x) + PaethPredictor(Recon(a), Recon(b), Recon(c)).  With a and c both
			// zero the predictor is always b, so the first pixel reduces to Up.
			for (index in 0 until firstPixelEnd) {
				current[index] = (current[index] + previous[index]).toByte()
			}
			for (index in bytesPerPixel until length) {
				val left = current[index - bytesPerPixel].toInt() and 0xFF
				val above = previous[index].toInt() and 0xFF
				val aboveLeft = previous[index - bytesPerPixel].toInt() and 0xFF
				current[index] = (current[index] + paethPredictor(left, above, aboveLeft)).toByte()
			}
		}

		else -> throw IllegalArgumentException("unknown PNG filter type $filterType")
	}
}

/**
 * The Paeth predictor (PNG spec §9.4): picks the neighbour (left, above, or above-left) closest to
 * the linear estimate left + above - aboveLeft.
 *
 * @param Int left       The reconstructed byte to the left (a in the spec).
 * @param Int above      The reconstructed byte above (b in the spec).
 * @param Int aboveLeft  The reconstructed byte above-left (c in the spec).
 * @return Int The predicted byte value (0..255).
 */
internal fun paethPredictor(left: Int, above: Int, aboveLeft: Int): Int {
	// The spec's pa, pb, and pc with the estimate p = a + b - c substituted in: p - a = b - c,
	// p - b = a - c, and p - c = a + b - 2c.
	val distanceLeft = kotlin.math.abs(above - aboveLeft)
	val distanceAbove = kotlin.math.abs(left - aboveLeft)
	val distanceAboveLeft = kotlin.math.abs(left + above - aboveLeft - aboveLeft)
	return when {
		distanceLeft <= distanceAbove && distanceLeft <= distanceAboveLeft -> left
		distanceAbove <= distanceAboveLeft -> above
		else -> aboveLeft
	}
}