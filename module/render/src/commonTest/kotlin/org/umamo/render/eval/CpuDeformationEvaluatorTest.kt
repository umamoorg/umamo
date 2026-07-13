package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Assembly tests for [CpuDeformationEvaluator] on tiny synthetic models: that it routes direct vs
 * deformer-parented meshes correctly and resolves unspecified parameters to their defaults. The
 * per-stage math is covered by the keyform/transform/cascade tests; this checks the wiring.
 */
class CpuDeformationEvaluatorTest {
	private val tol = 1e-4f
	private val paramA = ParameterId("A")

	private fun axis() = listOf(KeyformAxis(paramA, floatArrayOf(0f)))

	private fun zeroMeshGrid(coordCount: Int) = KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), MeshForm(FloatArray(coordCount)))))

	private fun model(deformers: List<Deformer>, drawable: Drawable) =
		PuppetModel(
			parameters = listOf(Parameter(paramA, "A", -1f, 1f, 0f)),
			parts = emptyList(),
			deformers = deformers,
			drawables = listOf(drawable),
			rootChildren = listOf(OrgChild.Drawable(drawable.id)),
			rootPartId = null,
		)

	@Test
	fun routesDirectMeshWithYFlip() {
		val mesh = DrawableMesh(floatArrayOf(10f, 5f, 20f, 7f), FloatArray(0), IntArray(0))
		val drawable = Drawable(DrawableId("M"), "M", null, BlendMode.Normal, emptyList(), mesh, zeroMeshGrid(4))
		val geo = CpuDeformationEvaluator().evaluate(model(emptyList(), drawable), emptyMap())
		assertEquals(listOf(10f, -5f, 20f, -7f), geo.worldPositions[DrawableId("M")]!!.toList())
	}

	@Test
	fun routesMeshThroughItsWarpParent() {
		val warpCp = floatArrayOf(10f, 20f, 12f, 20f, 10f, 24f, 12f, 24f) // 2×4 rect at (10,20)
		val warp = Deformer.Warp(DeformerId("W"), "W", null, null, 1, 1, true, KeyformGrid(axis(), listOf(KeyformCell(intArrayOf(0), WarpForm(warpCp)))))
		val mesh = DrawableMesh(floatArrayOf(0.5f, 0.5f), FloatArray(0), IntArray(0))
		val drawable = Drawable(DrawableId("M"), "M", DeformerId("W"), BlendMode.Normal, emptyList(), mesh, zeroMeshGrid(2))
		val geo = CpuDeformationEvaluator().evaluate(model(listOf(warp), drawable), emptyMap())
		val world = geo.worldPositions[DrawableId("M")]!!
		assertEquals(11f, world[0], tol)
		assertEquals(-22f, world[1], tol)
	}
}
