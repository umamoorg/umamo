package org.umamo.format.raster

import org.umamo.format.art.LayerBounds
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.roundToInt

/*
 * Thumbnail rasterization over the neutral raster: the fit every CMO3 icon takes (the art scaled so
 * its longer edge fills a square, centered, transparent elsewhere - the shape the official editor
 * writes for layer, model-image, drawable, and model icons), the area-average scale beneath it, and
 * the rectangle crop a texture patch is cut with.  Pure Kotlin, so the desktop and Android exports
 * render identical icons.
 */

/**
 * Scales this raster so its longer edge equals [size] and centers it in a transparent
 * [size] x [size] square, the aspect preserved.
 *
 * @param Int size The square's edge in pixels, at least 1.
 * @return RasterImage The fitted square, straight alpha, top row first.
 */
public fun RasterImage.fittedInto(size: Int): RasterImage {
	require(size > 0) { "a thumbnail needs a positive size, not $size" }
	val square = ByteArray(size * size * 4)

	if (width <= 0 || height <= 0) {
		return RasterImage(size, size, square)
	}

	val fitted = scaledToLongerEdge(size)
	val offsetX = (size - fitted.width) / 2
	val offsetY = (size - fitted.height) / 2

	for (rowIndex in 0 until fitted.height) {
		fitted.rgba.copyInto(square, ((offsetY + rowIndex) * size + offsetX) * 4, rowIndex * fitted.width * 4, (rowIndex + 1) * fitted.width * 4)
	}

	return RasterImage(size, size, square)
}

/**
 * This raster scaled, aspect preserved, so its longer edge is [edge] pixels.
 *
 * @param Int edge The longer edge in pixels, at least 1.
 * @return RasterImage The scaled raster, straight alpha, top row first.
 */
public fun RasterImage.scaledToLongerEdge(edge: Int): RasterImage {
	require(edge > 0) { "a scaled raster needs a positive edge, not $edge" }
	val scale = edge.toDouble() / maxOf(width, height)

	return scaledTo((width * scale).roundToInt().coerceIn(1, edge), (height * scale).roundToInt().coerceIn(1, edge))
}

/**
 * This raster resampled to [targetWidth] x [targetHeight].
 *
 * Each destination pixel is the area average of the source rectangle it covers, computed in
 * premultiplied space: averaging straight RGB across an alpha edge would drag the matte color into
 * the visible pixels and darken the fringe.
 *
 * @param Int targetWidth  The width in pixels, at least 1.
 * @param Int targetHeight The height in pixels, at least 1.
 * @return RasterImage The resampled raster, straight alpha, top row first.
 */
public fun RasterImage.scaledTo(targetWidth: Int, targetHeight: Int): RasterImage {
	require(targetWidth > 0 && targetHeight > 0) { "a scaled raster needs a positive size, not ${targetWidth}x$targetHeight" }
	val scaled = ByteArray(targetWidth * targetHeight * 4)

	if (width <= 0 || height <= 0) {
		return RasterImage(targetWidth, targetHeight, scaled)
	}

	val columnSpans = axisSpans(width, targetWidth)
	val rowSpans = axisSpans(height, targetHeight)

	for (destinationRow in 0 until targetHeight) {
		val rowSpan = rowSpans[destinationRow]

		for (destinationColumn in 0 until targetWidth) {
			val columnSpan = columnSpans[destinationColumn]
			var red = 0.0
			var green = 0.0
			var blue = 0.0
			var alpha = 0.0
			var coverage = 0.0

			for (rowTap in 0 until rowSpan.count) {
				val sourceRow = rowSpan.first + rowTap
				val rowWeight = rowSpan.weights[rowTap]

				for (columnTap in 0 until columnSpan.count) {
					val sourceColumn = columnSpan.first + columnTap
					val weight = rowWeight * columnSpan.weights[columnTap]
					val offset = (sourceRow * width + sourceColumn) * 4
					val sourceAlpha = (rgba[offset + 3].toInt() and 0xFF).toDouble()
					// Premultiplied taps: a transparent source pixel contributes coverage but no color.
					red += (rgba[offset].toInt() and 0xFF) * sourceAlpha * weight
					green += (rgba[offset + 1].toInt() and 0xFF) * sourceAlpha * weight
					blue += (rgba[offset + 2].toInt() and 0xFF) * sourceAlpha * weight
					alpha += sourceAlpha * weight
					coverage += weight
				}
			}

			if (coverage <= 0.0 || alpha <= 0.0) {
				continue
			}

			val destinationOffset = (destinationRow * targetWidth + destinationColumn) * 4
			// Un-premultiply: the color sums carry an alpha factor the alpha sum divides back out.
			scaled[destinationOffset] = (red / alpha).roundToInt().coerceIn(0, 255).toByte()
			scaled[destinationOffset + 1] = (green / alpha).roundToInt().coerceIn(0, 255).toByte()
			scaled[destinationOffset + 2] = (blue / alpha).roundToInt().coerceIn(0, 255).toByte()
			scaled[destinationOffset + 3] = (alpha / coverage).roundToInt().coerceIn(0, 255).toByte()
		}
	}

	return RasterImage(targetWidth, targetHeight, scaled)
}

/**
 * The source pixels one destination pixel covers along one axis, with the fraction of each it covers.
 *
 * @property Int         first   The first source index.
 * @property Int         count   How many source indices, at least one.
 * @property DoubleArray weights The covered fraction of each, in order.
 */
private class AxisSpan(val first: Int, val count: Int, val weights: DoubleArray)

/**
 * The per-destination source spans of one axis: destination index d covers the source interval
 * [d * ratio, (d + 1) * ratio), where ratio is source over destination length.
 *
 * @param Int sourceLength      The source extent in pixels.
 * @param Int destinationLength The destination extent in pixels.
 * @return Array<AxisSpan> One span per destination index.
 */
private fun axisSpans(sourceLength: Int, destinationLength: Int): Array<AxisSpan> {
	val ratio = sourceLength.toDouble() / destinationLength

	return Array(destinationLength) { destinationIndex ->
		val start = destinationIndex * ratio
		val end = ((destinationIndex + 1) * ratio).coerceAtMost(sourceLength.toDouble())
		val first = floor(start).toInt().coerceIn(0, sourceLength - 1)
		val last = (ceil(end).toInt() - 1).coerceIn(first, sourceLength - 1)
		val weights =
			DoubleArray(last - first + 1) { tap ->
				val sourceIndex = first + tap
				// The overlap of [sourceIndex, sourceIndex + 1) with [start, end).
				(minOf(end, sourceIndex + 1.0) - maxOf(start, sourceIndex.toDouble())).coerceAtLeast(0.0)
			}
		AxisSpan(first, weights.size, weights)
	}
}

/**
 * Extracts [bounds] out of this raster, returning the raster itself when the bounds cover it.
 *
 * @param LayerBounds bounds The raster-local rect to keep, inside the raster.
 * @return RasterImage The extracted pixels.
 */
public fun RasterImage.cropped(bounds: LayerBounds): RasterImage {
	if (bounds.left == 0 && bounds.top == 0 && bounds.width == width && bounds.height == height) {
		return this
	}

	val out = ByteArray(bounds.width * bounds.height * 4)

	for (rowIndex in 0 until bounds.height) {
		val sourceOffset = ((bounds.top + rowIndex) * width + bounds.left) * 4
		rgba.copyInto(out, rowIndex * bounds.width * 4, sourceOffset, sourceOffset + bounds.width * 4)
	}

	return RasterImage(bounds.width, bounds.height, out)
}