package org.umamo.geometry.mesh

import kotlin.math.sqrt
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * The planar mesh's validation and its quality measure: an equilateral triangle's 60 degrees, a
 * right isosceles triangle's 45, degenerate triangles counted apart, and the histogram buckets.
 */
class MeshQualityTest {
	@Test
	fun anEquilateralTriangleMeasuresSixtyDegrees() {
		val mesh = PlanarTriangleMesh(floatArrayOf(0f, 0f, 2f, 0f, 1f, sqrt(3.0).toFloat()), intArrayOf(0, 1, 2), IntArray(0))
		val quality = mesh.measureQuality()
		assertEquals(60.0, quality.minimumAngleDegrees, 1e-4)
		assertContentEquals(intArrayOf(0, 0, 0, 0, 0, 1), quality.smallestAngleHistogram)
	}

	@Test
	fun aRightIsoscelesTriangleMeasuresFortyFive() {
		val mesh = PlanarTriangleMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2), IntArray(0))
		val quality = mesh.measureQuality()
		assertEquals(45.0, quality.minimumAngleDegrees, 1e-9)
		assertEquals(1, quality.smallestAngleHistogram[4])
	}

	@Test
	fun degenerateTrianglesAreCountedApart() {
		val mesh = PlanarTriangleMesh(floatArrayOf(0f, 0f, 1f, 0f, 2f, 0f, 0f, 1f), intArrayOf(0, 1, 2, 0, 1, 3), IntArray(0))
		val quality = mesh.measureQuality()
		assertEquals(1, quality.degenerateTriangleCount)
		assertEquals(45.0, quality.minimumAngleDegrees, 1e-9)
		assertEquals(1, quality.smallestAngleHistogram.sum())
	}

	@Test
	fun anEmptyMeshMeasuresZero() {
		val quality = PlanarTriangleMesh(FloatArray(0), IntArray(0), IntArray(0)).measureQuality()
		assertEquals(0.0, quality.minimumAngleDegrees)
		assertEquals(0, quality.degenerateTriangleCount)
	}

	@Test
	fun malformedMeshesAreRejected() {
		assertFailsWith<IllegalArgumentException> { PlanarTriangleMesh(floatArrayOf(0f), IntArray(0), IntArray(0)) }
		assertFailsWith<IllegalArgumentException> { PlanarTriangleMesh(floatArrayOf(0f, 0f), intArrayOf(0, 0), IntArray(0)) }
		assertFailsWith<IllegalArgumentException> { PlanarTriangleMesh(floatArrayOf(0f, 0f), intArrayOf(0, 0, 1), IntArray(0)) }
		assertFailsWith<IllegalArgumentException> { PlanarTriangleMesh(floatArrayOf(0f, 0f), IntArray(0), intArrayOf(1)) }
	}
}