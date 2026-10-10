package org.umamo.format.art

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * Iso rings of the alpha field: exact positions on a single pixel, orientation of outer rings and
 * holes, enclosure of every opaque pixel, disjointness, the distance band, tie levels, saddles, and
 * the property the mesher's coverage rests on - at level m + 1.5 * cellSize every dense chord clears
 * m from every opaque pixel square.
 */
class IsoContourTest {
	@Test
	fun aSinglePixelAtLevelZeroRunsThroughItsEdgeMidpoints() {
		val raster = rasterOfRows(".....", ".....", "..#..", ".....", ".....")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 3))
		val rings = field.isoRings(0.0)
		assertEquals(1, rings.size)
		val points = (0 until rings[0].size / 2).map { rings[0][2 * it] to rings[0][2 * it + 1] }.toSet()
		assertEquals(setOf(2.5 to 2.0, 3.0 to 2.5, 2.5 to 3.0, 2.0 to 2.5), points)
		assertTrue(ringArea(rings[0]) > 0.0)
	}

	@Test
	fun outerRingsArePositiveAndHolesNegative() {
		val raster = rasterOfMask(12, 12) { column, row -> column in 2..9 && row in 2..9 && !(column in 5..6 && row in 5..6) }
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 3))
		val rings = field.isoRings(0.0)
		assertEquals(2, rings.size)
		assertEquals(1, rings.count { ringArea(it) > 0.0 })
		assertEquals(1, rings.count { ringArea(it) < 0.0 })
	}

	@Test
	fun ringsInsideTheArtTraceItsInterior() {
		val raster = rasterOfMask(30, 30) { column, row -> column in 5..24 && row in 5..24 }
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 3))
		val rings = field.isoRings(-3.0)
		assertEquals(1, rings.size)
		assertTrue(ringArea(rings[0]) > 0.0)

		for (vertex in 0 until rings[0].size / 2) {
			val x = rings[0][2 * vertex]
			val y = rings[0][2 * vertex + 1]
			assertTrue(x in 7.0..23.0 && y in 7.0..23.0, "inner vertex ($x, $y) is not about three pixels in")
		}
	}

	@Test
	fun denseRingsAtTheMarginLevelEncloseEveryPixelAndClearTheMargin() {
		val random = Random(127)

		for (cellSize in 1..3) {
			for (margin in doubleArrayOf(0.5, 1.0, 2.0)) {
				repeat(8) {
					val raster = randomBlobRaster(random, random.nextInt(6, 26), random.nextInt(6, 26))
					val level = margin + 1.5 * cellSize
					val paddingCells = (level / cellSize).toInt() + 3
					val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = cellSize, paddingCells = paddingCells))
					val rings = field.isoRings(level)
					val opaque = opaquePixelsOf(raster)

					for (pixel in opaque) {
						for (corner in 0 until 4) {
							val x = pixel[0] + (corner and 1) * 1.0
							val y = pixel[1] + (corner shr 1) * 1.0
							assertTrue(insideByEvenOdd(rings, x, y), "s=$cellSize m=$margin: pixel corner ($x, $y) is outside the rings")
						}
					}

					for (ring in rings) {
						val vertexCount = ring.size / 2

						for (vertex in 0 until vertexCount) {
							val next = (vertex + 1) % vertexCount
							assertTrue(
								field.segmentClears(ring[2 * vertex], ring[2 * vertex + 1], ring[2 * next], ring[2 * next + 1], margin),
								"s=$cellSize m=$margin: a dense chord comes closer than the margin",
							)
							val distance = bruteDistanceToSquares(opaque, ring[2 * vertex], ring[2 * vertex + 1])
							assertTrue(distance >= level - 1.5 * cellSize && distance <= level + 2.5 * cellSize, "s=$cellSize m=$margin: vertex at distance $distance for level $level")
						}
					}

					assertRingsDisjoint(rings)
				}
			}
		}
	}

	@Test
	fun aTieLevelStillTracesClosedDisjointRings() {
		// With cellSize 2 a cell three cells away holds exactly 2 * 3 - 1 = 5, the level itself.
		val raster = rasterOfMask(20, 20) { column, row -> column in 8..11 && row in 8..11 }
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 2, paddingCells = 6))
		var tieFound = false

		for (row in 0 until field.rows) {
			for (column in 0 until field.columns) {
				if (field.cellValue(column, row) == 5.0) {
					tieFound = true
				}
			}
		}

		assertTrue(tieFound, "the fixture must put a node exactly on the level")
		val rings = field.isoRings(5.0)
		assertEquals(1, rings.size)
		assertRingsDisjoint(rings)

		for (row in 8..11) {
			for (column in 8..11) {
				assertTrue(insideByEvenOdd(rings, column + 0.5, row + 0.5))
			}
		}
	}

	@Test
	fun saddlesAreDecidedTheSameWayEveryTime() {
		// Two pixels touching only at a corner: at level zero the saddle's center average is exactly
		// the level, which counts as outside, so the pixels stay separate rings.
		val raster = rasterOfRows("....", ".#..", "..#.", "....")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2))
		val first = field.isoRings(0.0)
		val second = field.isoRings(0.0)
		assertEquals(2, first.size)
		assertEquals(first.size, second.size)

		for (index in first.indices) {
			assertContentEquals(first[index], second[index])
		}

		assertRingsDisjoint(first)
	}

	/**
	 * Asserts that no two ring edges cross or touch, apart from consecutive edges of one ring meeting
	 * at their shared vertex.
	 *
	 * @param List<DoubleArray> rings The rings.
	 */
	private fun assertRingsDisjoint(rings: List<DoubleArray>) {
		val edges = ArrayList<DoubleArray>()
		val owners = ArrayList<IntArray>()

		for ((ringIndex, ring) in rings.withIndex()) {
			val vertexCount = ring.size / 2

			for (vertex in 0 until vertexCount) {
				val next = (vertex + 1) % vertexCount
				edges.add(doubleArrayOf(ring[2 * vertex], ring[2 * vertex + 1], ring[2 * next], ring[2 * next + 1]))
				owners.add(intArrayOf(ringIndex, vertex, vertexCount))
			}
		}

		for (first in edges.indices) {
			for (second in first + 1 until edges.size) {
				val sameRing = owners[first][0] == owners[second][0]
				val vertexCount = owners[first][2]
				val adjacent = sameRing && ((owners[first][1] + 1) % vertexCount == owners[second][1] || (owners[second][1] + 1) % vertexCount == owners[first][1])

				if (!adjacent && segmentsTouch(edges[first], edges[second])) {
					fail("ring edges $first and $second meet")
				}
			}
		}
	}

	/**
	 * Whether two segments share any point, by orientation signs.
	 *
	 * @param DoubleArray first  The first segment, x0, y0, x1, y1.
	 * @param DoubleArray second The second segment.
	 * @return Boolean True when they intersect or touch.
	 */
	private fun segmentsTouch(first: DoubleArray, second: DoubleArray): Boolean {
		val firstSideOfStart = cross(first, second[0], second[1])
		val firstSideOfEnd = cross(first, second[2], second[3])
		val secondSideOfStart = cross(second, first[0], first[1])
		val secondSideOfEnd = cross(second, first[2], first[3])

		return firstSideOfStart * firstSideOfEnd <= 0.0 &&
			secondSideOfStart * secondSideOfEnd <= 0.0 &&
			minOf(first[0], first[2]) <= maxOf(second[0], second[2]) &&
			minOf(second[0], second[2]) <= maxOf(first[0], first[2]) &&
			minOf(first[1], first[3]) <= maxOf(second[1], second[3]) &&
			minOf(second[1], second[3]) <= maxOf(first[1], first[3])
	}

	/**
	 * The cross product of a segment's direction with the vector to a point.
	 *
	 * @param DoubleArray segment The segment.
	 * @param Double      x       The point's x.
	 * @param Double      y       The point's y.
	 * @return Double The cross product.
	 */
	private fun cross(segment: DoubleArray, x: Double, y: Double): Double = (segment[2] - segment[0]) * (y - segment[1]) - (segment[3] - segment[1]) * (x - segment[0])
}