package org.umamo.format.art

import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Builds a LayerRaster from rows of character art: '#' is alpha 255, '.' is alpha 0, and a
 * decimal digit is that literal alpha value (0..9).  RGB bytes are filled with nonzero values
 * so an alpha-offset bug that reads a color channel as coverage fails loudly.
 *
 * @param String rows One string per pixel row, top row first, all the same length.
 * @return LayerRaster The synthetic raster.
 */
internal fun rasterOfRows(vararg rows: String): LayerRaster {
	val height = rows.size
	val width = if (height == 0) 0 else rows[0].length
	val rgba = ByteArray(width * height * 4)
	for (rowIndex in 0 until height) {
		require(rows[rowIndex].length == width) { "all rows must share one width" }
		for (columnIndex in 0 until width) {
			val cell = rows[rowIndex][columnIndex]
			val alpha =
				when (cell) {
					'#' -> 255
					'.' -> 0
					in '0'..'9' -> cell - '0'
					else -> throw IllegalArgumentException("unknown cell '$cell'")
				}
			val byteIndex = (rowIndex * width + columnIndex) * 4
			rgba[byteIndex] = 0x11
			rgba[byteIndex + 1] = 0x22
			rgba[byteIndex + 2] = 0x33
			rgba[byteIndex + 3] = alpha.toByte()
		}
	}
	return LayerRaster(width, height, rgba)
}

/**
 * Builds a one-row LayerRaster with the given alpha byte values, RGB filled nonzero.
 *
 * @param Int alphaValues One alpha (0..255) per pixel, left to right.
 * @return LayerRaster The synthetic 1-row raster.
 */
internal fun rasterOfAlphas(vararg alphaValues: Int): LayerRaster {
	val rgba = ByteArray(alphaValues.size * 4)
	for (columnIndex in alphaValues.indices) {
		rgba[columnIndex * 4] = 0x11
		rgba[columnIndex * 4 + 1] = 0x22
		rgba[columnIndex * 4 + 2] = 0x33
		rgba[columnIndex * 4 + 3] = alphaValues[columnIndex].toByte()
	}
	return LayerRaster(alphaValues.size, 1, rgba)
}

/**
 * Asserts by direct rescan that no pixel outside the given raster-local bounds meets the
 * threshold — the definition of the trimmed rect being trim-lossless.
 *
 * @param LayerRaster raster The analyzed raster.
 * @param LayerBounds bounds The raster-local trimmed bounds.
 * @param Int alphaThreshold The threshold the analysis ran with.
 */
internal fun assertNoOpaquePixelOutside(raster: LayerRaster, bounds: LayerBounds, alphaThreshold: Int) {
	for (rowIndex in 0 until raster.height) {
		for (columnIndex in 0 until raster.width) {
			val insideBounds =
				columnIndex >= bounds.left &&
					columnIndex < bounds.left + bounds.width &&
					rowIndex >= bounds.top &&
					rowIndex < bounds.top + bounds.height
			if (insideBounds) {
				continue
			}
			val alpha = raster.rgba[(rowIndex * raster.width + columnIndex) * 4 + 3].toInt() and 0xFF
			assertTrue(
				alpha < alphaThreshold,
				"opaque pixel ($columnIndex, $rowIndex) outside trimmed bounds $bounds",
			)
		}
	}
}

/**
 * Asserts the structural invariants every non-null analysis must satisfy: a positive count,
 * at least one contour, at least one non-hole, and per contour an even flat size of at least
 * 3 points with every corner inside the trimmed-bounds lattice.
 *
 * With exactRings (epsilon 0) it additionally asserts what only unsimplified rings guarantee:
 * implicit closure (first point differs from last), distinct consecutive points, axis-aligned
 * consecutive steps including the wrap-around pair, the hole flag matching the shoelace sign,
 * and the signed contour areas summing to exactly the opaque pixel count.
 *
 * @param AlphaAnalysis analysis The analysis under test.
 * @param Boolean exactRings True when the analysis ran with contourEpsilon 0.
 */
internal fun assertAnalysisInvariants(analysis: AlphaAnalysis, exactRings: Boolean) {
	assertTrue(analysis.opaquePixelCount >= 1, "non-null analysis has at least one opaque pixel")
	assertTrue(analysis.contours.isNotEmpty(), "non-null analysis has at least one contour")
	assertTrue(analysis.contours.any { contour -> !contour.isHole }, "at least one non-hole contour")
	val bounds = analysis.opaqueBounds
	var signedAreaSum = 0L
	for (contour in analysis.contours) {
		val points = contour.points
		assertTrue(points.size >= 6, "contour has at least 3 points, got ${points.size / 2}")
		assertTrue(points.size % 2 == 0, "contour point array is flat (x, y) pairs")
		val pointCount = points.size / 2
		for (pointIndex in 0 until pointCount) {
			val x = points[pointIndex * 2]
			val y = points[pointIndex * 2 + 1]
			assertTrue(
				x >= bounds.left &&
					x <= bounds.left + bounds.width &&
					y >= bounds.top &&
					y <= bounds.top + bounds.height,
				"corner ($x, $y) within trimmed-bounds lattice $bounds",
			)
			if (exactRings) {
				val nextIndex = (pointIndex + 1) % pointCount
				val nextX = points[nextIndex * 2]
				val nextY = points[nextIndex * 2 + 1]
				assertTrue(x != nextX || y != nextY, "consecutive contour points distinct")
				assertTrue((x == nextX) != (y == nextY), "exact ring steps are axis-aligned")
			}
		}
		if (exactRings) {
			assertTrue(
				points[0] != points[(pointCount - 1) * 2] || points[1] != points[(pointCount - 1) * 2 + 1],
				"closure is implicit: first point differs from last",
			)
			val signedArea = signedAreaTwice(points)
			assertEquals(contour.isHole, signedArea < 0L, "hole flag matches winding sign")
			signedAreaSum += signedArea
		}
	}
	if (exactRings) {
		assertEquals(
			analysis.opaquePixelCount.toLong() * 2L,
			signedAreaSum,
			"signed contour areas sum to the opaque pixel count",
		)
	}
}
