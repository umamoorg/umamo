package org.umamo.ui.transform

import org.umamo.edit.EditorMode
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * Pins the Pose-mode lowering: a shaped lattice lands each moved point on its world target at the pose - between keys,
 * under a turned and scaled rotation, with a blend shape adding on top - while the unmoved points keep their place in
 * the parent's space, so a nested warp follows a moved parent; and the stage reads Edit mode as rest.
 */
class LatticePoseTest {
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val smile = ParameterId("ParamSmile")
	private val rotationId = DeformerId("rotation")
	private val outerId = DeformerId("outer")
	private val innerId = DeformerId("inner")

	/**
	 * A 1x1 lattice offset and skewed by [seed], so every cell of the grid differs.
	 *
	 * @param Float seed The lattice's variation.
	 * @return FloatArray The four control points.
	 */
	private fun lattice(seed: Float): FloatArray = floatArrayOf(seed, -seed, 10f + seed * 0.5f, seed, seed * 0.25f, 10f - seed, 10f + seed, 10f + seed * 2f)

	/**
	 * An outer warp on a 3x2 grid over angle X and angle Y with a smile blend shape, under a turned and scaled
	 * rotation, and an inner warp nested in it.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val cells =
			(0 until 2).flatMap { yIndex ->
				(0 until 3).map { xIndex -> KeyformCell(intArrayOf(xIndex, yIndex), WarpLatticeForm(lattice(xIndex * 3f + yIndex * 7f))) }
			}
		val outer =
			Deformer.Warp(
				id = outerId,
				name = "outer",
				parent = rotationId,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid = KeyformGrid(listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f))), cells),
				blendShapes = listOf(BlendShapeBinding(smile, floatArrayOf(0f, 1f), neutralIndex = 0, forms = listOf(null, WarpForm(FloatArray(8) { componentIndex -> componentIndex * 0.5f })))),
			)
		val inner =
			Deformer.Warp(
				id = innerId,
				name = "inner",
				parent = outerId,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
						listOf(
							KeyformCell(intArrayOf(0), WarpLatticeForm(floatArrayOf(0.25f, 0.25f, 0.75f, 0.25f, 0.25f, 0.75f, 0.75f, 0.75f))),
							KeyformCell(intArrayOf(1), WarpLatticeForm(floatArrayOf(0.3f, 0.2f, 0.7f, 0.3f, 0.2f, 0.8f, 0.8f, 0.7f))),
						),
					),
			)
		val rotation =
			Deformer.Rotation(
				id = rotationId,
				name = "rotation",
				parent = null,
				partId = null,
				baseAngle = 0f,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
						listOf(KeyformCell(intArrayOf(0), RotationPivotForm(100f, 50f, 30f, 2f)), KeyformCell(intArrayOf(1), RotationPivotForm(100f, 50f, -10f, 1.5f))),
					),
			)

		return PuppetModel(
			parameters = listOf(Parameter(angleX, "X", -1f, 1f, 0f), Parameter(angleY, "Y", 0f, 1f, 0f), Parameter(smile, "Smile", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(rotation, outer, inner),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * Random poses off every key, the smile engaged.
	 *
	 * @return List The poses.
	 */
	private fun offKeyPoses(): List<Map<ParameterId, Float>> {
		val random = Random(5)

		return (0 until 12).map {
			mapOf(angleX to random.nextFloat() * 1.8f - 0.9f, angleY to 0.05f + random.nextFloat() * 0.9f, smile to 0.1f + random.nextFloat() * 0.8f)
		}
	}

	/**
	 * [world] with each point in [moved] shifted by [dx], [dz].
	 *
	 * @param FloatArray world The points.
	 * @param Set<Int> moved The points to shift.
	 * @param Float dx The x shift.
	 * @param Float dz The z shift.
	 * @return FloatArray The shifted points (a new array).
	 */
	private fun shifted(
		world: FloatArray,
		moved: Set<Int>,
		dx: Float,
		dz: Float,
	): FloatArray =
		FloatArray(world.size) { componentIndex ->
			val pointIndex = componentIndex / 2

			when {
				pointIndex !in moved -> world[componentIndex]
				componentIndex % 2 == 0 -> world[componentIndex] + dx
				else -> world[componentIndex] + dz
			}
		}

	/**
	 * Asserts two position arrays agree within [tolerance].
	 *
	 * @param FloatArray expected The expected positions.
	 * @param FloatArray actual The actual positions.
	 * @param Float tolerance The allowed difference.
	 * @param String message What is compared.
	 */
	private fun assertNear(
		expected: FloatArray,
		actual: FloatArray,
		tolerance: Float,
		message: String,
	) {
		assertEquals(expected.size, actual.size, message)

		for (componentIndex in expected.indices) {
			assertEquals(expected[componentIndex], actual[componentIndex], tolerance, "$message at $componentIndex")
		}
	}

	@Test
	fun movedPointsLandOnTheirTargetsAtEveryOffKeyPose() {
		val model = model()
		val moved = setOf(0, 3)

		for (pose in offKeyPoses()) {
			val stage = ElementEditStage.Posed(pose, emptyMap())
			val before = assertNotNull(warpLatticeAt(model, pose, outerId))
			val target = shifted(before.world, moved, 7f, -4f)

			val forms = posedLatticeForms(model, stage, mapOf(outerId to target), mapOf(outerId to moved))
			val form = assertNotNull(forms[outerId])
			val after = assertNotNull(warpLatticeAt(model, pose, outerId, forms))

			assertNear(target, after.world, 1e-3f, "the posed lattice is where it was dragged at $pose")
			// The pending form is the grid's own share: the blend shape adds on top of it to give what is shown.
			assertNear(after.local, FloatArray(form.controlPoints.size) { componentIndex -> form.controlPoints[componentIndex] + (before.local[componentIndex] - before.gridLocal[componentIndex]) }, 1e-3f, "the blend shape is taken out")
		}
	}

	@Test
	fun aNestedWarpFollowsItsMovedParentAndItsOwnMovedPointsLandToo() {
		val model = model()
		val pose = mapOf(angleX to 0.4f, angleY to 0.3f, smile to 0.5f)
		val stage = ElementEditStage.Posed(pose, emptyMap())
		val outerBefore = assertNotNull(warpLatticeAt(model, pose, outerId)).world
		val innerBefore = assertNotNull(warpLatticeAt(model, pose, innerId)).world
		val everyOuterPoint = (0 until 4).toSet()

		val followed = posedLatticeForms(model, stage, mapOf(outerId to shifted(outerBefore, everyOuterPoint, 6f, 3f), innerId to innerBefore), mapOf(outerId to everyOuterPoint, innerId to emptySet()))
		assertNear(shifted(innerBefore, everyOuterPoint, 6f, 3f), assertNotNull(warpLatticeAt(model, pose, innerId, followed)).world, 1e-3f, "the unmoved inner warp rides its moved parent")

		val innerTarget = shifted(shifted(innerBefore, everyOuterPoint, 6f, 3f), setOf(2), -2f, 1f)
		val both = posedLatticeForms(model, stage, mapOf(innerId to innerTarget, outerId to shifted(outerBefore, everyOuterPoint, 6f, 3f)), mapOf(outerId to everyOuterPoint, innerId to setOf(2)))
		assertNear(innerTarget, assertNotNull(warpLatticeAt(model, pose, innerId, both)).world, 1e-3f, "parents first: the inner point lands through the moved parent")
	}

	@Test
	fun pendingLatticesAreThePoseTheShapeStartsFrom() {
		val model = model()
		val pose = mapOf(angleX to -0.3f, angleY to 0.6f)
		val held = assertNotNull(posedLatticeForms(model, ElementEditStage.Posed(pose, emptyMap()), mapOf(outerId to shifted(assertNotNull(warpLatticeAt(model, pose, outerId)).world, setOf(1), 5f, 0f)), mapOf(outerId to setOf(1)))[outerId])
		val stage = ElementEditStage.Posed(pose, mapOf(outerId to held))
		val shown = assertNotNull(captureLatticeWorld(model, outerId, stage)).world

		val forms = posedLatticeForms(model, stage, mapOf(outerId to shifted(shown, setOf(2), 0f, -3f)), mapOf(outerId to setOf(2)))

		assertNear(shifted(shown, setOf(2), 0f, -3f), assertNotNull(warpLatticeAt(model, pose, outerId, forms)).world, 1e-3f, "the second shape builds on the first")
	}

	@Test
	fun editModeEditsAtRestAndPoseModeAtThePose() {
		val pose = mapOf(angleX to 0.5f)
		val pending = mapOf(outerId to WarpLatticeForm(lattice(1f)))

		assertSame(ElementEditStage.Rest, elementEditStageOf(EditorMode.Edit, pose, pending))
		val posed = elementEditStageOf(EditorMode.Pose, pose, pending)
		assertTrue(posed is ElementEditStage.Posed)
		assertEquals(pose, posed.pose)
		assertSame(pending, posed.latticeOverrides)
	}
}