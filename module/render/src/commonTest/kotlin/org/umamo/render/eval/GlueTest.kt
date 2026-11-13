package org.umamo.render.eval

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Glue
import org.umamo.runtime.model.GluePair
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Verifies the glue weld ([applyGluesResolved]): each pair slides `A' = A + (B−A)·wA`, `B' = B + (A−B)·wB`
 * from the pre-blend positions. When `wA + wB = 1` (the editor's case) the two converge to one point.
 */
class GlueTest {
	/** A direct (deformer-less) one-vertex drawable resting at `(x, y)` - evaluates to world `(x, −y)`. */
	private fun pointDrawable(raw: String, x: Float, y: Float): Drawable {
		val grid = KeyformGrid(emptyList<KeyformAxis>(), listOf(KeyformCell(intArrayOf(), MeshDeltaForm(floatArrayOf(0f, 0f)))))

		return Drawable(
			id = DrawableId(raw),
			name = raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(x, y), floatArrayOf(0f, 0f), IntArray(0)),
			geometryGrid = grid,
		)
	}

	private fun modelWith(drawableA: Drawable, drawableB: Drawable, glue: Glue) =
		PuppetModel(
			parameters = emptyList(),
			parts = emptyList(),
			deformers = emptyList(),
			drawables = listOf(drawableA, drawableB),
			rootChildren = listOf(OrgChild.Drawable(drawableA.id), OrgChild.Drawable(drawableB.id)),
			rootPartId = null,
			glues = listOf(glue),
		)

	@Test
	fun equalWeightsWeldToMidpoint() {
		val model =
			modelWith(
				pointDrawable("A", 10f, 0f),
				pointDrawable("B", 20f, 0f),
				Glue(DrawableId("A"), DrawableId("B"), listOf(GluePair(0, 0, 0.5f, 0.5f))),
			)
		val geometry = CpuDeformationEvaluator().evaluate(model, emptyMap())
		assertEquals(15f, geometry.worldPositions.getValue(DrawableId("A"))[0], 1e-4f)
		assertEquals(15f, geometry.worldPositions.getValue(DrawableId("B"))[0], 1e-4f)
	}

	@Test
	fun complementaryWeightsConvergeToOnePoint() {
		// wA + wB = 1 (the editor's seam case): A' and B' land on the exact same point.
		val model =
			modelWith(
				pointDrawable("A", 10f, 4f),
				pointDrawable("B", 22f, -8f),
				Glue(DrawableId("A"), DrawableId("B"), listOf(GluePair(0, 0, 0.25f, 0.75f))),
			)
		val geometry = CpuDeformationEvaluator().evaluate(model, emptyMap())
		val positionsA = geometry.worldPositions.getValue(DrawableId("A"))
		val positionsB = geometry.worldPositions.getValue(DrawableId("B"))
		assertEquals(positionsA[0], positionsB[0], 1e-4f)
		assertEquals(positionsA[1], positionsB[1], 1e-4f)
		assertEquals(13f, positionsA[0], 1e-4f) // 10 + (22−10)·0.25
	}

	@Test
	fun fullWeightSnapsOneSideOnly() {
		// wA = 1 → A snaps onto B; wB = 0 → B stays put.
		val model =
			modelWith(
				pointDrawable("A", 10f, 0f),
				pointDrawable("B", 20f, 0f),
				Glue(DrawableId("A"), DrawableId("B"), listOf(GluePair(0, 0, 1f, 0f))),
			)
		val geometry = CpuDeformationEvaluator().evaluate(model, emptyMap())
		assertEquals(20f, geometry.worldPositions.getValue(DrawableId("A"))[0], 1e-4f)
		assertEquals(20f, geometry.worldPositions.getValue(DrawableId("B"))[0], 1e-4f)
	}
}