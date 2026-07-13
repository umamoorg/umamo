package org.umamo.render.eval

import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.RotationForm
import org.umamo.runtime.model.WarpForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Cascade tests on synthetic single-level and nested hierarchies. A mesh's local verts are pushed
 * through its parent deformer's baked world transform and Y-flipped; nesting composes the transforms
 * top-down.
 */
class DeformerCascadeTest {
	private val tol = 1e-4f
	private val paramA = ParameterId("A")

	private fun values(vararg pairs: Pair<ParameterId, Float>): (ParameterId) -> Float {
		val map = pairs.toMap()
		return { map[it] ?: 0f }
	}

	private fun singleKeyAxis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun warp(id: String, parent: String?, cols: Int, rows: Int, bilinear: Boolean, cp: FloatArray): Deformer.Warp {
		val grid = KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), WarpForm(cp))))
		return Deformer.Warp(DeformerId(id), id, parent?.let(::DeformerId), null, rows, cols, bilinear, grid)
	}

	private fun rotation(id: String, baseAngle: Float, originX: Float, originY: Float, angle: Float, scale: Float): Deformer.Rotation {
		val grid = KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), RotationForm(originX, originY, angle, scale, false, false))))
		return Deformer.Rotation(DeformerId(id), id, null, null, baseAngle, grid)
	}

	private fun zeroDeltaMesh(vertexCount: Int): KeyformGrid<MeshForm> =
		KeyformGrid(singleKeyAxis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(vertexCount * 2)))))

	@Test
	fun meshUnderTranslatedWarp() {
		// 1×1 lattice placed as a 2×4 rect at (10,20).
		val warp = warp("W", null, 1, 1, true, floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f))
		val parent = buildDeformerWorlds(listOf(warp), values(paramA to 0f))[DeformerId("W")]!!
		val world = deformMeshThroughParent(zeroDeltaMesh(1), floatArrayOf(0.5f, 0.5f), parent, values(paramA to 0f))!!
		assertEquals(11f, world[0], tol)
		assertEquals(-22f, world[1], tol) // center (11,22), Y-flipped
	}

	@Test
	fun meshUnderRootRotation() {
		val rotation = rotation("R", 0f, 0f, 0f, 90f, 1f)
		val parent = buildDeformerWorlds(listOf(rotation), values(paramA to 0f))[DeformerId("R")]!!
		val world = deformMeshThroughParent(zeroDeltaMesh(1), floatArrayOf(1f, 0f), parent, values(paramA to 0f))!!
		assertEquals(0f, world[0], tol) // (1,0) rotated +90 -> (0,1)
		assertEquals(-1f, world[1], tol) // Y-flipped
	}

	@Test
	fun nestedWarpComposesParentTransform() {
		// Parent translates +100 in x; child is identity -> composed lattice is the parent's.
		val parentWarp = warp("P", null, 1, 1, true, floatArrayOf(100f, 0f, 101f, 0f, 100f, 1f, 101f, 1f))
		val childWarp = warp("C", "P", 1, 1, true, floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f))
		val worlds = buildDeformerWorlds(listOf(parentWarp, childWarp), values(paramA to 0f))
		val child = worlds[DeformerId("C")]!!
		val world = deformMeshThroughParent(zeroDeltaMesh(1), floatArrayOf(0.5f, 0.5f), child, values(paramA to 0f))!!
		assertEquals(100.5f, world[0], tol) // center of the +100-translated lattice
		assertEquals(-0.5f, world[1], tol)
	}
}
