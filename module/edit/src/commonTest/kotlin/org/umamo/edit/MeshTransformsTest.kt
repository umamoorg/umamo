package org.umamo.edit

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the pure modal-operator math: each operator moves only the selected vertices by the expected
 * amount about the median pivot, leaves the unselected coordinates and the input array untouched
 * (purity / copy-on-write), and the pivot is the centroid of the selection.
 */
class MeshTransformsTest {
	// Four vertices of a 2x2 square: (0,0) (2,0) (0,2) (2,2), interleaved.
	private fun square(): FloatArray = floatArrayOf(0f, 0f, 2f, 0f, 0f, 2f, 2f, 2f)

	private fun assertClose(expected: Float, actual: Float, message: String) {
		assertTrue(abs(expected - actual) < 1e-4f, "$message (expected $expected, was $actual)")
	}

	/** The median pivot is the centroid of the selected vertices. */
	@Test
	fun medianPivotIsCentroidOfSelection() {
		val (allX, allY) = MeshTransforms.medianPivot(square(), setOf(0, 1, 2, 3))
		assertClose(1f, allX, "median x of all four")
		assertClose(1f, allY, "median y of all four")

		val (rightX, rightY) = MeshTransforms.medianPivot(square(), setOf(1, 3))
		assertClose(2f, rightX, "median x of the right edge")
		assertClose(1f, rightY, "median y of the right edge")
	}

	/** Translate moves only the selected vertices; the rest and the input array are untouched. */
	@Test
	fun translateMovesOnlySelected() {
		val input = square()
		val result = MeshTransforms.translateVertices(input, setOf(1), deltaX = 1f, deltaY = -1f)

		assertEquals(3f, result[2], "vertex 1 x moved by +1")
		assertEquals(-1f, result[3], "vertex 1 y moved by -1")
		assertEquals(0f, result[0], "vertex 0 untouched")
		assertEquals(2f, result[6], "vertex 3 untouched")
		// Purity: the input array is unchanged.
		assertEquals(2f, input[2], "input vertex 1 x unchanged")
		assertEquals(0f, input[3], "input vertex 1 y unchanged")
	}

	/** Collapse piles every selected vertex onto the target (no offsets kept); the rest are untouched. */
	@Test
	fun collapsePilesSelectedOntoTarget() {
		val input = square()
		val result = MeshTransforms.collapseVertices(input, setOf(0, 1, 3), targetX = 5f, targetY = 7f)

		// Unlike translateVertices, the inter-vertex offsets are gone: all three land on (5,7).
		for (vertexIndex in setOf(0, 1, 3)) {
			assertEquals(5f, result[vertexIndex * 2], "vertex $vertexIndex x piled on the target")
			assertEquals(7f, result[vertexIndex * 2 + 1], "vertex $vertexIndex y piled on the target")
		}
		assertEquals(0f, result[4], "vertex 2 x untouched")
		assertEquals(2f, result[5], "vertex 2 y untouched")
		// Purity: the input array is unchanged.
		assertEquals(0f, input[0], "input vertex 0 x unchanged")
	}

	/** Scale expands selected vertices about the pivot; unselected vertices stay put. */
	@Test
	fun scaleAboutPivot() {
		val input = square()
		val result = MeshTransforms.scaleVertices(input, setOf(0, 3), factor = 2f, pivotX = 1f, pivotY = 1f)

		// (0,0) about (1,1) by 2 -> (-1,-1); (2,2) -> (3,3).
		assertClose(-1f, result[0], "vertex 0 x scaled")
		assertClose(-1f, result[1], "vertex 0 y scaled")
		assertClose(3f, result[6], "vertex 3 x scaled")
		assertClose(3f, result[7], "vertex 3 y scaled")
		assertEquals(2f, result[2], "vertex 1 untouched")
		assertEquals(0f, input[0], "input unchanged")
	}

	/** Rotate turns selected vertices about the pivot; a 90 degree turn maps (2,0) about origin to (0,2). */
	@Test
	fun rotateAboutPivot() {
		val input = square()
		val result = MeshTransforms.rotateVertices(input, setOf(1), radians = (PI / 2).toFloat(), pivotX = 0f, pivotY = 0f)

		assertClose(0f, result[2], "vertex 1 x after 90 degree turn")
		assertClose(2f, result[3], "vertex 1 y after 90 degree turn")
		assertEquals(0f, result[0], "vertex 0 untouched")
		assertEquals(2f, input[2], "input unchanged")
	}

	/** The combined centroid across arrays is the mean of every vertex, weighted by vertex count. */
	@Test
	fun combinedCentroidIsVertexWeightedMean() {
		// One array centred at (1,1) with four vertices, plus a single-vertex array at (10,10). The mean of the
		// five vertices is ((0+2+0+2+10)/5, (0+0+2+2+10)/5) = (2.8, 2.8), pulled toward the denser square.
		val single = floatArrayOf(10f, 10f)
		val (centroidX, centroidY) = MeshTransforms.combinedCentroid(listOf(square(), single))
		assertClose(2.8f, centroidX, "combined centroid x weighted by vertex count")
		assertClose(2.8f, centroidY, "combined centroid y weighted by vertex count")
	}

	/** The combined centroid of an empty collection (or only empty arrays) is the origin. */
	@Test
	fun combinedCentroidEmptyIsOrigin() {
		val (emptyX, emptyY) = MeshTransforms.combinedCentroid(emptyList())
		assertEquals(0f, emptyX, "empty collection centroid x")
		assertEquals(0f, emptyY, "empty collection centroid y")

		val (blankX, blankY) = MeshTransforms.combinedCentroid(listOf(FloatArray(0), FloatArray(0)))
		assertEquals(0f, blankX, "only-empty-arrays centroid x")
		assertEquals(0f, blankY, "only-empty-arrays centroid y")
	}

	/** Weighted translate moves each vertex by its share of the delta; unweighted vertices stay put. */
	@Test
	fun weightedTranslateScalesTheDeltaPerVertex() {
		val input = square()
		val result = MeshTransforms.translateVerticesWeighted(input, mapOf(1 to 0.5f, 2 to 1f), deltaX = 4f, deltaY = -2f)

		assertClose(4f, result[2], "half-weight vertex 1 x moved by +2")
		assertClose(-1f, result[3], "half-weight vertex 1 y moved by -1")
		assertClose(4f, result[4], "full-weight vertex 2 x moved by +4")
		assertClose(0f, result[5], "full-weight vertex 2 y moved by -2")
		assertEquals(0f, result[0], "unweighted vertex 0 untouched")
		assertEquals(2f, input[2], "input unchanged")
	}

	/** Weighted scale lerps each vertex toward its fully-scaled position by its weight. */
	@Test
	fun weightedScaleLerpsTowardTheScaledPosition() {
		val input = square()
		val result = MeshTransforms.scaleVerticesWeighted(input, mapOf(0 to 0.5f), factorX = 3f, factorY = 3f, pivotX = 1f, pivotY = 1f)

		// (0,0) fully scaled about (1,1) by 3 lands on (-2,-2); half weight stops halfway at (-1,-1)
		// (the effective factor is 1 + (3-1) x 0.5 = 2).
		assertClose(-1f, result[0], "half-weight vertex 0 x scaled by the effective factor")
		assertClose(-1f, result[1], "half-weight vertex 0 y scaled by the effective factor")
		assertEquals(2f, result[2], "unweighted vertex 1 untouched")
	}

	/** Weighted rotate scales the ANGLE (arc), preserving each vertex's distance from the pivot. */
	@Test
	fun weightedRotateScalesTheAngleNotTheChord() {
		val input = square()
		val result = MeshTransforms.rotateVerticesWeighted(input, mapOf(1 to 0.5f), radians = (PI / 2).toFloat(), pivotX = 0f, pivotY = 0f)

		// (2,0) with half the 90 degree turn lands at 45 degrees: (sqrt(2), sqrt(2)) - still 2 from the
		// pivot, which a positional lerp (the chord midpoint (1,1)) would not be.
		val expected = (2.0 / sqrt(2.0)).toFloat()
		assertClose(expected, result[2], "half-weight vertex 1 x at 45 degrees")
		assertClose(expected, result[3], "half-weight vertex 1 y at 45 degrees")
		assertClose(2f, sqrt(result[2] * result[2] + result[3] * result[3]), "distance from the pivot preserved")
		assertEquals(0f, result[0], "unweighted vertex 0 untouched")
	}
}
