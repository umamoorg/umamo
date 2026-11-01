package org.umamo.render.eval

import org.umamo.runtime.keyform.withEveryLatticeMapped
import org.umamo.runtime.keyform.withLatticeShifted
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
import org.umamo.runtime.model.applyAffine
import kotlin.math.abs
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the warp lattice the editor reads: its world positions are the baked world's, its parent-space lattice
 * maps there through its parent mapping, a pending lattice replaces the grid blend alone, and the whole-lattice
 * transforms do what they promise to the evaluated lattice at every pose - shifted by the delta, or the affine
 * of the old one - blend shapes included, under a rotation parent.
 */
class WarpLatticeTest {
	private val tolerance = 1e-3f
	private val angleX = ParameterId("ParamAngleX")
	private val angleY = ParameterId("ParamAngleY")
	private val smile = ParameterId("ParamSmile")
	private val warpId = DeformerId("warp")
	private val rotationId = DeformerId("rotation")

	/**
	 * A 1x1 lattice offset and skewed by [seed], so every cell of the grid differs.
	 *
	 * @param Float seed The lattice's variation.
	 * @return FloatArray The four control points.
	 */
	private fun lattice(seed: Float): FloatArray =
		floatArrayOf(seed, -seed, 10f + seed * 0.5f, seed, seed * 0.25f, 10f - seed, 10f + seed, 10f + seed * 2f)

	/**
	 * A warp on a 3x2 grid over angle X and angle Y, with a smile blend shape, under a turned and scaled rotation.
	 *
	 * @return PuppetModel The rig.
	 */
	private fun model(): PuppetModel {
		val cells =
			(0 until 2).flatMap { yIndex ->
				(0 until 3).map { xIndex -> KeyformCell(intArrayOf(xIndex, yIndex), WarpLatticeForm(lattice(xIndex * 3f + yIndex * 7f))) }
			}
		val warp =
			Deformer.Warp(
				id = warpId,
				name = "warp",
				parent = rotationId,
				partId = null,
				rows = 1,
				columns = 1,
				isQuadTransform = true,
				geometryGrid = KeyformGrid(listOf(KeyformAxis(angleX, floatArrayOf(-1f, 0f, 1f)), KeyformAxis(angleY, floatArrayOf(0f, 1f))), cells),
				blendShapes = listOf(BlendShapeBinding(smile, floatArrayOf(0f, 1f), neutralIndex = 0, forms = listOf(null, WarpForm(lattice(40f))))),
			)
		val rotation =
			Deformer.Rotation(
				id = rotationId,
				name = "rotation",
				parent = null,
				partId = null,
				baseAngle = 0f,
				// Two keys holding one form: a one-key axis would hide the rotation, and the warp under it, at
				// every other value of the parameter.
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angleX, floatArrayOf(-1f, 1f))),
						listOf(KeyformCell(intArrayOf(0), RotationPivotForm(100f, 50f, 30f, 2f)), KeyformCell(intArrayOf(1), RotationPivotForm(100f, 50f, 30f, 2f))),
					),
			)

		return PuppetModel(
			parameters = listOf(Parameter(angleX, "X", -1f, 1f, 0f), Parameter(angleY, "Y", 0f, 1f, 0f), Parameter(smile, "Smile", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers = listOf(rotation, warp),
			drawables = emptyList(),
			rootChildren = emptyList(),
			rootPartId = null,
		)
	}

	/**
	 * The model with its warp replaced by [transform] of it.
	 *
	 * @param PuppetModel model The rig.
	 * @param Function transform The warp's replacement.
	 * @return PuppetModel The rig with the warp replaced.
	 */
	private fun withWarp(model: PuppetModel, transform: (Deformer.Warp) -> Deformer.Warp): PuppetModel =
		model.copy(deformers = model.deformers.map { deformer -> if (deformer is Deformer.Warp) transform(deformer) else deformer })

	/**
	 * Random poses across every parameter's range, the defaults among them.
	 *
	 * @return List The poses.
	 */
	private fun poses(): List<Map<ParameterId, Float>> {
		val random = Random(7)

		return listOf(emptyMap<ParameterId, Float>()) +
			List(40) { mapOf(angleX to random.nextFloat() * 2f - 1f, angleY to random.nextFloat(), smile to random.nextFloat()) }
	}

	/**
	 * Asserts two interleaved arrays agree within [tolerance].
	 *
	 * @param FloatArray expected The expected values.
	 * @param FloatArray actual The actual values.
	 * @param String message The failure context.
	 */
	private fun assertClose(expected: FloatArray, actual: FloatArray, message: String) {
		assertEquals(expected.size, actual.size, message)

		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= tolerance, "$message: component $componentIndex is ${actual[componentIndex]}, not ${expected[componentIndex]}")
		}
	}

	/** The world lattice is the baked world's control points with y turned up, and the parent mapping takes the local one there. */
	@Test
	fun theLatticeIsWhereTheBakeDrawsIt() {
		val model = model()

		for (pose in poses()) {
			val lattice = assertNotNull(warpLatticeAt(model, pose, warpId))
			val poseParameters = PoseParameters(model, pose)
			val baked = buildDeformerWorlds(model.deformers, poseParameters.paramValue, poseParameters.defaultValue)[warpId] as WarpWorld
			val expectedWorld = FloatArray(baked.cp.size) { componentIndex -> if (componentIndex % 2 == 0) baked.cp[componentIndex] else -baked.cp[componentIndex] }

			assertContentEquals(expectedWorld, lattice.world, "world at $pose")
			assertClose(lattice.world, lattice.parentMapping.localToWorld(lattice.local), "local through the parent at $pose")
		}
	}

	/** The rendered lattice is the grid blend plus the blend shape, which is zero at the defaults. */
	@Test
	fun theLocalLatticeIsTheGridBlendPlusTheBlendShapes() {
		val model = model()
		val rest = assertNotNull(warpLatticeAt(model, emptyMap(), warpId))
		val smiling = assertNotNull(warpLatticeAt(model, mapOf(smile to 1f), warpId))

		assertContentEquals(rest.gridLocal, rest.local, "no blend contribution at the defaults")
		// At full smile the blend adds its form minus the grid at the defaults.
		val expected = FloatArray(8) { componentIndex -> smiling.gridLocal[componentIndex] + lattice(40f)[componentIndex] - rest.gridLocal[componentIndex] }
		assertClose(expected, smiling.local, "full smile")
	}

	/** A pending lattice replaces the grid blend alone: the blend shape still adds on top, and other warps are untouched. */
	@Test
	fun anOverrideReplacesTheGridBlendAndKeepsTheBlendShapes() {
		val model = model()
		val pose = mapOf(angleX to 0.3f, smile to 1f)
		val pending = WarpLatticeForm(lattice(-5f))
		val plain = assertNotNull(warpLatticeAt(model, pose, warpId))
		val overridden = assertNotNull(warpLatticeAt(model, pose, warpId, mapOf(warpId to pending)))

		assertContentEquals(pending.controlPoints, overridden.gridLocal)
		val expected = FloatArray(8) { componentIndex -> pending.controlPoints[componentIndex] + plain.local[componentIndex] - plain.gridLocal[componentIndex] }
		assertClose(expected, overridden.local, "the blend shape adds on top")
		assertClose(overridden.world, overridden.parentMapping.localToWorld(overridden.local), "the override reaches the world")

		val unrelated = assertNotNull(warpLatticeAt(model, pose, warpId, mapOf(DeformerId("elsewhere") to pending)))
		assertContentEquals(plain.world, unrelated.world, "an override for another warp changes nothing")
	}

	/** A shift moves the rendered lattice by exactly the delta at every pose, blend shapes included. */
	@Test
	fun aShiftMovesTheLatticeByTheDeltaEverywhere() {
		val model = model()
		val delta = floatArrayOf(1.5f, -2f, 0.25f, 3f, -4f, 0.5f, 2f, -1f)
		val shifted = withWarp(model) { warp -> warp.withLatticeShifted(delta) }

		for (pose in poses()) {
			val before = assertNotNull(warpLatticeAt(model, pose, warpId))
			val after = assertNotNull(warpLatticeAt(shifted, pose, warpId))

			assertClose(FloatArray(8) { componentIndex -> before.local[componentIndex] + delta[componentIndex] }, after.local, "shifted at $pose")
		}
	}

	/** An affine moves the rendered lattice to the affine of the old one at every pose, blend shapes included. */
	@Test
	fun anAffineTransformsTheLatticeEverywhere() {
		val model = model()
		val turnScaleAndMove = floatArrayOf(0f, -1.5f, 7f, 1.5f, 0f, -3f)
		val transformed = withWarp(model) { warp -> warp.withEveryLatticeMapped { points -> applyAffine(points, turnScaleAndMove) } }

		for (pose in poses()) {
			val before = assertNotNull(warpLatticeAt(model, pose, warpId))
			val after = assertNotNull(warpLatticeAt(transformed, pose, warpId))
			val expected =
				FloatArray(8) { componentIndex ->
					val x = before.local[componentIndex - componentIndex % 2]
					val y = before.local[componentIndex - componentIndex % 2 + 1]
					if (componentIndex % 2 == 0) -1.5f * y + 7f else 1.5f * x - 3f
				}

			assertClose(expected, after.local, "transformed at $pose")
		}
	}

	/** The resolver answers per warp exactly what the single function does. */
	@Test
	fun theResolverAnswersAsTheSingleFunction() {
		val model = model()
		val pose = mapOf(angleX to -0.4f, angleY to 0.6f, smile to 0.5f)
		val single = assertNotNull(warpLatticeAt(model, pose, warpId))
		val resolved = assertNotNull(WarpLatticeResolver(model, pose).lattice(warpId))

		assertContentEquals(single.world, resolved.world)
		assertContentEquals(single.local, resolved.local)
		assertNull(WarpLatticeResolver(model, pose).lattice(rotationId), "a rotation has no lattice")
	}

	/** A pose outside the warp's keyed range hides it, so it has no lattice there. */
	@Test
	fun aHiddenWarpHasNoLattice() {
		assertNull(warpLatticeAt(model(), mapOf(angleX to 2f), warpId))
	}
}