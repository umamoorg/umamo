package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the pure bounds geometry behind the Properties panel's Position and Size rows: the rows READ
 * meshBounds and WRITE through these ops, so the invariant that matters is that asking for a value makes
 * the measurement become exactly that value.  Also pins the bounds center as the resize pivot (which is
 * what keeps the Position row from jumping when Size changes) and the degenerate-axis guard.
 *
 * These are space-agnostic - the Properties panel feeds them WORLD positions, and the session-level round
 * trip through the deformer chain is covered in :ui by DrawableWorldTransformTest.
 */
class ObjectBoundsEditsTest {
	private val drawableId = DrawableId("d")

	/** A 40 x 20 quad centered on (10, 5), deliberately asymmetric in x so the vertex mean is NOT the bounds center. */
	private val quadPositions =
		floatArrayOf(
			-10f,
			-5f,
			30f,
			-5f,
			30f,
			15f,
			-10f,
			15f,
			// A fifth vertex clustered left, to pull the vertex centroid away from the bounds center.
			-9f,
			14f,
		)

	private fun drawable(positions: FloatArray?): Drawable =
		Drawable(
			id = drawableId,
			name = "d",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = positions?.let { DrawableMesh(it, FloatArray(it.size), intArrayOf(0, 1, 2)) },
			keyforms = null,
		)

	private fun model(positions: FloatArray? = quadPositions.copyOf()): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawable(positions)),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	@Test
	fun boundsReportsTheCenterAndExtents() {
		val bounds = meshBounds(quadPositions)
		assertEquals(10f, bounds.centerX)
		assertEquals(5f, bounds.centerY)
		assertEquals(40f, bounds.width)
		assertEquals(20f, bounds.height)

		// An empty or single-coordinate array degrades to a zero box rather than throwing.
		val empty = meshBounds(floatArrayOf())
		assertEquals(0f, empty.width)
		assertEquals(0f, empty.height)
	}

	@Test
	fun movingLandsTheBoundsCenterExactlyOnTheRequestedPoint() {
		val moved = movedToBoundsCenter(quadPositions, -100f, 250f)
		val bounds = meshBounds(moved)
		// The whole contract of the Position row: what you type is what the readout becomes.
		assertEquals(-100f, bounds.centerX)
		assertEquals(250f, bounds.centerY)
		// Rigid: the extents are untouched.
		assertEquals(40f, bounds.width)
		assertEquals(20f, bounds.height)
	}

	@Test
	fun movingToTheCurrentCenterIsANoOp() {
		assertSame(quadPositions, movedToBoundsCenter(quadPositions, 10f, 5f))
		// An empty mesh has no center to move, so it comes back untouched (the same instance it was given).
		val empty = floatArrayOf()
		assertSame(empty, movedToBoundsCenter(empty, 1f, 1f))
	}

	@Test
	fun resizingLandsTheExtentsAndHoldsThePositionStill() {
		val resized = resizedAboutBoundsCenter(quadPositions, 80f, 5f)
		val bounds = meshBounds(resized)
		assertEquals(80f, bounds.width)
		assertEquals(5f, bounds.height)
		// The bounds center is the pivot, so the Position row does not move when Size is edited.  This is
		// what a vertex-centroid pivot would break: the extra clustered vertex pulls that mean off-center.
		assertEquals(10f, bounds.centerX)
		assertEquals(5f, bounds.centerY)
	}

	@Test
	fun resizingToTheCurrentExtentsIsANoOp() {
		assertSame(quadPositions, resizedAboutBoundsCenter(quadPositions, 40f, 20f))
	}

	@Test
	fun aDegenerateAxisIsLeftAloneRatherThanScaledByInfinity() {
		// Every vertex on one horizontal line: height is 0, so no factor exists for it.
		val flat = floatArrayOf(0f, 7f, 10f, 7f, 20f, 7f)
		val resized = resizedAboutBoundsCenter(flat, 40f, 100f)
		val bounds = meshBounds(resized)
		assertEquals(40f, bounds.width, "the well-defined axis still scales")
		assertEquals(0f, bounds.height, "the degenerate axis stays degenerate, not NaN or infinite")
		assertTrue(resized.all { coordinate -> coordinate.isFinite() }, "no infinity leaked into the geometry")
	}
}
