package org.umamo.format.art

/**
 * A layer-sized image read one alpha byte at a time - the only channel the alpha analysis and the
 * meshing field look at.  A [LayerRaster] answers through [alphaChannel]; a caller that meshes part
 * of a tile answers with the tile's alpha inside its region and zero outside it, without copying the
 * tile's pixels.
 */
public interface AlphaChannel {
	/** The width in pixels. */
	public val width: Int

	/** The height in pixels. */
	public val height: Int

	/**
	 * One pixel's alpha.
	 *
	 * @param Int column The pixel's column, 0 until [width].
	 * @param Int row    The pixel's row, 0 until [height].
	 * @return Int The alpha, 0..255.
	 */
	public fun alphaAt(column: Int, row: Int): Int
}

/**
 * The smallest rectangle holding every pixel at or above [alphaThreshold], raster-local, or null when no
 * pixel reaches it - the same rectangle [analyzeAlpha] reports as its opaque bounds, read through the
 * channel so a caller meshing part of a tile sees only its own region.
 *
 * @param Int alphaThreshold The least alpha byte (1..255) counted as art.
 * @return LayerBounds? The bounds, or null.
 */
public fun AlphaChannel.opaqueBounds(alphaThreshold: Int): LayerBounds? {
	require(alphaThreshold in 1..255) { "alphaThreshold must be in 1..255: $alphaThreshold" }
	var left = Int.MAX_VALUE
	var top = Int.MAX_VALUE
	var right = Int.MIN_VALUE
	var bottom = Int.MIN_VALUE

	for (row in 0 until height) {
		for (column in 0 until width) {
			if (alphaAt(column, row) >= alphaThreshold) {
				left = minOf(left, column)
				top = minOf(top, row)
				right = maxOf(right, column)
				bottom = maxOf(bottom, row)
			}
		}
	}

	return if (left > right) null else LayerBounds(left, top, right - left + 1, bottom - top + 1)
}

/**
 * This raster's alpha channel.
 *
 * @return AlphaChannel The channel, reading the raster's alpha bytes in place.
 */
public fun LayerRaster.alphaChannel(): AlphaChannel {
	require(rgba.size.toLong() == width.toLong() * height.toLong() * 4L) { "rgba size ${rgba.size} does not match $width x $height x 4" }

	return RasterAlphaChannel(this)
}

/**
 * A raster's alpha bytes, read in place.
 *
 * @property LayerRaster raster The raster.
 */
private class RasterAlphaChannel(private val raster: LayerRaster) : AlphaChannel {
	override val width: Int
		get() = raster.width

	override val height: Int
		get() = raster.height

	// The `and 0xFF` is load-bearing: Kotlin Byte is signed.
	override fun alphaAt(column: Int, row: Int): Int = raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF
}