package org.umamo.edit

import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.BlendWeightLimit
import org.umamo.runtime.model.BlendWeightLimitPoint
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.KeyformOwner
import org.umamo.runtime.model.MeshForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.Part
import org.umamo.runtime.model.PartForm
import org.umamo.runtime.model.PartId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.toDoubleArray
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins what a parameter delete does to the blend shapes that name it: a binding the parameter drives goes, a
 * weight limit over it goes or is baked into its binding's forms, no id the model lacks survives, and the
 * owners whose rest pose that moves are the ones reported.
 */
class ParameterDeletionScrubTest {
	private val deletedId = ParameterId("ParamDeleted")
	private val drivingId = ParameterId("ParamDriving")
	private val otherLimitId = ParameterId("ParamOtherLimit")
	private val drawableId = DrawableId("drawable")

	/** A parameter over -1..1 with the given [default]. */
	private fun parameter(id: ParameterId, default: Float = 0f): Parameter = Parameter(id, id.raw, min = -1f, max = 1f, default = default)

	/** A limit over [parameterId] that holds [weightAtZero] at 0 and 1 at 1. */
	private fun limitOver(parameterId: ParameterId, weightAtZero: Float): BlendWeightLimit =
		BlendWeightLimit(parameterId, listOf(BlendWeightLimitPoint(0f, weightAtZero), BlendWeightLimitPoint(1f, 1f)))

	/** A mesh binding driven by [parameterId], neutral at 0 and one form at 1. */
	private fun meshBinding(parameterId: ParameterId, limits: List<BlendWeightLimit> = emptyList()): BlendShapeBinding<MeshForm> =
		BlendShapeBinding(
			parameterId = parameterId,
			keys = floatArrayOf(0f, 1f),
			neutralIndex = 0,
			forms = listOf(null, MeshForm(floatArrayOf(2f, 4f).toDoubleArray(), drawOrder = 510f, opacity = 0.5f)),
			limits = limits,
		)

	/** An ungridded drawable (its blend reference is the rest) carrying [bindings]. */
	private fun drawable(bindings: List<BlendShapeBinding<MeshForm>>): Drawable =
		Drawable(
			id = drawableId,
			name = "drawable",
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = null,
			geometryGrid = null,
			blendShapes = bindings,
		)

	/** A model of [parameters] and [drawables]. */
	private fun model(parameters: List<Parameter>, drawables: List<Drawable> = emptyList()): PuppetModel =
		PuppetModel(
			parameters = parameters,
			parts = emptyList(),
			deformers = emptyList(),
			drawables = drawables,
			rootChildren = emptyList(),
			rootPartId = null,
		)

	/** A binding the deleted parameter drives goes; with the parameter's default on the neutral key the rest pose is kept. */
	@Test
	fun aBindingTheParameterDrivesIsDropped() {
		val start = model(listOf(parameter(deletedId)), listOf(drawable(listOf(meshBinding(deletedId)))))
		val after = start.withParameterDeleted(deletedId)
		assertTrue(after.drawables.single().blendShapes.isEmpty(), "the binding goes with its parameter")
		assertTrue(start.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty(), "a default on the neutral key keeps the rest pose")
	}

	/** A binding that contributed at the deleted parameter's default takes that contribution with it, and is reported. */
	@Test
	fun aBindingActiveAtTheDefaultIsReported() {
		val start = model(listOf(parameter(deletedId, default = 0.5f)), listOf(drawable(listOf(meshBinding(deletedId)))))
		val session = EditorSession(start)
		session.deleteParameter(deletedId)
		assertTrue(session.model.value.drawables.single().blendShapes.isEmpty(), "the binding goes with its parameter")
		assertEquals(listOf<KeyformOwner>(KeyformOwner.Drawable(drawableId)), start.ownersWhoseRestChangesOnDeleting(deletedId))
		val notice = session.notice.value
		assertEquals("notice.parameter.deleteChangedRest", notice?.messageKey, "the delete says the rest pose moved")
		assertEquals(listOf("1"), notice?.arguments, "one object moved")
	}

	/** A limit that caps nothing at the deleted parameter's default goes, and the binding keeps its forms. */
	@Test
	fun aLimitThatCapsNothingIsDropped() {
		val binding = meshBinding(drivingId, listOf(limitOver(deletedId, weightAtZero = 1f)))
		val start = model(listOf(parameter(deletedId), parameter(drivingId)), listOf(drawable(listOf(binding))))
		val kept = start.withParameterDeleted(deletedId).drawables.single().blendShapes.single()
		assertTrue(kept.limits.isEmpty(), "the limit goes")
		assertSame(binding.forms, kept.forms, "the forms stay as they were")
		assertTrue(start.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty())
	}

	/** A binding's only limit, capping it below one at the default, is baked into its forms around the reference. */
	@Test
	fun aSoleLimitBelowOneIsBakedIntoTheForms() {
		val binding = meshBinding(drivingId, listOf(limitOver(deletedId, weightAtZero = 0.5f)))
		val start = model(listOf(parameter(deletedId), parameter(drivingId)), listOf(drawable(listOf(binding))))
		val kept = start.withParameterDeleted(deletedId).drawables.single().blendShapes.single()
		assertTrue(kept.limits.isEmpty(), "the limit is gone")
		assertNull(kept.forms[0], "the neutral stays a hole")
		val form = kept.forms[1]!!
		// The drawable is ungridded, so its reference is the rest: zero deltas, draw order 500, opacity 1.
		assertContentEquals(doubleArrayOf(1.0, 2.0), form.positionDeltas, "the deltas are halved")
		assertEquals(505f, form.drawOrder, "the draw order moves half as far from 500")
		assertEquals(0.75f, form.opacity, "the opacity moves half as far from 1")
		assertTrue(start.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty(), "baking the cap is exact")
	}

	/** A limit that holds its binding at zero at the default silences it there, so the binding goes, exactly. */
	@Test
	fun aLimitHoldingZeroDropsTheBinding() {
		val binding = meshBinding(drivingId, listOf(limitOver(deletedId, weightAtZero = 0f)))
		val start = model(listOf(parameter(deletedId), parameter(drivingId, default = 1f)), listOf(drawable(listOf(binding))))
		assertTrue(start.withParameterDeleted(deletedId).drawables.single().blendShapes.isEmpty(), "the silenced binding goes")
		assertTrue(start.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty(), "it contributed nothing at the default")
	}

	/** Beside a limit that was already the tighter cap at the default, the deleted parameter's limit goes with no change at rest. */
	@Test
	fun aLimitBesideATighterOneIsDroppedExactly() {
		val binding = meshBinding(drivingId, listOf(limitOver(deletedId, weightAtZero = 0.5f), limitOver(otherLimitId, weightAtZero = 0.25f)))
		val start = model(listOf(parameter(deletedId), parameter(drivingId, default = 1f), parameter(otherLimitId)), listOf(drawable(listOf(binding))))
		val kept = start.withParameterDeleted(deletedId).drawables.single().blendShapes.single()
		assertEquals(listOf(otherLimitId), kept.limits.map { limit -> limit.parameterId }, "only the other limit stays")
		assertTrue(start.ownersWhoseRestChangesOnDeleting(deletedId).isEmpty(), "the other limit was the tighter one")
	}

	/** Beside a looser limit, the deleted parameter's cap has no constant to stand in for it, so the change at rest is reported. */
	@Test
	fun aLimitBesideALooserOneIsReported() {
		val binding = meshBinding(drivingId, listOf(limitOver(deletedId, weightAtZero = 0.5f), limitOver(otherLimitId, weightAtZero = 1f)))
		val start = model(listOf(parameter(deletedId), parameter(drivingId, default = 1f), parameter(otherLimitId)), listOf(drawable(listOf(binding))))
		val kept = start.withParameterDeleted(deletedId).drawables.single().blendShapes.single()
		assertEquals(listOf(otherLimitId), kept.limits.map { limit -> limit.parameterId }, "the deleted parameter's limit goes")
		assertEquals(listOf<KeyformOwner>(KeyformOwner.Drawable(drawableId)), start.ownersWhoseRestChangesOnDeleting(deletedId))
	}

	/** A warp's forms are baked around its lattice at the default pose, not around zero. */
	@Test
	fun aWarpFormIsBakedAroundItsDefaultLattice() {
		val lattice = FloatArray(8) { component -> 10f * component }
		val warp =
			Deformer.Warp(
				id = DeformerId("warp"),
				name = "warp",
				parent = null,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = false,
				geometryGrid = KeyformGrid(listOf(KeyformAxis(drivingId, floatArrayOf(0f))), listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(lattice)))),
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = drivingId,
							keys = floatArrayOf(0f, 1f),
							neutralIndex = 0,
							forms = listOf(null, WarpForm(FloatArray(8) { component -> 10f * component + 4f }, opacity = 0.5f)),
							limits = listOf(limitOver(deletedId, weightAtZero = 0.25f)),
						),
					),
			)
		val start = model(listOf(parameter(deletedId), parameter(drivingId))).copy(deformers = listOf(warp))
		val form = (start.withParameterDeleted(deletedId).deformers.single() as Deformer.Warp).blendShapes.single().forms[1]!!
		assertContentEquals(FloatArray(8) { component -> 10f * component + 1f }, form.controlPoints, "each point keeps a quarter of its offset from the lattice")
		assertEquals(0.875f, form.opacity, "the opacity keeps a quarter of its offset from 1")
	}

	/** A part's forms are baked around its draw order at the default pose. */
	@Test
	fun aPartFormIsBakedAroundItsDrawOrder() {
		val part =
			Part(
				id = PartId("part"),
				name = "part",
				children = emptyList(),
				drawOrder = 400,
				blendShapes =
					listOf(
						BlendShapeBinding(
							parameterId = drivingId,
							keys = floatArrayOf(0f, 1f),
							neutralIndex = 0,
							forms = listOf(null, PartForm(drawOrder = 480f)),
							limits = listOf(limitOver(deletedId, weightAtZero = 0.5f)),
						),
					),
			)
		val start = model(listOf(parameter(deletedId), parameter(drivingId))).copy(parts = listOf(part))
		val form = start.withParameterDeleted(deletedId).parts.single().blendShapes.single().forms[1]!!
		assertEquals(440f, form.drawOrder, "the draw order keeps half its offset from 400")
	}
}