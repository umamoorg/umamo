package org.umamo.format.art

import kotlin.math.ceil
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Filling the field's holes: the exterior cells match the traced rings exactly (saddles included),
 * an annulus and a bullseye fill solid, the outer side is untouched, a wide inlet stays open while a
 * narrow one closes, the filled field never rises above the original, and art with nothing to fill
 * gets the same field back.
 */
class HoleFillingTest {
	@Test
	fun aCellIsExteriorExactlyWhenNoRingEnclosesIt() {
		val random = Random(173)

		repeat(25) {
			val raster = holeyRaster(random)

			for (cellSize in 1..3) {
				for (level in doubleArrayOf(0.5, 2.5, 4.0)) {
					val field = fieldOf(raster, cellSize, level)
					val values = FloatArray(field.columns * field.rows) { cell -> field.cellValue(cell % field.columns, cell / field.columns).toFloat() }
					val exterior = exteriorCells(values, field.columns, field.rows, level)
					val rings = field.isoRings(level)

					for (row in 0 until field.rows) {
						for (column in 0 until field.columns) {
							val centerX = field.gridLeft + (column + 0.5) * cellSize
							val centerY = field.gridTop + (row + 0.5) * cellSize
							val enclosed = rings.any { ring -> insideByEvenOdd(listOf(ring), centerX, centerY) }
							assertEquals(!enclosed, exterior[row * field.columns + column], "cell ($column, $row) at cell size $cellSize, level $level")
						}
					}
				}
			}
		}
	}

	@Test
	fun anAnnulusFillsSolid() {
		val field = fieldOf(annulus(withIsland = false), cellSize = 1, level = 3.0)
		assertEquals(2, field.isoRings(3.0).size)
		val filled = field.withHolesFilled(3.0)
		val rings = filled.isoRings(3.0)
		assertEquals(1, rings.size)
		assertTrue(ringArea(rings[0]) > 0.0)
		assertTrue(filled.valueAt(30.0, 30.0) < 0.0, "the hole's center is inside the silhouette")
	}

	@Test
	fun aBullseyeFillsToOneRing() {
		val field = fieldOf(annulus(withIsland = true), cellSize = 1, level = 3.0)
		assertEquals(3, field.isoRings(3.0).size)
		assertEquals(1, field.withHolesFilled(3.0).isoRings(3.0).size)
	}

	@Test
	fun theArtsOuterSideIsUntouched() {
		val field = fieldOf(annulus(withIsland = false), cellSize = 1, level = 3.0)
		val filled = field.withHolesFilled(3.0)

		for (row in 0 until field.rows) {
			for (column in 0 until field.columns) {
				val offsetX = field.gridLeft + column + 0.5 - 30.0
				val offsetY = field.gridTop + row + 0.5 - 30.0

				if (offsetX * offsetX + offsetY * offsetY >= 24.0 * 24.0) {
					assertEquals(field.cellValue(column, row), filled.cellValue(column, row), "cell ($column, $row)")
				}
			}
		}
	}

	@Test
	fun aWideInletStaysOpenAndANarrowOneCloses() {
		// A thick ring with a gap on its right side: 14 px wide stays open at level 3 (the outside
		// reaches in), 4 px is bridged by the contours and its inside fills.
		for ((gapWidth, fills) in listOf(14 to false, 4 to true)) {
			val raster =
				rasterOfMask(80, 80) { column, row ->
					val offsetX = column + 0.5 - 40.0
					val offsetY = row + 0.5 - 40.0
					val radiusSquared = offsetX * offsetX + offsetY * offsetY
					val inGap = offsetX > 0.0 && offsetY > -gapWidth / 2.0 && offsetY < gapWidth / 2.0
					radiusSquared <= 30.0 * 30.0 && radiusSquared >= 14.0 * 14.0 && !inGap
				}
			val filled = fieldOf(raster, cellSize = 1, level = 3.0).withHolesFilled(3.0)

			if (fills) {
				assertTrue(filled.valueAt(40.0, 40.0) < 0.0, "a $gapWidth px inlet closes")
			} else {
				assertTrue(filled.valueAt(40.0, 40.0) >= 3.0, "a $gapWidth px inlet stays open")
			}
		}
	}

	@Test
	fun fillingNeverRaisesTheFieldAndFillsOnlyEnclosedCells() {
		val random = Random(179)

		repeat(25) {
			val raster = holeyRaster(random)

			for (cellSize in 1..3) {
				for (level in doubleArrayOf(0.5, 2.5, 4.0)) {
					val field = fieldOf(raster, cellSize, level)
					val filled = field.withHolesFilled(level)
					val rings = field.isoRings(level)

					for (row in 0 until field.rows) {
						for (column in 0 until field.columns) {
							val before = field.cellValue(column, row)
							val after = filled.cellValue(column, row)
							assertTrue(after <= before, "cell ($column, $row) rose from $before to $after")

							if (before >= 0.0 && after < 0.0) {
								val centerX = field.gridLeft + (column + 0.5) * cellSize
								val centerY = field.gridTop + (row + 0.5) * cellSize
								assertTrue(rings.any { ring -> insideByEvenOdd(listOf(ring), centerX, centerY) }, "filled cell ($column, $row) lies outside every ring")
							}
						}
					}
				}
			}
		}
	}

	@Test
	fun artWithNothingToFillKeepsItsField() {
		val raster = rasterOfRows(".....", ".....", "..#..", ".....", ".....")
		val field = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = 1, paddingCells = 5))
		assertSame(field, field.withHolesFilled(3.0))
	}

	@Test
	fun malformedLevelsAreRejected() {
		val field = fieldOf(annulus(withIsland = false), cellSize = 1, level = 3.0)

		for (level in doubleArrayOf(0.0, -1.0, Double.NaN, Double.POSITIVE_INFINITY)) {
			assertFailsWith<IllegalArgumentException> { field.withHolesFilled(level) }
		}
	}

	/**
	 * A field padded enough for its contours at a level to close.
	 *
	 * @param LayerRaster raster   The raster.
	 * @param Int         cellSize The cell size.
	 * @param Double      level    The highest level the test traces.
	 * @return AlphaField The field.
	 */
	private fun fieldOf(raster: LayerRaster, cellSize: Int, level: Double): AlphaField = assertNotNull(raster.alphaField(alphaThreshold = 1, cellSize = cellSize, paddingCells = ceil(level / cellSize).toInt() + 3))

	/**
	 * A 60 px annulus around (30, 30), radii 24 and 14, optionally with a disc of radius 3 at its center.
	 *
	 * @param Boolean withIsland Whether to add the central disc.
	 * @return LayerRaster The raster.
	 */
	private fun annulus(withIsland: Boolean): LayerRaster =
		rasterOfMask(60, 60) { column, row ->
			val radiusSquared = squared(column + 0.5 - 30.0) + squared(row + 0.5 - 30.0)
			(radiusSquared <= 24.0 * 24.0 && radiusSquared >= 14.0 * 14.0) || (withIsland && radiusSquared <= 9.0)
		}

	/**
	 * Random art with real holes: a few large discs with smaller discs cut out, plus specks.
	 *
	 * @param Random random The source.
	 * @return LayerRaster The raster, never fully transparent.
	 */
	private fun holeyRaster(random: Random): LayerRaster {
		val width = random.nextInt(30, 60)
		val height = random.nextInt(30, 60)
		val discs = List(random.nextInt(1, 4)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(6.0, 16.0)) }
		val holes = List(random.nextInt(1, 5)) { doubleArrayOf(random.nextDouble(0.0, width.toDouble()), random.nextDouble(0.0, height.toDouble()), random.nextDouble(2.0, 7.0)) }
		val specks = List(random.nextInt(0, 6)) { intArrayOf(random.nextInt(width), random.nextInt(height)) }

		return rasterOfMask(width, height) { column, row ->
			val centerX = column + 0.5
			val centerY = row + 0.5
			val inDisc = discs.any { disc -> squared(centerX - disc[0]) + squared(centerY - disc[1]) <= squared(disc[2]) }
			val inHole = holes.any { hole -> squared(centerX - hole[0]) + squared(centerY - hole[1]) <= squared(hole[2]) }
			val isSpeck = specks.any { speck -> speck[0] == column && speck[1] == row }
			(inDisc && !inHole) || isSpeck || (column == width / 2 && row == height / 2)
		}
	}
}