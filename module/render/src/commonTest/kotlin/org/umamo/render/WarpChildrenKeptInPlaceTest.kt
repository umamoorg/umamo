package org.umamo.render

import org.umamo.render.eval.DrawableSpaceResolver
import org.umamo.render.eval.PoseParameters
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.keyform.withEveryLatticeMapped
import org.umamo.runtime.keyform.withLatticeTransformed
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.MeshDeltaForm
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins keeping a reshaped warp's children in place: every kind of direct child rests where it rested, a keyed
 * child's motion keeps its on-screen size and direction, a child rotation keeps its world pivot and angle, and a
 * child that cannot be re-expressed is reported rather than silently left to move.
 */
class WarpChildrenKeptInPlaceTest {
	private val warpId = DeformerId("cage")
	private val innerId = DeformerId("inner")
	private val pivotId = DeformerId("pivot")
	private val plainId = DrawableId("plain")
	private val keyedId = DrawableId("keyed")
	private val hiddenId = DrawableId("hidden")
	private val sway = ParameterId("ParamSway")
	private val reveal = ParameterId("ParamReveal")
	private val swayed = mapOf(sway to 1f)

	/**
	 * A 2x2 lattice 100 units across, or curved when [bend] is non-zero.
	 *
	 * @param Float bend How far the centre point is pushed.
	 * @return FloatArray The nine control points.
	 */
	private fun cage(bend: Float = 0f): FloatArray =
		FloatArray(18) { componentIndex ->
			val pointIndex = componentIndex / 2
			val base = if (componentIndex % 2 == 0) (pointIndex % 3) * 50f else (pointIndex / 3) * 50f
			if (pointIndex == 4) base + bend else base
		}

	/**
	 * A two-key grid on [sway] whose two cells hold [rest] and [swayedForm].
	 *
	 * @param TForm rest The form at sway 0.
	 * @param TForm swayedForm The form at sway 1.
	 * @return KeyformGrid The grid.
	 */
	private fun <TForm> swayGrid(rest: TForm, swayedForm: TForm): KeyformGrid<TForm> =
		KeyformGrid(listOf(KeyformAxis(sway, floatArrayOf(0f, 1f))), listOf(KeyformCell(intArrayOf(0), rest), KeyformCell(intArrayOf(1), swayedForm)))

	/**
	 * A drawable under the cage at the given normalized points.
	 *
	 * @param DrawableId id The drawable.
	 * @param FloatArray points Its base in the cage's coordinates.
	 * @param KeyformGrid? grid Its keyforms, or null when unkeyed.
	 * @return Drawable The drawable.
	 */
	private fun child(id: DrawableId, points: FloatArray, grid: KeyformGrid<MeshDeltaForm>?): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = warpId,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(points.copyOf(), points.copyOf(), FloatArray(points.size), intArrayOf(0, 1, 2)),
			geometryGrid = grid,
		)

	/**
	 * The rig: a cage holding an unkeyed and a keyed drawable, an inner warp, and a pivot rotation, all swaying,
	 * plus a drawable the default pose hides.
	 *
	 * @param Float bend The cage's curvature.
	 * @return PuppetModel The rig.
	 */
	private fun model(bend: Float = 0f): PuppetModel {
		val points = floatArrayOf(0.2f, 0.3f, 0.5f, 0.5f, 0.8f, 0.6f)
		val sweep = floatArrayOf(0.1f, 0f, 0.1f, 0.05f, 0.1f, 0f)
		val cageWarp = Deformer.Warp(warpId, "cage", null, null, 2, 2, true, swayGrid(WarpLatticeForm(cage(bend)), WarpLatticeForm(cage(bend))))
		val innerLattice = floatArrayOf(0.4f, 0.4f, 0.6f, 0.4f, 0.4f, 0.6f, 0.6f, 0.6f)
		val innerSwayed = FloatArray(8) { componentIndex -> innerLattice[componentIndex] + if (componentIndex % 2 == 0) 0.05f else 0f }
		val inner = Deformer.Warp(innerId, "inner", warpId, null, 1, 1, true, swayGrid(WarpLatticeForm(innerLattice), WarpLatticeForm(innerSwayed)))
		val pivot =
			Deformer.Rotation(pivotId, "pivot", warpId, null, 10f, swayGrid(RotationPivotForm(0.5f, 0.25f, 0f, 1f), RotationPivotForm(0.6f, 0.25f, 15f, 1f)))
		val hiddenGrid = KeyformGrid(listOf(KeyformAxis(reveal, floatArrayOf(1f, 2f))), listOf(KeyformCell(intArrayOf(0), MeshDeltaForm(FloatArray(6))), KeyformCell(intArrayOf(1), MeshDeltaForm(FloatArray(6)))))

		return PuppetModel(
			parameters = listOf(Parameter(sway, "Sway", 0f, 1f, 0f), Parameter(reveal, "Reveal", 0f, 2f, 0f)),
			parts = emptyList(),
			deformers = listOf(cageWarp, inner, pivot),
			drawables =
				listOf(
					child(plainId, points, null),
					child(keyedId, points, swayGrid(MeshDeltaForm(FloatArray(6)), MeshDeltaForm(sweep))),
					child(hiddenId, points, hiddenGrid),
				),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * [model] with the cage replaced by [reshape] of it.
	 *
	 * @param PuppetModel model The rig.
	 * @param Function reshape The cage's replacement.
	 * @return PuppetModel The rig with the cage reshaped, its children untouched.
	 */
	private fun reshaped(model: PuppetModel, reshape: (Deformer.Warp) -> Deformer.Warp): PuppetModel =
		model.copy(deformers = model.deformers.map { deformer -> if (deformer.id == warpId) reshape(deformer as Deformer.Warp) else deformer })

	/**
	 * A drawable's world positions at [pose].
	 *
	 * @param PuppetModel model The rig.
	 * @param DrawableId drawableId The drawable.
	 * @param Map pose The pose.
	 * @return FloatArray The world positions.
	 */
	private fun worldOf(model: PuppetModel, drawableId: DrawableId, pose: Map<ParameterId, Float>): FloatArray {
		val spaces = DrawableSpaceResolver(model, pose)

		return assertNotNull(spaces.mapping(drawableId)).localToWorld(assertNotNull(spaces.localPosed(drawableId)))
	}

	/**
	 * A point taken through the pivot rotation's world at [pose].
	 *
	 * @param PuppetModel model The rig.
	 * @param Map pose The pose.
	 * @return FloatArray The point (10, 0) in the rotation's frame, in the cascade's world.
	 */
	private fun throughPivot(model: PuppetModel, pose: Map<ParameterId, Float>): FloatArray {
		val parameters = PoseParameters(model, pose)
		val world = buildDeformerWorlds(model.deformers, parameters.paramValue, parameters.defaultValue)[pivotId] as RotationWorld
		val out = FloatArray(2)
		world.apply(10f, 0f, out, 0)

		return out
	}

	/**
	 * Asserts two arrays agree within [tolerance].
	 *
	 * @param FloatArray expected The expected values.
	 * @param FloatArray actual The actual values.
	 * @param Float tolerance The allowed difference.
	 * @param String message The failure context.
	 */
	private fun assertClose(expected: FloatArray, actual: FloatArray, tolerance: Float, message: String) {
		assertEquals(expected.size, actual.size, message)

		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= tolerance, "$message: component $componentIndex is ${actual[componentIndex]}, not ${expected[componentIndex]}")
		}
	}

	/** Under a cage enlarged 2x, every child rests where it did, and the keyed ones sway the same on screen. */
	@Test
	fun anEnlargedCageMovesNoChildAtRestOrInMotion() {
		val before = model()
		val kept = warpChildrenKeptInPlace(before, reshaped(before) { warp -> warp.withLatticeTransformed(floatArrayOf(2f, 0f, -30f, 0f, 2f, 10f)) }, warpId)
		val after = kept.model

		assertEquals(listOf(hiddenId), kept.unconvertedDrawables, "only the drawable the defaults hide is left")
		assertTrue(kept.unconvertedDeformers.isEmpty())

		for (pose in listOf(emptyMap(), swayed)) {
			assertClose(worldOf(before, plainId, pose), worldOf(after, plainId, pose), 1e-3f, "plain at $pose")
			assertClose(worldOf(before, keyedId, pose), worldOf(after, keyedId, pose), 1e-3f, "keyed at $pose")
			assertClose(assertNotNull(warpLatticeAt(before, pose, innerId)).world, assertNotNull(warpLatticeAt(after, pose, innerId)).world, 1e-3f, "inner at $pose")
			assertClose(throughPivot(before, pose), throughPivot(after, pose), 1e-3f, "pivot at $pose")
		}
	}

	/** Under a turned cage the child rotation's world angle holds: its base angle takes up the cage's turn. */
	@Test
	fun aTurnedCageKeepsAChildRotationsWorldAngle() {
		val before = model()
		val angle = 0.5f
		val turn = floatArrayOf(cos(angle), -sin(angle), 0f, sin(angle), cos(angle), 0f)
		val after = warpChildrenKeptInPlace(before, reshaped(before) { warp -> warp.withLatticeTransformed(turn) }, warpId).model

		for (pose in listOf(emptyMap(), swayed)) {
			assertClose(throughPivot(before, pose), throughPivot(after, pose), 1e-2f, "pivot at $pose")
			assertClose(worldOf(before, keyedId, pose), worldOf(after, keyedId, pose), 1e-2f, "keyed at $pose")
		}
	}

	/** On a curved reshape every child still rests where it did, within the landing tolerance. */
	@Test
	fun aCurvedReshapeKeepsEveryChildAtRest() {
		val before = model()
		val after = warpChildrenKeptInPlace(before, reshaped(before) { warp -> warp.withEveryLatticeMapped { cage(bend = 18f) } }, warpId).model

		assertClose(worldOf(before, plainId, emptyMap()), worldOf(after, plainId, emptyMap()), LANDING_TOLERANCE_PX, "plain")
		assertClose(worldOf(before, keyedId, emptyMap()), worldOf(after, keyedId, emptyMap()), LANDING_TOLERANCE_PX, "keyed")
		assertClose(assertNotNull(warpLatticeAt(before, emptyMap(), innerId)).world, assertNotNull(warpLatticeAt(after, emptyMap(), innerId)).world, LANDING_TOLERANCE_PX, "inner")
		assertClose(throughPivot(before, emptyMap()), throughPivot(after, emptyMap()), LANDING_TOLERANCE_PX, "pivot")
	}

	/** An unkeyed child lands where the rebinding helper would put it: one rule for keeping a drawable at rest. */
	@Test
	fun anUnkeyedChildMatchesTheRebindingHelper() {
		val before = model()
		val unconverted = reshaped(before) { warp -> warp.withEveryLatticeMapped { cage(bend = 12f) } }
		val kept = warpChildrenKeptInPlace(before, unconverted, warpId).model
		val expected = assertNotNull(localPositionsKeepingRest(before, unconverted, listOf(plainId))[plainId])

		assertClose(expected, assertNotNull(kept.drawables.first { drawable -> drawable.id == plainId }.mesh).localPositions, 1e-3f, "base")
	}

	/** A cage collapsed to a point cannot hold anything in place, so every child is reported and left as it was. */
	@Test
	fun aCollapsedCageReportsEveryChild() {
		val before = model()
		val collapsed = reshaped(before) { warp -> warp.withEveryLatticeMapped { points -> FloatArray(points.size) { 5f } } }
		val kept = warpChildrenKeptInPlace(before, collapsed, warpId)

		assertEquals(setOf(plainId, keyedId, hiddenId), kept.unconvertedDrawables.toSet())
		assertEquals(setOf(innerId, pivotId), kept.unconvertedDeformers.toSet())
		assertEquals(collapsed, kept.model, "nothing is rewritten")
	}
}