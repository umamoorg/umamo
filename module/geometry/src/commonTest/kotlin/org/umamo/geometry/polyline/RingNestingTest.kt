package org.umamo.geometry.polyline

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Exact point-in-ring and the outermost-ring selection: inside and outside on both orientations, rays
 * through vertices counted once, points one ulp either side of a slanted edge, nesting three deep
 * beside a sibling, and input order.
 */
class RingNestingTest {
	private val box = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 10.0, 0.0, 10.0)

	@Test
	fun aSquareContainsItsCenterOnEitherOrientation() {
		val reversed = doubleArrayOf(0.0, 10.0, 10.0, 10.0, 10.0, 0.0, 0.0, 0.0)

		for (ring in listOf(box, reversed)) {
			assertTrue(ringContainsPoint(ring, 5.0, 5.0))
			assertFalse(ringContainsPoint(ring, 15.0, 5.0))
			assertFalse(ringContainsPoint(ring, -5.0, 5.0))
			assertFalse(ringContainsPoint(ring, 5.0, 15.0))
		}
	}

	@Test
	fun aRayThroughAVertexCountsItOnce() {
		// A diamond: the ray from (2, 5) runs straight through the vertex at (10, 5), and the ray from
		// (-2, 5) through both side vertices.
		val diamond = doubleArrayOf(5.0, 0.0, 10.0, 5.0, 5.0, 10.0, 0.0, 5.0)
		assertTrue(ringContainsPoint(diamond, 2.0, 5.0))
		assertTrue(ringContainsPoint(diamond, 8.0, 5.0))
		assertFalse(ringContainsPoint(diamond, -2.0, 5.0))
		assertFalse(ringContainsPoint(diamond, 12.0, 5.0))
	}

	@Test
	fun pointsOneUlpEitherSideOfASlantedEdgeAreDecidedExactly() {
		// The triangle (0, 0), (3, 3), (0, 3) holds the points above the diagonal y = x.
		val triangle = doubleArrayOf(0.0, 0.0, 3.0, 3.0, 0.0, 3.0)
		val random = Random(167)

		repeat(200) {
			val along = random.nextDouble(0.1, 2.9)
			assertTrue(ringContainsPoint(triangle, along, oneUlpUp(along)), "($along, ${oneUlpUp(along)}) is above the diagonal")
			assertFalse(ringContainsPoint(triangle, oneUlpUp(along), along), "(${oneUlpUp(along)}, $along) is below the diagonal")
		}
	}

	@Test
	fun theOutermostRingsSkipHolesAndWhatTheyHold() {
		val outer = square(0.0, 0.0, 100.0)
		val hole = square(10.0, 10.0, 80.0).reversedRing()
		val island = square(20.0, 20.0, 60.0)
		val islandHole = square(30.0, 30.0, 40.0).reversedRing()
		val sibling = square(150.0, 0.0, 20.0)
		val rings = listOf(island, sibling, islandHole, outer, hole)
		assertContentEquals(intArrayOf(1, 3), outermostRings(rings))
		assertContentEquals(intArrayOf(0, 1), outermostRings(listOf(outer, sibling)))
		assertContentEquals(intArrayOf(), outermostRings(emptyList()))
	}

	@Test
	fun aRingBesideAnotherInsideItsBoxIsStillOutermost() {
		// An L-shaped ring whose bounding box holds a small square that sits outside the L itself.
		val shapeL = doubleArrayOf(0.0, 0.0, 10.0, 0.0, 10.0, 2.0, 2.0, 2.0, 2.0, 10.0, 0.0, 10.0)
		val nook = square(5.0, 5.0, 2.0)
		assertContentEquals(intArrayOf(0, 1), outermostRings(listOf(shapeL, nook)))
		// One inside the L's upright arm is enclosed.
		assertContentEquals(intArrayOf(1), outermostRings(listOf(square(0.5, 4.0, 1.0), shapeL)))
	}

	@Test
	fun malformedRingsAreRejected() {
		assertFailsWith<IllegalArgumentException> { outermostRings(listOf(doubleArrayOf(0.0, 0.0, 1.0, 1.0))) }
		assertFailsWith<IllegalArgumentException> { ringContainsPoint(doubleArrayOf(0.0, 0.0, 1.0), 0.0, 0.0) }
	}

	/**
	 * An axis-aligned square ring, counterclockwise in raw coordinates.
	 *
	 * @param Double left The left edge.
	 * @param Double top  The top edge.
	 * @param Double side The side length.
	 * @return DoubleArray The ring.
	 */
	private fun square(left: Double, top: Double, side: Double): DoubleArray = doubleArrayOf(left, top, left + side, top, left + side, top + side, left, top + side)

	/**
	 * The same ring traversed the other way.
	 *
	 * @return DoubleArray The reversed ring.
	 */
	private fun DoubleArray.reversedRing(): DoubleArray = DoubleArray(size) { component -> this[size - 2 - (component / 2) * 2 + component % 2] }

	/**
	 * The next double above a positive value.
	 *
	 * @param Double value A positive, finite value.
	 * @return Double Its upper neighbor.
	 */
	private fun oneUlpUp(value: Double): Double = Double.fromBits(value.toRawBits() + 1)
}