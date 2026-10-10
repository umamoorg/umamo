package org.umamo.format.art

import kotlin.math.sqrt
import kotlin.random.Random

/**
 * A raster whose alpha is 255 where a predicate says opaque and 0 elsewhere.
 *
 * @param Int                       width  The raster width.
 * @param Int                       height The raster height.
 * @param (Int, Int) -> Boolean     opaque Whether pixel (column, row) is opaque.
 * @return LayerRaster The raster.
 */
internal fun rasterOfMask(width: Int, height: Int, opaque: (Int, Int) -> Boolean): LayerRaster {
	val rgba = ByteArray(width * height * 4)

	for (row in 0 until height) {
		for (column in 0 until width) {
			if (opaque(column, row)) {
				rgba[(row * width + column) * 4 + 3] = 0xFF.toByte()
			}
		}
	}

	return LayerRaster(width, height, rgba)
}

/**
 * A random blob raster: a few filled discs and specks, so there are islands, holes, and saddles.
 *
 * @param Random random The source.
 * @param Int    width  The raster width.
 * @param Int    height The raster height.
 * @return LayerRaster The raster, never fully transparent.
 */
internal fun randomBlobRaster(random: Random, width: Int, height: Int): LayerRaster {
	val discs = List(random.nextInt(1, 5)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(1.0, 5.0)) }
	val holes = List(random.nextInt(0, 3)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(0.5, 2.5)) }
	val specks = List(random.nextInt(0, 4)) { intArrayOf(random.nextInt(width), random.nextInt(height)) }

	return rasterOfMask(width, height) { column, row ->
		val centerX = column + 0.5
		val centerY = row + 0.5
		val inDisc = discs.any { disc -> squared(centerX - disc[0]) + squared(centerY - disc[1]) <= squared(disc[2]) }
		val inHole = holes.any { hole -> squared(centerX - hole[0]) + squared(centerY - hole[1]) <= squared(hole[2]) }
		val isSpeck = specks.any { speck -> speck[0] == column && speck[1] == row }
		(inDisc && !inHole) || isSpeck || (column == width / 2 && row == height / 2)
	}
}

/**
 * The opaque pixels of a raster as column, row pairs.
 *
 * @param LayerRaster raster The raster.
 * @return List<IntArray> The opaque pixels.
 */
internal fun opaquePixelsOf(raster: LayerRaster): List<IntArray> {
	val pixels = ArrayList<IntArray>()

	for (row in 0 until raster.height) {
		for (column in 0 until raster.width) {
			if ((raster.rgba[(row * raster.width + column) * 4 + 3].toInt() and 0xFF) > 0) {
				pixels.add(intArrayOf(column, row))
			}
		}
	}

	return pixels
}

/**
 * The distance from a point to the nearest opaque pixel square, by scanning them all.
 *
 * @param List<IntArray> pixels The opaque pixels.
 * @param Double         x      The point's x.
 * @param Double         y      The point's y.
 * @return Double The distance (zero inside a square).
 */
internal fun bruteDistanceToSquares(pixels: List<IntArray>, x: Double, y: Double): Double {
	var nearest = Double.POSITIVE_INFINITY

	for (pixel in pixels) {
		val offsetX = maxOf(pixel[0] - x, 0.0, x - (pixel[0] + 1))
		val offsetY = maxOf(pixel[1] - y, 0.0, y - (pixel[1] + 1))
		nearest = minOf(nearest, sqrt(offsetX * offsetX + offsetY * offsetY))
	}

	return nearest
}

/**
 * Whether a point lies inside a set of rings by the even-odd rule (a ray cast toward +x).
 *
 * @param List<DoubleArray> rings The rings.
 * @param Double            x     The point's x.
 * @param Double            y     The point's y.
 * @return Boolean True for an odd number of crossings.
 */
internal fun insideByEvenOdd(rings: List<DoubleArray>, x: Double, y: Double): Boolean {
	var inside = false

	for (ring in rings) {
		val vertexCount = ring.size / 2

		for (vertex in 0 until vertexCount) {
			val next = (vertex + 1) % vertexCount
			val fromX = ring[2 * vertex]
			val fromY = ring[2 * vertex + 1]
			val toX = ring[2 * next]
			val toY = ring[2 * next + 1]

			if ((fromY > y) != (toY > y)) {
				val crossingX = fromX + (y - fromY) * (toX - fromX) / (toY - fromY)

				if (crossingX > x) {
					inside = !inside
				}
			}
		}
	}

	return inside
}

/**
 * A ring's signed shoelace area.
 *
 * @param DoubleArray ring The ring.
 * @return Double The signed area.
 */
internal fun ringArea(ring: DoubleArray): Double {
	val vertexCount = ring.size / 2
	var doubledArea = 0.0

	for (vertex in 0 until vertexCount) {
		val next = (vertex + 1) % vertexCount
		doubledArea += ring[2 * vertex] * ring[2 * next + 1] - ring[2 * next] * ring[2 * vertex + 1]
	}

	return doubledArea / 2.0
}

/**
 * A value squared.
 *
 * @param Double value The value.
 * @return Double Its square.
 */
internal fun squared(value: Double): Double = value * value