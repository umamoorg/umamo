package org.umamo.render.eval

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Unit tests for the warp (in-grid + extrapolation) and rotation transforms on synthetic lattices.
 * The identity unit-square lattice is linear everywhere, so it maps `(u,v)` to `(u,v)` both inside the
 * grid and under extrapolation - a clean oracle for the FFD math without the corpus.
 */
class DeformTransformsTest {
	private val tol = 1e-4f

	// Identity unit-square 1×1 lattice: cp00=(0,0) cp10=(1,0) cp01=(0,1) cp11=(1,1).
	private val identity = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

	@Test
	fun identityGridMapsUvToUvBilinear() {
		val out = FloatArray(2)
		assertTrue(warpInGrid(identity, 1, 1, true, 0.5f, 0.5f, out, 0))
		assertEquals(0.5f, out[0], tol)
		assertEquals(0.5f, out[1], tol)
		warpApply(identity, 1, 1, true, 0.25f, 0.75f, out, 0)
		assertEquals(0.25f, out[0], tol)
		assertEquals(0.75f, out[1], tol)
	}

	@Test
	fun identityGridMapsUvToUvTriangle() {
		val out = FloatArray(2)
		warpApply(identity, 1, 1, false, 0.25f, 0.25f, out, 0) // lower triangle (u+v <= 1)
		assertEquals(0.25f, out[0], tol)
		assertEquals(0.25f, out[1], tol)
		warpApply(identity, 1, 1, false, 0.7f, 0.6f, out, 0) // upper triangle (u+v > 1)
		assertEquals(0.7f, out[0], tol)
		assertEquals(0.6f, out[1], tol)
	}

	@Test
	fun warpReturnsFalseOutsideGrid() {
		val out = FloatArray(2)
		assertFalse(warpInGrid(identity, 1, 1, true, 1.5f, 0.5f, out, 0))
		assertFalse(warpInGrid(identity, 1, 1, true, -0.1f, 0.5f, out, 0))
	}

	@Test
	fun translatedScaledGridMapsCenter() {
		// A 1×1 lattice placed as a 2×4 rectangle at (10,20): corners (10,20)(12,20)(10,24)(12,24).
		val grid = floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f)
		val out = FloatArray(2)
		warpApply(grid, 1, 1, true, 0.5f, 0.5f, out, 0)
		assertEquals(11f, out[0], tol)
		assertEquals(22f, out[1], tol)
	}

	@Test
	fun identityGridExtrapolatesLinearly() {
		val out = FloatArray(2)
		// Far outside -> pure affine; the identity lattice is linear, so (u,v) maps to (u,v).
		warpApply(identity, 1, 1, true, 5f, 2f, out, 0)
		assertEquals(5f, out[0], tol)
		assertEquals(2f, out[1], tol)
		warpApply(identity, 1, 1, true, -3f, 4f, out, 0)
		assertEquals(-3f, out[0], tol)
		assertEquals(4f, out[1], tol)
	}

	@Test
	fun rotation90DegreesRotatesPoint() {
		val xform = rotationXform(90f, 1f, false, false, 0f, 0f)
		val out = FloatArray(2)
		xform.apply(1f, 0f, out, 0) // (1,0) rotated +90 -> (0,1)
		assertEquals(0f, out[0], tol)
		assertEquals(1f, out[1], tol)
		xform.apply(0f, 1f, out, 0) // (0,1) -> (-1,0)
		assertEquals(-1f, out[0], tol)
		assertEquals(0f, out[1], tol)
	}

	@Test
	fun rotationAppliesScaleFlipOrigin() {
		// angle 0, scale 2, flipX, origin (5,-3): x' = -2·x + 5, y' = 2·y - 3.
		val xform = rotationXform(0f, 2f, true, false, 5f, -3f)
		val out = FloatArray(2)
		xform.apply(1f, 1f, out, 0)
		assertEquals(3f, out[0], tol)
		assertEquals(-1f, out[1], tol)
	}
}
