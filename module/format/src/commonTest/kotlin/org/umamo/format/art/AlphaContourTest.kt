package org.umamo.format.art

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Geometry tests for the boundary tracer, run at contourEpsilon 0 so rings are exact: the
 * lattice-corner convention, winding and the hole flag, the 4-connected saddle policy,
 * multi-island discovery, and the signed-area-equals-pixel-count invariant.
 */
class AlphaContourTest {
	@Test
	fun singlePixelTracesItsUnitSquare() {
		val analysis = assertNotNull(rasterOfRows("...", ".#.", "...").analyzeAlpha(contourEpsilon = 0f))
		assertEquals(1, analysis.contours.size)
		val contour = analysis.contours[0]
		// Corners of the unit square [1, 2] x [1, 2], clockwise on screen from the top-left.
		assertContentEquals(intArrayOf(1, 1, 2, 1, 2, 2, 1, 2), contour.points)
		assertEquals(2L, signedAreaTwice(contour.points))
		assertTrue(!contour.isHole, "single pixel is an outer contour")
		assertAnalysisInvariants(analysis, exactRings = true)
	}

	@Test
	fun fullRectTracesFourCorners() {
		val analysis = assertNotNull(rasterOfRows("###", "###").analyzeAlpha(contourEpsilon = 0f))
		assertEquals(1, analysis.contours.size)
		// Straight runs emit no interior vertices, so the exact ring is already minimal.
		assertContentEquals(intArrayOf(0, 0, 3, 0, 3, 2, 0, 2), analysis.contours[0].points)
		assertEquals(12L, signedAreaTwice(analysis.contours[0].points))
		assertAnalysisInvariants(analysis, exactRings = true)
	}

	@Test
	fun lShapeTracesSixCorners() {
		val analysis = assertNotNull(rasterOfRows("#.", "##").analyzeAlpha(contourEpsilon = 0f))
		assertEquals(1, analysis.contours.size)
		assertContentEquals(intArrayOf(0, 0, 1, 0, 1, 1, 2, 1, 2, 2, 0, 2), analysis.contours[0].points)
		assertEquals(analysis.opaquePixelCount.toLong() * 2L, signedAreaTwice(analysis.contours[0].points))
		assertAnalysisInvariants(analysis, exactRings = true)
	}

	@Test
	fun donutTracesOuterRingAndFlaggedHole() {
		val analysis = assertNotNull(rasterOfRows("###", "#.#", "###").analyzeAlpha(contourEpsilon = 0f))
		assertEquals(8, analysis.opaquePixelCount)
		assertEquals(2, analysis.contours.size)
		val outer = analysis.contours[0]
		val hole = analysis.contours[1]
		assertContentEquals(intArrayOf(0, 0, 3, 0, 3, 3, 0, 3), outer.points)
		assertEquals(18L, signedAreaTwice(outer.points))
		assertTrue(!outer.isHole, "outer ring is not a hole")
		// The hole's north edge sits under the hole (transparent above, opaque below), so its
		// walk starts at pixel (1, 2) and winds opposite to the outer ring.
		assertContentEquals(intArrayOf(1, 2, 2, 2, 2, 1, 1, 1), hole.points)
		assertEquals(-2L, signedAreaTwice(hole.points))
		assertTrue(hole.isHole, "inner ring is flagged as a hole")
		assertAnalysisInvariants(analysis, exactRings = true)
	}

	@Test
	fun diagonalSaddleSeparatesIntoUnitSquares() {
		// The 4-connected-foreground saddle policy: diagonal-only contact never merges, so
		// the checkerboard is two unit squares, not one corner-crossing zigzag.
		val analysis = assertNotNull(rasterOfRows("#.", ".#").analyzeAlpha(contourEpsilon = 0f))
		assertEquals(2, analysis.contours.size)
		assertContentEquals(intArrayOf(0, 0, 1, 0, 1, 1, 0, 1), analysis.contours[0].points)
		assertContentEquals(intArrayOf(1, 1, 2, 1, 2, 2, 1, 2), analysis.contours[1].points)
		assertEquals(2L, signedAreaTwice(analysis.contours[0].points))
		assertEquals(2L, signedAreaTwice(analysis.contours[1].points))
		assertAnalysisInvariants(analysis, exactRings = true)
	}

	@Test
	fun separateIslandsGetSeparateContoursAndUnionBounds() {
		val analysis = assertNotNull(rasterOfRows("#..#", "#..#").analyzeAlpha(contourEpsilon = 0f))
		assertEquals(2, analysis.contours.size)
		assertEquals(LayerBounds(left = 0, top = 0, width = 4, height = 2), analysis.opaqueBounds)
		assertEquals(4, analysis.opaquePixelCount)
		assertAnalysisInvariants(analysis, exactRings = true)
	}

	@Test
	fun tracingIsDeterministic() {
		val raster = rasterOfRows(".##.", "#..#", ".##.")
		val first = assertNotNull(raster.analyzeAlpha(contourEpsilon = 0f))
		val second = assertNotNull(raster.analyzeAlpha(contourEpsilon = 0f))
		assertEquals(first.contours.size, second.contours.size)
		for (contourIndex in first.contours.indices) {
			assertContentEquals(first.contours[contourIndex].points, second.contours[contourIndex].points)
		}
	}

	@Test
	fun complexSceneSatisfiesAllInvariants() {
		// Donut, vertical sliver, second donut, and a checkerboard row in one raster: the
		// invariant sweep is the real assertion; the shape mix exists to exercise saddles,
		// holes, slivers, and multi-island discovery together.
		val raster =
			rasterOfRows(
				".##..#......",
				"####.#..###.",
				"#..#.#..#.#.",
				"####.#..###.",
				".....#......",
				"#.#.#.#.#.#.",
			)
		val exact = assertNotNull(raster.analyzeAlpha(contourEpsilon = 0f))
		assertAnalysisInvariants(exact, exactRings = true)
		assertNoOpaquePixelOutside(raster, exact.opaqueBounds, DEFAULT_ALPHA_THRESHOLD)
		assertTrue(exact.contours.count { contour -> contour.isHole } == 2, "both donut holes found")

		// The simplified run keeps the structural invariants and only ever drops vertices.
		val simplified = assertNotNull(raster.analyzeAlpha())
		assertAnalysisInvariants(simplified, exactRings = false)
		assertEquals(exact.contours.size, simplified.contours.size)
		for (contourIndex in exact.contours.indices) {
			val exactPoints = exact.contours[contourIndex].points
			val exactPointSet = mutableSetOf<Long>()
			for (pointIndex in 0 until exactPoints.size / 2) {
				exactPointSet.add(pointKey(exactPoints[pointIndex * 2], exactPoints[pointIndex * 2 + 1]))
			}
			val simplifiedPoints = simplified.contours[contourIndex].points
			for (pointIndex in 0 until simplifiedPoints.size / 2) {
				assertTrue(
					pointKey(simplifiedPoints[pointIndex * 2], simplifiedPoints[pointIndex * 2 + 1]) in exactPointSet,
					"simplified vertices are a subset of the exact ring",
				)
			}
		}
	}

	/**
	 * Packs a lattice corner into one Long for set membership checks.
	 *
	 * @param Int x Lattice corner x.
	 * @param Int y Lattice corner y.
	 * @return Long The packed key.
	 */
	private fun pointKey(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFFFFFFL)
}
