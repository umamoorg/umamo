package org.umamo.format.art

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The clearance check and the cap check against independent brute force: clearance against a
 * segment-to-square distance built from segment-to-segment distances, the cap check against a
 * barycentric point-in-triangle scan.
 */
class SegmentClearanceTest {
	@Test
	fun squaredDistanceToSquareCases() {
		// A segment through the square, one passing beside it, one ending short of a corner.
		assertEquals(0.0, squaredDistanceToSquare(-1.0, 0.5, 2.0, 0.5, 0.0, 0.0))
		assertEquals(0.25, squaredDistanceToSquare(-1.0, 1.5, 2.0, 1.5, 0.0, 0.0), 1e-12)
		assertEquals(2.0, squaredDistanceToSquare(2.0, 2.0, 3.0, 3.0, 0.0, 0.0), 1e-12)
		assertEquals(0.0, squaredDistanceToSquare(0.25, 0.25, 0.75, 0.75, 0.0, 0.0))
	}

	@Test
	fun clearanceMatchesBruteForce() {
		val random = Random(131)
		var clearCount = 0
		var blockedCount = 0

		repeat(400) {
			val raster = randomBlobRaster(random, random.nextInt(4, 18), random.nextInt(4, 18))
			val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = random.nextInt(1, 3), paddingCells = 4))
			val opaque = opaquePixelsOf(raster)
			val startX = random.nextDouble(-6.0, raster.width + 6.0)
			val startY = random.nextDouble(-6.0, raster.height + 6.0)
			val endX = random.nextDouble(-6.0, raster.width + 6.0)
			val endY = random.nextDouble(-6.0, raster.height + 6.0)
			val required = random.nextDouble(0.0, 3.0)
			val nearest = opaque.minOf { pixel -> segmentToSquareDistance(startX, startY, endX, endY, pixel[0].toDouble(), pixel[1].toDouble()) }

			// Leave a hair of room on either side of the threshold for rounding.
			if (nearest > required + 1e-9) {
				assertTrue(field.segmentClears(startX, startY, endX, endY, required), "a segment $nearest away must clear $required")
				clearCount++
			} else if (nearest < required - 1e-9) {
				assertFalse(field.segmentClears(startX, startY, endX, endY, required), "a segment $nearest away must not clear $required")
				blockedCount++
			}
		}

		assertTrue(clearCount > 20 && blockedCount > 20, "both outcomes must be exercised: $clearCount clear, $blockedCount blocked")
	}

	@Test
	fun capCheckMatchesBruteForce() {
		val random = Random(137)
		var containingCount = 0

		repeat(400) {
			val raster = randomBlobRaster(random, random.nextInt(4, 18), random.nextInt(4, 18))
			val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2))
			val triangle = DoubleArray(6) { component -> random.nextDouble(-3.0, if (component % 2 == 0) raster.width + 3.0 else raster.height + 3.0) }
			val expected = opaquePixelsOf(raster).any { pixel -> strictlyInsideTriangle(triangle, pixel[0] + 0.5, pixel[1] + 0.5) }
			assertEquals(expected, field.containsOpaquePixelCenter(triangle))

			if (expected) {
				containingCount++
			}
		}

		assertTrue(containingCount > 20, "the containing case must be exercised: $containingCount")
	}

	@Test
	fun aCapAroundASpeckIsCaught() {
		// A thin sliver of a polygon around one isolated pixel's center, far from the chord ends.
		val raster = rasterOfRows("..........", "..........", ".....#....", "..........")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2))
		assertTrue(field.containsOpaquePixelCenter(doubleArrayOf(0.0, 2.4, 10.0, 2.4, 10.0, 2.6, 0.0, 2.6)))
		assertFalse(field.containsOpaquePixelCenter(doubleArrayOf(0.0, 3.0, 10.0, 3.0, 10.0, 3.9, 0.0, 3.9)))
	}

	/**
	 * The distance between a segment and a unit square, built differently from production: zero when
	 * either endpoint is inside or the segment crosses a square edge, else the least distance to the
	 * four square edges.
	 *
	 * @param Double startX The segment's start x.
	 * @param Double startY The segment's start y.
	 * @param Double endX   The segment's end x.
	 * @param Double endY   The segment's end y.
	 * @param Double left   The square's left edge.
	 * @param Double top    The square's top edge.
	 * @return Double The distance.
	 */
	private fun segmentToSquareDistance(startX: Double, startY: Double, endX: Double, endY: Double, left: Double, top: Double): Double {
		val inside = { x: Double, y: Double -> x in left..left + 1 && y in top..top + 1 }

		if (inside(startX, startY) || inside(endX, endY)) {
			return 0.0
		}

		val corners = doubleArrayOf(left, top, left + 1, top, left + 1, top + 1, left, top + 1)
		var nearest = Double.POSITIVE_INFINITY

		for (edge in 0 until 4) {
			val next = (edge + 1) % 4
			nearest = minOf(nearest, segmentToSegmentDistance(startX, startY, endX, endY, corners[2 * edge], corners[2 * edge + 1], corners[2 * next], corners[2 * next + 1]))
		}

		return nearest
	}

	/**
	 * The distance between two segments: zero when they cross, else the least endpoint-to-segment
	 * distance.
	 *
	 * @param Double firstStartX  The first segment's start x.
	 * @param Double firstStartY  The first segment's start y.
	 * @param Double firstEndX    The first segment's end x.
	 * @param Double firstEndY    The first segment's end y.
	 * @param Double secondStartX The second segment's start x.
	 * @param Double secondStartY The second segment's start y.
	 * @param Double secondEndX   The second segment's end x.
	 * @param Double secondEndY   The second segment's end y.
	 * @return Double The distance.
	 */
	private fun segmentToSegmentDistance(
		firstStartX: Double,
		firstStartY: Double,
		firstEndX: Double,
		firstEndY: Double,
		secondStartX: Double,
		secondStartY: Double,
		secondEndX: Double,
		secondEndY: Double,
	): Double {
		val crossStart = side(firstStartX, firstStartY, firstEndX, firstEndY, secondStartX, secondStartY)
		val crossEnd = side(firstStartX, firstStartY, firstEndX, firstEndY, secondEndX, secondEndY)
		val otherStart = side(secondStartX, secondStartY, secondEndX, secondEndY, firstStartX, firstStartY)
		val otherEnd = side(secondStartX, secondStartY, secondEndX, secondEndY, firstEndX, firstEndY)

		if (crossStart * crossEnd < 0.0 && otherStart * otherEnd < 0.0) {
			return 0.0
		}

		return minOf(
			pointToSegment(secondStartX, secondStartY, firstStartX, firstStartY, firstEndX, firstEndY),
			pointToSegment(secondEndX, secondEndY, firstStartX, firstStartY, firstEndX, firstEndY),
			pointToSegment(firstStartX, firstStartY, secondStartX, secondStartY, secondEndX, secondEndY),
			pointToSegment(firstEndX, firstEndY, secondStartX, secondStartY, secondEndX, secondEndY),
		)
	}

	/**
	 * The distance from a point to a segment.
	 *
	 * @param Double pointX The point's x.
	 * @param Double pointY The point's y.
	 * @param Double startX The segment's start x.
	 * @param Double startY The segment's start y.
	 * @param Double endX   The segment's end x.
	 * @param Double endY   The segment's end y.
	 * @return Double The distance.
	 */
	private fun pointToSegment(pointX: Double, pointY: Double, startX: Double, startY: Double, endX: Double, endY: Double): Double {
		val alongX = endX - startX
		val alongY = endY - startY
		val lengthSquared = alongX * alongX + alongY * alongY
		val fraction = if (lengthSquared > 0.0) (((pointX - startX) * alongX + (pointY - startY) * alongY) / lengthSquared).coerceIn(0.0, 1.0) else 0.0

		return sqrt(squared(startX + fraction * alongX - pointX) + squared(startY + fraction * alongY - pointY))
	}

	/**
	 * Which side of a directed line a point lies on.
	 *
	 * @param Double startX The line's start x.
	 * @param Double startY The line's start y.
	 * @param Double endX   The line's end x.
	 * @param Double endY   The line's end y.
	 * @param Double x      The point's x.
	 * @param Double y      The point's y.
	 * @return Double The cross product's sign and size.
	 */
	private fun side(startX: Double, startY: Double, endX: Double, endY: Double, x: Double, y: Double): Double = (endX - startX) * (y - startY) - (endY - startY) * (x - startX)

	/**
	 * Whether a point lies strictly inside a triangle (either winding).
	 *
	 * @param DoubleArray triangle The triangle, x0, y0, x1, y1, x2, y2.
	 * @param Double      x        The point's x.
	 * @param Double      y        The point's y.
	 * @return Boolean True strictly inside.
	 */
	private fun strictlyInsideTriangle(triangle: DoubleArray, x: Double, y: Double): Boolean {
		val first = side(triangle[0], triangle[1], triangle[2], triangle[3], x, y)
		val second = side(triangle[2], triangle[3], triangle[4], triangle[5], x, y)
		val third = side(triangle[4], triangle[5], triangle[0], triangle[1], x, y)

		return (first > 0.0 && second > 0.0 && third > 0.0) || (first < 0.0 && second < 0.0 && third < 0.0)
	}
}