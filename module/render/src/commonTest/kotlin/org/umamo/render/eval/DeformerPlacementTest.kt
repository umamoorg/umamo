package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull

/**
 * A deformer's placement as the Properties panel shows it: a warp's lattice bounds and a rotation's pivot in
 * world space (z up), a rotation's own angle and scale at a pose, and null wherever the renderer draws
 * nothing.
 */
class DeformerPlacementTest {
	private val tolerance = 1e-4f
	private val parameterA = ParameterId("A")
	private val parameterB = ParameterId("B")

	/**
	 * A 1 x 1 warp keyed once, at 0 on [keyedOn].
	 *
	 * @param String id The deformer's id.
	 * @param String? parent The parent deformer's id, or null at the root.
	 * @param FloatArray controlPoints The lattice's four points, row by row, in the parent's space.
	 * @param ParameterId keyedOn The parameter its one key sits on.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: String, parent: String?, controlPoints: FloatArray, keyedOn: ParameterId = parameterA): Deformer.Warp {
		val grid = KeyformGrid(listOf(KeyformAxis(keyedOn, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(controlPoints))))

		return Deformer.Warp(DeformerId(id), id, parent?.let(::DeformerId), null, 1, 1, true, grid)
	}

	/**
	 * A rotation keyed on A at 0 and 1, its pivot fixed and its angle and scale moving between the keys.
	 *
	 * @param String id The deformer's id.
	 * @param String? parent The parent deformer's id, or null at the root.
	 * @param Float baseAngle The base angle in degrees.
	 * @param Float originX The pivot's x in the parent's space.
	 * @param Float originY The pivot's y in the parent's space.
	 * @return Deformer.Rotation The rotation: angle 0 and scale 1 at A = 0, angle 20 and scale 3 at A = 1.
	 */
	private fun rotation(id: String, parent: String?, baseAngle: Float, originX: Float, originY: Float): Deformer.Rotation {
		val grid =
			KeyformGrid(
				listOf(KeyformAxis(parameterA, floatArrayOf(0f, 1f))),
				listOf(
					KeyformCell(intArrayOf(0), RotationPivotForm(originX, originY, 0f, 1f)),
					KeyformCell(intArrayOf(1), RotationPivotForm(originX, originY, 20f, 3f)),
				),
			)

		return Deformer.Rotation(DeformerId(id), id, parent?.let(::DeformerId), null, baseAngle, grid)
	}

	/**
	 * A model holding [deformers], with A and B each ranging over 0..1 and defaulting to 0.
	 *
	 * @param Deformer deformers The deformers.
	 * @return PuppetModel The model.
	 */
	private fun model(vararg deformers: Deformer): PuppetModel =
		PuppetModel(
			parameters =
				listOf(
					Parameter(parameterA, "A", min = 0f, max = 1f, default = 0f),
					Parameter(parameterB, "B", min = 0f, max = 1f, default = 0f),
				),
			parts = emptyList(),
			deformers = deformers.toList(),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** A root warp's bounds are its lattice's, with the cascade's downward y turned into world z. */
	@Test
	fun aWarpsBoundsAreItsLatticeInWorldZ() {
		val model = model(warp("W", null, floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f)))
		val placement = assertIs<DeformerPlacement.Warp>(deformerPlacement(model, emptyMap(), DeformerId("W")))
		assertEquals(10f, placement.worldMinX, tolerance)
		assertEquals(12f, placement.worldMaxX, tolerance)
		assertEquals(-24f, placement.worldMinZ, tolerance)
		assertEquals(-20f, placement.worldMaxZ, tolerance)
	}

	/** A root rotation's pivot is its origin in world z, and at the default pose its angle is the base angle. */
	@Test
	fun aRootRotationsPivotAndRestValues() {
		val model = model(rotation("R", null, baseAngle = 15f, originX = 30f, originY = 40f))
		val placement = assertIs<DeformerPlacement.Rotation>(deformerPlacement(model, emptyMap(), DeformerId("R")))
		assertEquals(30f, placement.worldPivotX, tolerance)
		assertEquals(-40f, placement.worldPivotZ, tolerance)
		assertEquals(15f, placement.angleDegrees, tolerance)
		assertEquals(1f, placement.scale, tolerance)
	}

	/** Between two keys the angle and scale blend, and the base angle still adds on. */
	@Test
	fun aRotationsOwnValuesFollowThePose() {
		val model = model(rotation("R", null, baseAngle = 15f, originX = 30f, originY = 40f))
		val placement = assertIs<DeformerPlacement.Rotation>(deformerPlacement(model, mapOf(parameterA to 0.5f), DeformerId("R")))
		assertEquals(25f, placement.angleDegrees, tolerance)
		assertEquals(2f, placement.scale, tolerance)
	}

	/**
	 * Under a warp the pivot goes through the lattice into world space, while the angle stays the rotation's
	 * own: the lattice turns everything a quarter turn, which the world angle carries and the shown one does
	 * not.
	 */
	@Test
	fun aRotationUnderAWarpShowsItsWorldPivotAndItsOwnAngle() {
		// The unit square turned a quarter turn about the origin, then moved 100 right.
		val turned = warp("W", null, floatArrayOf(100f, 0f, 100f, 1f, 99f, 0f, 99f, 1f))
		val child = rotation("R", "W", baseAngle = 30f, originX = 1f, originY = 0f)
		val placement = assertIs<DeformerPlacement.Rotation>(deformerPlacement(model(turned, child), emptyMap(), DeformerId("R")))
		assertEquals(100f, placement.worldPivotX, tolerance)
		assertEquals(-1f, placement.worldPivotZ, tolerance)
		assertEquals(30f, placement.angleDegrees, tolerance)
	}

	/** Nothing is placed for a deformer the model does not hold, one out of its keyed range, or one under it. */
	@Test
	fun nothingIsPlacedWhereTheRendererDrawsNothing() {
		val parent = rotation("R", null, baseAngle = 0f, originX = 0f, originY = 0f)
		// Keyed on B, which stays on its key, so only its parent can hide it.
		val child = warp("W", "R", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f), keyedOn = parameterB)
		val model = model(parent, child)
		assertIs<DeformerPlacement.Warp>(deformerPlacement(model, emptyMap(), DeformerId("W")), "precondition: W is placed at rest")
		assertNull(deformerPlacement(model, emptyMap(), DeformerId("absent")))
		assertNull(deformerPlacement(model, mapOf(parameterA to 2f), DeformerId("R")), "A = 2 is past R's last key")
		assertNull(deformerPlacement(model, mapOf(parameterA to 2f), DeformerId("W")), "and W hangs from R")
	}

	/** A parent cycle never bakes, as in the full cascade, so it places nothing rather than looping. */
	@Test
	fun aParentCyclePlacesNothing() {
		val first = warp("First", "Second", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		val second = warp("Second", "First", floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		assertNull(deformerPlacement(model(first, second), emptyMap(), DeformerId("First")))
	}
}