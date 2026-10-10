package org.umamo.format.art

import kotlin.math.sqrt
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The signed distance field: its zero on the shared edge, signs, values against brute force,
 * max-pooling, the aligned padded crop reaching past the raster, seeds, the grid cap, and sampling.
 */
class AlphaFieldTest {
	@Test
	fun nothingOpaqueMakesNoField() {
		val empty = rasterOfRows("....", "....")
		assertNull(empty.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2))
		assertNull(empty.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2, seeds = doubleArrayOf(1.5, 0.5)))
		// Faint pixels below the threshold do not count either.
		assertNull(rasterOfRows("...", ".5.", "...").alphaField(alphaThreshold = 16, cellSize = 1, paddingCells = 2))
	}

	@Test
	fun aSinglePixelsFieldCrossesZeroOnItsEdges() {
		val raster = rasterOfRows(".....", ".....", "..#..", ".....", ".....")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 3))
		assertEquals(-0.5, field.valueAt(2.5, 2.5), 1e-6)
		assertEquals(0.5, field.valueAt(3.5, 2.5), 1e-6)
		assertEquals(sqrt(2.0) - 0.5, field.valueAt(3.5, 3.5), 1e-6)
		// Halfway between the pixel's center and its neighbor's: the pixel's left edge.
		assertEquals(0.0, field.valueAt(2.0, 2.5), 1e-6)
		assertEquals(1, field.opaquePixelCount)
	}

	@Test
	fun fieldValuesMatchBruteForceDistances() {
		val random = Random(113)

		repeat(30) {
			val raster = randomBlobRaster(random, random.nextInt(4, 20), random.nextInt(4, 20))
			val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 3))
			val opaque = opaquePixelsOf(raster)

			for (row in 0 until field.rows) {
				for (column in 0 until field.columns) {
					val centerX = field.gridLeft + column + 0.5
					val centerY = field.gridTop + row + 0.5
					val pixelIsOpaque = field.isOpaquePixel(field.gridLeft + column, field.gridTop + row)
					val expected =
						if (pixelIsOpaque) {
							-(nearestCenter(centerX, centerY, field, wantOpaque = false) - 0.5)
						} else {
							nearestCenter(centerX, centerY, opaque) - 0.5
						}
					assertEquals(expected, field.cellValue(column, row), 1e-5, "cell ($column, $row)")
				}
			}
		}
	}

	@Test
	fun theGridIsAlignedPaddedAndReachesPastTheRaster() {
		// Art in the raster's corner: the padded grid must extend into negative coordinates.
		val raster = rasterOfRows("#..", "...", "...")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 2, paddingCells = 3))
		assertEquals(2, field.cellSize)
		assertEquals(-6, field.gridLeft)
		assertEquals(-6, field.gridTop)
		assertEquals(7, field.columns)
		assertEquals(7, field.rows)
		assertTrue(field.cellValue(0, 0) > 0.0)
		assertFalse(field.isOpaquePixel(-1, 0))
		assertTrue(field.isOpaquePixel(0, 0))
	}

	@Test
	fun maxPoolingMarksACellOpaqueForOnePixel() {
		val raster = rasterOfMask(16, 16) { column, row -> column == 5 && row == 6 }
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 4, paddingCells = 2))
		val cellColumn = (5 - field.gridLeft) / 4
		val cellRow = (6 - field.gridTop) / 4
		assertEquals(-2.0, field.cellValue(cellColumn, cellRow), 1e-6)
		assertEquals(2.0, field.cellValue(cellColumn + 1, cellRow), 1e-6)
	}

	@Test
	fun seedsCountAsOpaqueButNotAsArt() {
		val raster = rasterOfRows("....", ".#..", "....")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2, seeds = doubleArrayOf(-3.5, 10.25)))
		assertTrue(field.isOpaquePixel(-4, 10))
		assertTrue(field.gridLeft <= -6 && field.gridTop + field.rows > 12)
		assertTrue(field.valueAt(-3.5, 10.5) < 0.0)
		assertEquals(1, field.opaquePixelCount)
	}

	@Test
	fun theGridCapGrowsTheCell() {
		val raster = rasterOfMask(100, 3) { _, row -> row == 1 }
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2, maximumGridSide = 20))
		assertTrue(field.cellSize > 1)
		assertTrue(field.columns <= 20 && field.rows <= 20)
		assertEquals(0, field.gridLeft % field.cellSize)
	}

	@Test
	fun samplingIsInfiniteOutsideTheGridAndExactAtCellCenters() {
		val raster = rasterOfRows("....", ".##.", ".##.", "....")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2))
		assertEquals(Double.POSITIVE_INFINITY, field.valueAt(field.gridLeft - 1.0, 0.0))
		assertEquals(Double.POSITIVE_INFINITY, field.valueAt(0.0, (field.gridTop + field.rows + 1).toDouble()))

		for (row in 0 until field.rows) {
			for (column in 0 until field.columns) {
				assertEquals(field.cellValue(column, row), field.valueAt(field.gridLeft + column + 0.5, field.gridTop + row + 0.5), 1e-9)
			}
		}
	}

	@Test
	fun malformedRequestsAreRejected() {
		val raster = rasterOfRows("#")
		assertFailsWith<IllegalArgumentException> { raster.alphaField(alphaThreshold = 0, cellSize = 1, paddingCells = 2) }
		assertFailsWith<IllegalArgumentException> { raster.alphaField(alphaThreshold = 1, cellSize = 0, paddingCells = 2) }
		assertFailsWith<IllegalArgumentException> { raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 0) }
		assertFailsWith<IllegalArgumentException> { raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2, seeds = doubleArrayOf(1.0)) }
		assertFailsWith<IllegalArgumentException> { raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 2, seeds = doubleArrayOf(Double.NaN, 0.0)) }
	}

	/**
	 * The distance from a point to the nearest center of an opaque pixel.
	 *
	 * @param Double         x      The point's x.
	 * @param Double         y      The point's y.
	 * @param List<IntArray> pixels The opaque pixels.
	 * @return Double The distance.
	 */
	private fun nearestCenter(x: Double, y: Double, pixels: List<IntArray>): Double = pixels.minOf { sqrt(squared(it[0] + 0.5 - x) + squared(it[1] + 0.5 - y)) }

	/**
	 * The distance from a point to the nearest center of a field cell of one kind, over the whole grid.
	 *
	 * @param Double     x          The point's x.
	 * @param Double     y          The point's y.
	 * @param AlphaField field      The field (cell size 1).
	 * @param Boolean    wantOpaque Which kind of cell to measure to.
	 * @return Double The distance.
	 */
	private fun nearestCenter(x: Double, y: Double, field: AlphaField, wantOpaque: Boolean): Double {
		var nearest = Double.POSITIVE_INFINITY

		for (row in 0 until field.rows) {
			for (column in 0 until field.columns) {
				if (field.isOpaquePixel(field.gridLeft + column, field.gridTop + row) == wantOpaque) {
					nearest = minOf(nearest, sqrt(squared(field.gridLeft + column + 0.5 - x) + squared(field.gridTop + row + 0.5 - y)))
				}
			}
		}

		return nearest
	}
}