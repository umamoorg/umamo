package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Verifies [eligibleTransformDrawables] - the gate that decides what an Object-mode G / S / R may run
 * over: the mesh-carrying drawables of the selection, with parts, deformers, and mesh-less drawables
 * silently skipped; null only when the selection holds nothing transformable at all (so the caller
 * blocks the gesture with a note).  Also verifies [isPoseNeutral], the guard that refuses an
 * Object-mode transform while any parameter is scrubbed away from its default.
 */
class ObjectTransformsTest {
	private fun mesh(): DrawableMesh = DrawableMesh(floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2))

	private fun drawable(id: String, withMesh: Boolean): Drawable =
		Drawable(
			DrawableId(id),
			id.uppercase(),
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = if (withMesh) mesh() else null,
			keyforms = null,
		)

	// A model with two mesh-carrying drawables, one mesh-less drawable, a part, and a deformer.
	private fun model(): PuppetModel =
		PuppetModel(
			parameters = emptyList(),
			parts = listOf(Part(PartId("p"), "P", children = emptyList())),
			deformers = listOf(Deformer.Warp(DeformerId("w"), "W", parent = null, partId = null, rows = 2, columns = 2, isQuadTransform = true, keyforms = null)),
			drawables = listOf(drawable("a", withMesh = true), drawable("b", withMesh = true), drawable("empty", withMesh = false)),
			rootChildren = emptyList(),
			rootPartId = null,
		)

	private val drawableA = SelectionTarget.Drawable(DrawableId("a"))
	private val drawableB = SelectionTarget.Drawable(DrawableId("b"))

	/** All targets are mesh-carrying drawables: the ids are returned (order-independent). */
	@Test
	fun allMeshDrawablesAreEligible() {
		val ids = eligibleTransformDrawables(Selection(setOf(drawableA, drawableB), drawableB), model())
		assertEquals(setOf(DrawableId("a"), DrawableId("b")), ids?.toSet(), "both drawable ids are eligible")
	}

	/** An empty selection has nothing to transform. */
	@Test
	fun emptySelectionIsIneligible() {
		assertNull(eligibleTransformDrawables(Selection(), model()))
	}

	/** A part in the selection is silently skipped; the drawable beside it still transforms. */
	@Test
	fun partInSelectionIsSkipped() {
		val selection = Selection(setOf(drawableA, SelectionTarget.Part(PartId("p"))), drawableA)
		assertEquals(listOf(DrawableId("a")), eligibleTransformDrawables(selection, model()))
	}

	/** A deformer in the selection is silently skipped; the drawable beside it still transforms. */
	@Test
	fun deformerInSelectionIsSkipped() {
		val selection = Selection(setOf(drawableA, SelectionTarget.Deformer(DeformerId("w"))), drawableA)
		assertEquals(listOf(DrawableId("a")), eligibleTransformDrawables(selection, model()))
	}

	/** A mesh-less drawable in the selection is silently skipped: it has no positions to move. */
	@Test
	fun meshlessDrawableInSelectionIsSkipped() {
		val selection = Selection(setOf(drawableA, SelectionTarget.Drawable(DrawableId("empty"))), drawableA)
		assertEquals(listOf(DrawableId("a")), eligibleTransformDrawables(selection, model()))
	}

	/** A selection holding nothing transformable at all (only a part and a deformer) blocks. */
	@Test
	fun onlyIneligibleTargetsBlock() {
		val selection = Selection(setOf(SelectionTarget.Part(PartId("p")), SelectionTarget.Deformer(DeformerId("w"))), null)
		assertNull(eligibleTransformDrawables(selection, model()))
	}

	private fun parameter(id: String, default: Float): Parameter = Parameter(ParameterId(id), id, min = -1f, max = 1f, default = default)

	private fun modelWithParameters(): PuppetModel = model().copy(parameters = listOf(parameter("angle", 0f), parameter("breath", 0.5f)))

	/** Every parameter at its default (explicitly or by omission) is neutral. */
	@Test
	fun poseAtDefaultsIsNeutral() {
		val model = modelWithParameters()
		assertTrue(isPoseNeutral(model, mapOf(ParameterId("angle") to 0f, ParameterId("breath") to 0.5f)), "explicit defaults are neutral")
		assertTrue(isPoseNeutral(model, emptyMap()), "an omitted parameter counts as neutral")
	}

	/** Any parameter scrubbed away from its default makes the pose non-neutral. */
	@Test
	fun scrubbedPoseIsNotNeutral() {
		val model = modelWithParameters()
		assertFalse(isPoseNeutral(model, mapOf(ParameterId("angle") to 0.25f)))
	}
}
