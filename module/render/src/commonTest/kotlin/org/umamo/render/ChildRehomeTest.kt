package org.umamo.render

import org.umamo.render.eval.DrawableSpaceResolver
import org.umamo.render.eval.PoseParameters
import org.umamo.render.eval.RotationWorld
import org.umamo.render.eval.buildDeformerWorlds
import org.umamo.render.eval.warpLatticeAt
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
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins re-expressing children across a change of parent: a keyed and an unkeyed drawable, a warp, and a rotation
 * each keep their rest and their keyed motion on screen moving from the root into a warp, from the warp into a turned
 * and scaled rotation, and back to the root - exactly between affine spaces, a rotation child keeping its world
 * pivot, angle, and size - and within the landing tolerance into a curved warp, while a child the new parent cannot
 * reach is reported and left as it was.
 */
class ChildRehomeTest {
	private val cageId = DeformerId("cage")
	private val bentId = DeformerId("bent")
	private val turnerId = DeformerId("turner")
	private val collapsedId = DeformerId("collapsed")
	private val innerId = DeformerId("inner")
	private val pivotId = DeformerId("pivot")
	private val plainId = DrawableId("plain")
	private val keyedId = DrawableId("keyed")
	private val sway = ParameterId("ParamSway")
	private val swayed = mapOf(sway to 1f)
	private val children = RehomedChildren(listOf(plainId, keyedId), listOf(innerId, pivotId))

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
	 * A 2 x 2 lattice over [originX]..[originX] + 100 and 0..100 in canvas space, its centre pushed by [bend].
	 *
	 * @param Float originX The left edge.
	 * @param Float bend How far the centre point is pushed.
	 * @return FloatArray The nine control points.
	 */
	private fun lattice(originX: Float, bend: Float = 0f): FloatArray =
		FloatArray(18) { componentIndex ->
			val pointIndex = componentIndex / 2
			val base = if (componentIndex % 2 == 0) originX + (pointIndex % 3) * 50f else (pointIndex / 3) * 50f
			if (pointIndex == 4) base + bend else base
		}

	/**
	 * The rig at the root: four parents to move into (a square cage, a bent cage, a rotation turned 20 degrees at
	 * scale 2, and a collapsed cage), and the children - an unkeyed and a keyed drawable, a warp, and a rotation that
	 * turns as it sways - all at the root, in canvas space over the square cage.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val points = floatArrayOf(20f, 30f, 50f, 50f, 80f, 60f)
		val sweep = floatArrayOf(10f, 0f, 10f, 5f, 10f, 0f)
		val cage = Deformer.Warp(cageId, "cage", null, null, 2, 2, true, swayGrid(WarpLatticeForm(lattice(0f)), WarpLatticeForm(lattice(0f))))
		val bent = Deformer.Warp(bentId, "bent", null, null, 2, 2, true, swayGrid(WarpLatticeForm(lattice(-10f, bend = 14f)), WarpLatticeForm(lattice(-10f, bend = 14f))))
		val turner = Deformer.Rotation(turnerId, "turner", null, null, 20f, swayGrid(RotationPivotForm(150f, 40f, 0f, 2f), RotationPivotForm(150f, 40f, 0f, 2f)))
		val collapsed = Deformer.Warp(collapsedId, "collapsed", null, null, 2, 2, true, swayGrid(WarpLatticeForm(FloatArray(18) { 5f }), WarpLatticeForm(FloatArray(18) { 5f })))
		val innerLattice = floatArrayOf(40f, 40f, 60f, 40f, 40f, 60f, 60f, 60f)
		val innerSwayed = FloatArray(8) { componentIndex -> innerLattice[componentIndex] + if (componentIndex % 2 == 0) 5f else 0f }
		val inner = Deformer.Warp(innerId, "inner", null, null, 1, 1, true, swayGrid(WarpLatticeForm(innerLattice), WarpLatticeForm(innerSwayed)))
		val pivot = Deformer.Rotation(pivotId, "pivot", null, null, 10f, swayGrid(RotationPivotForm(50f, 25f, 0f, 1f), RotationPivotForm(60f, 25f, 15f, 1.5f)))

		return PuppetModel(
			parameters = listOf(Parameter(sway, "Sway", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(cage, bent, turner, collapsed, inner, pivot),
			drawables = listOf(drawable(plainId, points, null), drawable(keyedId, points, swayGrid(MeshDeltaForm(FloatArray(6)), MeshDeltaForm(sweep)))),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * A root drawable at the given canvas points.
	 *
	 * @param DrawableId id The drawable.
	 * @param FloatArray points Its base on the canvas.
	 * @param KeyformGrid? grid Its keyforms, or null when unkeyed.
	 * @return Drawable The drawable.
	 */
	private fun drawable(id: DrawableId, points: FloatArray, grid: KeyformGrid<MeshDeltaForm>?): Drawable =
		Drawable(
			id = id,
			name = id.raw,
			parentDeformerId = null,
			blendMode = BlendMode.Normal,
			maskedBy = emptyList(),
			mesh = DrawableMesh(points.copyOf(), points.copyOf(), FloatArray(points.size), intArrayOf(0, 1, 2)),
			geometryGrid = grid,
		)

	/**
	 * [model] with every child moved under [parentId], its numbers untouched.
	 *
	 * @param PuppetModel model The rig.
	 * @param DeformerId? parentId The new parent, or null for the root.
	 * @return PuppetModel The rig with the children re-parented.
	 */
	private fun reparented(model: PuppetModel, parentId: DeformerId?): PuppetModel =
		model.copy(
			drawables = model.drawables.map { drawable -> drawable.copy(parentDeformerId = parentId) },
			deformers =
				model.deformers.map { deformer ->
					when {
						deformer.id != innerId && deformer.id != pivotId -> deformer
						deformer is Deformer.Warp -> deformer.copy(parent = parentId)
						deformer is Deformer.Rotation -> deformer.copy(parent = parentId)
						else -> deformer
					}
				},
		)

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
	 * A point taken through the pivot rotation's world at [pose]: (10, 0) in its frame, which carries its pivot,
	 * angle, and scale.
	 *
	 * @param PuppetModel model The rig.
	 * @param Map pose The pose.
	 * @return FloatArray The point in the cascade's world.
	 */
	private fun throughPivot(model: PuppetModel, pose: Map<ParameterId, Float>): FloatArray {
		val parameters = PoseParameters(model, pose)
		val world = buildDeformerWorlds(model.deformers, parameters.paramValue, parameters.defaultValue)[pivotId] as RotationWorld
		val out = FloatArray(2)
		world.apply(10f, 0f, out, 0)

		return out
	}

	/**
	 * Asserts every child of [after] sits where it sits in [before] at rest and swayed, within [tolerance].
	 *
	 * @param PuppetModel before The rig before the move.
	 * @param PuppetModel after The rig after it.
	 * @param Float tolerance The allowed difference.
	 * @param String move What moved, for the failure message.
	 */
	private fun assertEveryChildHolds(before: PuppetModel, after: PuppetModel, tolerance: Float, move: String) {
		for (pose in listOf(emptyMap(), swayed)) {
			assertClose(worldOf(before, plainId, pose), worldOf(after, plainId, pose), tolerance, "$move: plain at $pose")
			assertClose(worldOf(before, keyedId, pose), worldOf(after, keyedId, pose), tolerance, "$move: keyed at $pose")
			assertClose(assertNotNull(warpLatticeAt(before, pose, innerId)).world, assertNotNull(warpLatticeAt(after, pose, innerId)).world, tolerance, "$move: inner at $pose")
			assertClose(throughPivot(before, pose), throughPivot(after, pose), tolerance, "$move: pivot at $pose")
		}
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

	/**
	 * Moves every child under [parentId] with conversion, asserting nothing was left behind.
	 *
	 * @param PuppetModel model The rig.
	 * @param DeformerId? parentId The new parent, or null for the root.
	 * @return PuppetModel The rig with the children re-parented and kept in place.
	 */
	private fun rehomed(model: PuppetModel, parentId: DeformerId?): PuppetModel {
		val rehome = childrenRehomed(model, reparented(model, parentId), children)
		assertEquals(0, rehome.unconvertedCount, "every child converts under $parentId")

		return rehome.model
	}

	@Test
	fun theRootToAWarpToARotationAndBackHoldsEveryChild() {
		val atRoot = model()
		val inCage = rehomed(atRoot, cageId)
		assertEveryChildHolds(atRoot, inCage, 1e-3f, "root to warp")

		val inTurner = rehomed(inCage, turnerId)
		assertEveryChildHolds(inCage, inTurner, 1e-3f, "warp to turned, scaled rotation")
		val pivot = inTurner.deformerById[pivotId] as Deformer.Rotation
		assertEquals(0.5f, assertNotNull(pivot.geometryGrid).cells[0].form.scale, 1e-4f, "the child's own scale halves under a parent of scale 2")

		val backAtRoot = rehomed(inTurner, null)
		assertEveryChildHolds(inTurner, backAtRoot, 1e-3f, "rotation to root")
		assertEveryChildHolds(atRoot, backAtRoot, 1e-2f, "the round trip")
	}

	@Test
	fun aCurvedWarpKeepsEveryChildAtRest() {
		val inCage = rehomed(model(), cageId)
		val inBent = childrenRehomed(inCage, reparented(inCage, bentId), children)

		assertEquals(0, inBent.unconvertedCount)
		assertClose(worldOf(inCage, plainId, emptyMap()), worldOf(inBent.model, plainId, emptyMap()), LANDING_TOLERANCE_PX, "plain")
		assertClose(worldOf(inCage, keyedId, emptyMap()), worldOf(inBent.model, keyedId, emptyMap()), LANDING_TOLERANCE_PX, "keyed")
		assertClose(assertNotNull(warpLatticeAt(inCage, emptyMap(), innerId)).world, assertNotNull(warpLatticeAt(inBent.model, emptyMap(), innerId)).world, LANDING_TOLERANCE_PX, "inner")
		assertClose(throughPivot(inCage, emptyMap()), throughPivot(inBent.model, emptyMap()), LANDING_TOLERANCE_PX, "pivot")
	}

	@Test
	fun aParentThatCannotReachAChildReportsIt() {
		val atRoot = model()
		val moved = reparented(atRoot, collapsedId)
		val rehome = childrenRehomed(atRoot, moved, children)

		assertEquals(setOf(plainId, keyedId), rehome.unconvertedDrawables.toSet())
		assertEquals(setOf(innerId, pivotId), rehome.unconvertedDeformers.toSet())
		assertEquals(moved, rehome.model, "nothing is rewritten")
	}
}