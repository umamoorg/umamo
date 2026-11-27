package org.umamo.geometry.axis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The chordal axis: a strip is one branch from tip to tip whose chords run across it, a three-armed
 * star meets at one junction, a band around a hole is one closed loop, each side of a branch runs along
 * one stretch of the boundary, and the result is a function of the input alone.
 */
class ChordalAxisTest {
	@Test
	fun aStripIsOneBranchAcrossItFromTipToTip() {
		val points = polygon(doubleArrayOf(0.0, 0.0, 100.0, 0.0, 100.0, 10.0, 0.0, 10.0), step = 2.0)
		val axis = chordalAxis(points, listOf(ringOf(points)))
		val branch = axis.branches.single()

		assertEquals(AxisEnd.Tip, branch.startEnd)
		assertEquals(AxisEnd.Tip, branch.endEnd)
		assertEquals(3 * (branch.chordCount - 1), branch.sleeves.size)

		for (chord in 0 until branch.chordCount) {
			val firstY = points[2 * branch.sideA[chord] + 1]
			val secondY = points[2 * branch.sideB[chord] + 1]
			val middleX = (points[2 * branch.sideA[chord]] + points[2 * branch.sideB[chord]]) / 2.0

			if (middleX in 15.0..85.0) {
				assertTrue(firstY != secondY, "chord $chord runs across the strip")
			}
		}

		assertSidesFollowTheBoundary(branch, points.size / 2)
	}

	@Test
	fun aThreeArmedStarMeetsAtOneJunction() {
		val corners = ArrayList<Double>()

		for (arm in 0 until 3) {
			val angle = 2.0 * PI * arm / 3.0
			val across = angle + PI / 2.0

			// Each arm an 8-wide bar from 8 to 60 out from the center, counterclockwise: out along its
			// right side, back along its left.
			corners.add(-4.0 * cos(across) + 8.0 * cos(angle))
			corners.add(-4.0 * sin(across) + 8.0 * sin(angle))
			corners.add(-4.0 * cos(across) + 60.0 * cos(angle))
			corners.add(-4.0 * sin(across) + 60.0 * sin(angle))
			corners.add(4.0 * cos(across) + 60.0 * cos(angle))
			corners.add(4.0 * sin(across) + 60.0 * sin(angle))
			corners.add(4.0 * cos(across) + 8.0 * cos(angle))
			corners.add(4.0 * sin(across) + 8.0 * sin(angle))
		}

		val points = polygon(corners.toDoubleArray(), step = 2.0)
		val axis = chordalAxis(points, listOf(ringOf(points)))
		val arms = axis.branches.filter { branch -> branch.chordCount > 5 }

		assertEquals(3, arms.size, "${axis.branches.map { branch -> "${branch.startEnd}-${branch.endEnd}:${branch.chordCount}" }}")

		for (arm in arms) {
			assertEquals(setOf(AxisEnd.Junction, AxisEnd.Tip), setOf(arm.startEnd, arm.endEnd))
			assertSidesFollowTheBoundary(arm, points.size / 2)
		}
	}

	@Test
	fun aBandAroundAHoleIsOneLoop() {
		val outer = polygon(doubleArrayOf(0.0, 0.0, 60.0, 0.0, 60.0, 60.0, 0.0, 60.0), step = 2.0)
		val inner = polygon(doubleArrayOf(8.0, 8.0, 52.0, 8.0, 52.0, 52.0, 8.0, 52.0), step = 2.0)
		val points = outer + inner
		val outerCount = outer.size / 2
		val rings = listOf(IntArray(outerCount) { it }, IntArray(inner.size / 2) { outerCount + it })
		val axis = chordalAxis(points, rings)
		val loops = axis.branches.filter { branch -> branch.startEnd == AxisEnd.Loop }

		assertEquals(1, loops.size, "${axis.branches.map { branch -> "${branch.startEnd}-${branch.endEnd}:${branch.chordCount}" }}")
		val loop = loops.single()
		assertEquals(AxisEnd.Loop, loop.endEnd)
		assertEquals(3 * loop.chordCount, loop.sleeves.size, "a loop's sleeves close it")
		// Each side keeps to one ring.
		assertTrue(loop.sideA.all { vertex -> vertex < outerCount } != loop.sideA.all { vertex -> vertex >= outerCount })
		assertTrue(loop.sideB.all { vertex -> vertex < outerCount } != loop.sideB.all { vertex -> vertex >= outerCount })
	}

	@Test
	fun theAxisIsAFunctionOfItsInput() {
		val points = polygon(doubleArrayOf(0.0, 0.0, 100.0, 0.0, 120.0, 30.0, 60.0, 14.0, 0.0, 10.0), step = 1.5)
		val first = chordalAxis(points, listOf(ringOf(points)))
		val second = chordalAxis(points.copyOf(), listOf(ringOf(points)))

		assertEquals(first.branches.size, second.branches.size)

		for ((one, other) in first.branches.zip(second.branches)) {
			assertContentEquals(one.sideA, other.sideA)
			assertContentEquals(one.sideB, other.sideB)
			assertContentEquals(one.sleeves, other.sleeves)
		}
	}

	/**
	 * Asserts each side of a branch runs one way along the boundary in short steps: consecutive distinct
	 * vertices on one side are close on the ring (a pruned bump skips a few), all in the same direction.
	 *
	 * @param AxisBranch branch    The branch.
	 * @param Int        ringSize  The ring's vertex count (one ring of consecutive indices from 0).
	 */
	private fun assertSidesFollowTheBoundary(branch: AxisBranch, ringSize: Int) {
		for (side in listOf(branch.sideA, branch.sideB)) {
			val steps = ArrayList<Int>()

			for (chord in 1 until side.size) {
				if (side[chord] != side[chord - 1]) {
					val forward = (side[chord] - side[chord - 1] + ringSize) % ringSize
					steps.add(if (forward <= ringSize / 2) forward else forward - ringSize)
				}
			}

			assertTrue(steps.all { step -> step in 1..8 } || steps.all { step -> step in -8..-1 }, "the side runs one way along the ring: $steps")
		}
	}

	/**
	 * A polygon's boundary sampled at about a step apart, every corner kept.
	 *
	 * @param DoubleArray corners The corners, x0, y0, ...
	 * @param Double      step    The spacing.
	 * @return DoubleArray The sampled points.
	 */
	private fun polygon(corners: DoubleArray, step: Double): DoubleArray {
		val points = ArrayList<Double>()
		val count = corners.size / 2

		for (corner in 0 until count) {
			val next = (corner + 1) % count
			val deltaX = corners[2 * next] - corners[2 * corner]
			val deltaY = corners[2 * next + 1] - corners[2 * corner + 1]
			val pieces = maxOf(1, kotlin.math.round(kotlin.math.sqrt(deltaX * deltaX + deltaY * deltaY) / step).toInt())

			for (piece in 0 until pieces) {
				points.add(corners[2 * corner] + deltaX * piece / pieces)
				points.add(corners[2 * corner + 1] + deltaY * piece / pieces)
			}
		}

		return points.toDoubleArray()
	}

	/**
	 * One ring over every point in order.
	 *
	 * @param DoubleArray points The points.
	 * @return IntArray The ring.
	 */
	private fun ringOf(points: DoubleArray): IntArray = IntArray(points.size / 2) { it }
}