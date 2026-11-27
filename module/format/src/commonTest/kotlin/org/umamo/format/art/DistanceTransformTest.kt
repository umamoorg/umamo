package org.umamo.format.art

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The squared Euclidean distance transform against brute force on random grids, including grids
 * with a single feature, no features, and features only.
 */
class DistanceTransformTest {
	@Test
	fun randomGridsMatchBruteForce() {
		val random = Random(107)

		repeat(200) {
			val columns = random.nextInt(1, 24)
			val rows = random.nextInt(1, 24)
			val density = random.nextDouble(0.01, 0.6)
			val cells = BooleanArray(columns * rows) { random.nextDouble() < density }
			val computed = IntArray(columns * rows)
			squaredDistanceToFeatures(cells, true, columns, rows, computed)
			assertContentEquals(bruteForce(cells, true, columns, rows), computed, "$columns x $rows grid")
		}
	}

	@Test
	fun eitherFlagCanBeTheFeature() {
		val random = Random(109)
		val columns = 17
		val rows = 13
		val cells = BooleanArray(columns * rows) { random.nextBoolean() }
		val computed = IntArray(columns * rows)
		squaredDistanceToFeatures(cells, false, columns, rows, computed)
		assertContentEquals(bruteForce(cells, false, columns, rows), computed)
	}

	@Test
	fun aGridWithoutFeaturesIsUnreached() {
		val computed = IntArray(12)
		squaredDistanceToFeatures(BooleanArray(12), true, 4, 3, computed)
		assertTrue(computed.all { it == UNREACHED })
	}

	@Test
	fun aGridOfFeaturesIsAllZero() {
		val computed = IntArray(12) { -1 }
		squaredDistanceToFeatures(BooleanArray(12) { true }, true, 4, 3, computed)
		assertTrue(computed.all { it == 0 })
	}

	@Test
	fun aSingleFeatureGivesSquaredDistances() {
		val cells = BooleanArray(25)
		cells[2 * 5 + 2] = true
		val computed = IntArray(25)
		squaredDistanceToFeatures(cells, true, 5, 5, computed)
		assertEquals(8, computed[0])
		assertEquals(1, computed[2 * 5 + 3])
		assertEquals(4, computed[0 * 5 + 2])
	}

	/**
	 * Squared distances to the nearest feature by scanning every pair.
	 *
	 * @param BooleanArray cells       The flags.
	 * @param Boolean      featureFlag The flag marking a feature.
	 * @param Int          columns     The grid width.
	 * @param Int          rows        The grid height.
	 * @return IntArray The squared distances, UNREACHED without features.
	 */
	private fun bruteForce(cells: BooleanArray, featureFlag: Boolean, columns: Int, rows: Int): IntArray =
		IntArray(columns * rows) { cell ->
			var nearest = UNREACHED

			for (other in cells.indices) {
				if (cells[other] == featureFlag) {
					val deltaColumn = other % columns - cell % columns
					val deltaRow = other / columns - cell / columns
					nearest = minOf(nearest, deltaColumn * deltaColumn + deltaRow * deltaRow)
				}
			}

			nearest
		}
}