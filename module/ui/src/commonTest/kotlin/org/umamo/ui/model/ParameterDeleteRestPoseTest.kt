package org.umamo.ui.model

import org.umamo.edit.parameter.ownersWhoseRestChangesOnDeleting
import org.umamo.edit.parameter.withParameterDeleted
import org.umamo.render.eval.CpuDeformationEvaluator
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.withDerivedRenderRoot
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Pins a parameter delete's blend scrub against the CPU evaluator itself, not against the scrub's own idea of
 * exactness: where [ownersWhoseRestChangesOnDeleting] reports nothing, the rig evaluates at its default pose to
 * the same vertices after the delete as before it, and where it reports an owner, the vertices really move.
 * `:ui` hosts the test because it is the module that sees both `:edit` and `:render`.
 */
class ParameterDeleteRestPoseTest {
	private val deletedId = ParameterId("ParamDeleted")
	private val drivingId = ParameterId("ParamDriving")
	private val drawableId = DrawableId("drawable")
	private val evaluator = CpuDeformationEvaluator()

	/** A three-vertex drawable at the root carrying [binding]. */
	private fun model(parameters: List<Parameter>, binding: BlendShapeBinding<MeshForm>): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = emptyList(),
			deformers = emptyList(),
			drawables =
				listOf(
					Drawable(
						id = drawableId,
						name = "drawable",
						parentDeformerId = null,
						blendMode = BlendMode.Normal,
						maskedBy = emptyList(),
						mesh = DrawableMesh.withLocalEqualToCanvas(floatArrayOf(0f, 0f, 10f, 0f, 0f, 10f), floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f), intArrayOf(0, 1, 2)),
						geometryGrid = null,
						blendShapes = listOf(binding),
					),
				),
			rootChildren = listOf(OrgChild.Drawable(drawableId)),
			rootPartId = null,
		).withDerivedRenderRoot()

	/** A binding driven by [parameterId], neutral at 0, moving every vertex at 1. */
	private fun binding(parameterId: ParameterId, limits: List<BlendWeightLimit> = emptyList()): BlendShapeBinding<MeshForm> =
		BlendShapeBinding(
			parameterId = parameterId,
			keys = floatArrayOf(0f, 1f),
			neutralIndex = 0,
			forms = listOf(null, MeshForm(floatArrayOf(3f, 1f, 3f, 1f, 3f, 1f))),
			limits = limits,
		)

	/**
	 * The drawable's world positions with every parameter at its default.
	 *
	 * @param PuppetModel model The model.
	 * @return FloatArray The positions.
	 */
	private fun restOf(model: PuppetModel): FloatArray =
		evaluator.evaluate(model, model.parameters.associate { parameter -> parameter.id to parameter.default }).worldPositions.getValue(drawableId)

	/** A sole limit capping the binding at its constraint's default is baked into the forms, and the rest pose evaluates the same. */
	@Test
	fun aBakedLimitKeepsTheRestPose() {
		val limit = BlendWeightLimit(deletedId, listOf(BlendWeightLimitPoint(0f, 0.5f), BlendWeightLimitPoint(1f, 1f)))
		val before = model(listOf(Parameter(deletedId, "deleted", -1f, 1f, 0f), Parameter(drivingId, "driving", -1f, 1f, 1f)), binding(drivingId, listOf(limit)))
		val after = before.withParameterDeleted(deletedId)
		assertTrue(before.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty(), "the scrub calls itself exact")
		assertContentEquals(restOf(before), restOf(after), "and the evaluator agrees")
	}

	/** A binding its parameter held active at the default is dropped with it, and the rest pose moves as reported. */
	@Test
	fun aDroppedActiveBindingMovesTheRestPose() {
		val before = model(listOf(Parameter(deletedId, "deleted", -1f, 1f, 0.5f)), binding(deletedId))
		val after = before.withParameterDeleted(deletedId)
		assertFalse(before.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty(), "the scrub reports the drawable")
		assertFalse(restOf(before).contentEquals(restOf(after)), "and the evaluator sees it move")
	}
}