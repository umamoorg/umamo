package org.umamo.ui.transform

import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.BlendShapeBinding
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.KeyformAxis
import org.umamo.runtime.model.KeyformCell
import org.umamo.runtime.model.KeyformGrid
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.Parameter
import org.umamo.runtime.model.ParameterId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.RotationPivotForm
import org.umamo.runtime.model.WarpForm
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Pins a warp moved as a whole object: under a root or a rotation parent the evaluated lattice is the world affine of
 * the old one at every pose, keyed motion and blend shapes included, and the art inside follows; under a warp parent
 * the rest lattice lands on the affine of the rest; and the drag preview shows exactly what the confirm lands.
 */
class WarpObjectTransformTest {
	private val angle = ParameterId("ParamAngle")
	private val blend = ParameterId("ParamBlend")
	private val root = DeformerId("root")
	private val nested = DeformerId("nested")
	private val pivot = DeformerId("pivot")
	private val spun = DeformerId("spun")
	private val inner = DrawableId("inner")

	// A turn of 30 degrees about (20, -20), then a shift of (7, -3): a world affine with every part in play.
	private val turn: FloatArray =
		run {
			val cosAngle = cos(30.0 * PI / 180.0).toFloat()
			val sinAngle = sin(30.0 * PI / 180.0).toFloat()
			floatArrayOf(cosAngle, -sinAngle, 20f - 20f * cosAngle - 20f * sinAngle + 7f, sinAngle, cosAngle, -20f - 20f * sinAngle + 20f * cosAngle - 3f)
		}

	// A tolerance well inside a pixel: every check compares world points tens of units across.
	private val tolerance = 1e-3f

	/**
	 * A 1 x 1 square lattice of [size] at ([left], [top]) in its parent's space, nudged right by [shift].
	 *
	 * @param Float left The square's left edge.
	 * @param Float top The square's top edge.
	 * @param Float size Its side.
	 * @param Float shift A rightward shift of every point.
	 * @return FloatArray The four points, row by row.
	 */
	private fun square(left: Float, top: Float, size: Float, shift: Float = 0f): FloatArray =
		floatArrayOf(left + shift, top, left + size + shift, top, left + shift, top + size, left + size + shift, top + size)

	/**
	 * A 1 x 1 warp keyed on the angle parameter at -1 and 1.
	 *
	 * @param DeformerId id The warp.
	 * @param DeformerId? parent Its parent.
	 * @param FloatArray low Its lattice at -1.
	 * @param FloatArray high Its lattice at 1.
	 * @param List<BlendShapeBinding<WarpForm>> blendShapes Its blend shapes.
	 * @return Deformer.Warp The warp.
	 */
	private fun warp(id: DeformerId, parent: DeformerId?, low: FloatArray, high: FloatArray, blendShapes: List<BlendShapeBinding<WarpForm>> = emptyList()): Deformer.Warp =
		Deformer.Warp(
			id = id,
			name = id.raw,
			parent = parent,
			partId = null,
			rows = 1,
			columns = 1,
			isQuadTransform = true,
			geometryGrid =
				KeyformGrid(
					listOf(KeyformAxis(angle, floatArrayOf(-1f, 1f))),
					listOf(KeyformCell(intArrayOf(0), WarpLatticeForm(low)), KeyformCell(intArrayOf(1), WarpLatticeForm(high))),
				),
			blendShapes = blendShapes,
		)

	/**
	 * The rig: a keyed root warp with a blend shape and art inside it, a warp nested in it, and a warp under a keyed
	 * rotation.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val rootWarp =
			warp(
				root,
				null,
				square(0f, 0f, 40f),
				square(0f, 0f, 40f, shift = 10f),
				listOf(BlendShapeBinding(blend, floatArrayOf(0f, 1f), 0, listOf(null, WarpForm(floatArrayOf(0f, 0f, 40f, 0f, 0f, 40f, 46f, 45f))))),
			)
		val rotation =
			Deformer.Rotation(
				id = pivot,
				name = "pivot",
				parent = null,
				partId = null,
				baseAngle = 0f,
				geometryGrid =
					KeyformGrid(
						listOf(KeyformAxis(angle, floatArrayOf(-1f, 1f))),
						listOf(KeyformCell(intArrayOf(0), RotationPivotForm(100f, 100f, -10f, 1f)), KeyformCell(intArrayOf(1), RotationPivotForm(100f, 100f, 10f, 1f))),
					),
			)
		val art =
			Drawable(
				id = inner,
				name = "inner",
				parentDeformerId = root,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(floatArrayOf(4f, 4f, 16f, 4f, 16f, 16f, 4f, 16f), floatArrayOf(0.1f, 0.1f, 0.4f, 0.1f, 0.4f, 0.4f, 0.1f, 0.4f), FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)

		return PuppetModel(
			parameters = listOf(Parameter(angle, "Angle", -1f, 1f, 0f), Parameter(blend, "Blend", 0f, 1f, 0f)),
			parts = emptyList(),
			deformers =
				listOf(
					rootWarp,
					warp(nested, root, square(0.25f, 0.25f, 0.5f), square(0.25f, 0.25f, 0.5f, shift = 0.1f)),
					rotation,
					warp(spun, pivot, square(-10f, -10f, 20f), square(-10f, -10f, 20f, shift = 3f)),
				),
			drawables = listOf(art),
			rootChildren = listOf(OrgChild.Drawable(inner)),
			rootPartId = null,
		)
	}

	/**
	 * [world] moved by [affine].
	 *
	 * @param FloatArray world Interleaved x, z points.
	 * @param FloatArray affine The 2x3 affine.
	 * @return FloatArray The moved points.
	 */
	private fun moved(world: FloatArray, affine: FloatArray): FloatArray =
		FloatArray(world.size) { componentIndex ->
			val x = world[componentIndex - componentIndex % 2]
			val z = world[componentIndex - componentIndex % 2 + 1]

			if (componentIndex % 2 == 0) affine[0] * x + affine[1] * z + affine[2] else affine[3] * x + affine[4] * z + affine[5]
		}

	/**
	 * Asserts two arrays agree within [limit].
	 *
	 * @param FloatArray expected The expected values.
	 * @param FloatArray actual The actual values.
	 * @param Float limit The largest difference allowed.
	 * @param String message What is compared.
	 */
	private fun assertClose(expected: FloatArray, actual: FloatArray, limit: Float, message: String) {
		assertEquals(expected.size, actual.size, message)

		for (componentIndex in expected.indices) {
			assertTrue(abs(expected[componentIndex] - actual[componentIndex]) <= limit, "$message at $componentIndex: ${expected[componentIndex]} vs ${actual[componentIndex]}")
		}
	}

	/** Every pose the exactness checks sweep: the angle across and off its keys, with and without the blend shape. */
	private val poses: List<Map<ParameterId, Float>> =
		listOf(-1f, -0.4f, 0f, 0.7f, 1f).flatMap { angleValue -> listOf(0f, 0.5f, 1f).map { blendValue -> mapOf(angle to angleValue, blend to blendValue) } }

	@Test
	fun aRootWarpMovesByTheAffineAtEveryPoseItsArtFollowing() {
		val before = model()
		val after = before.withWarpsTransformed(mapOf(root to turn))

		for (pose in poses) {
			val lattice = assertNotNull(warpLatticeAt(before, pose, root)).world
			assertClose(moved(lattice, turn), assertNotNull(warpLatticeAt(after, pose, root)).world, tolerance, "the lattice at $pose")
			val art = captureDrawableWorlds(before, pose, listOf(inner)).single().world
			assertClose(moved(art, turn), captureDrawableWorlds(after, pose, listOf(inner)).single().world, tolerance, "the art inside at $pose")
			val child = assertNotNull(warpLatticeAt(before, pose, nested)).world
			assertClose(moved(child, turn), assertNotNull(warpLatticeAt(after, pose, nested)).world, tolerance, "the warp inside at $pose")
		}
	}

	/**
	 * Under a rotation the warp moves in its parent's frame: at rest it lands on the world affine, and at every pose its
	 * lattice in the parent's space is the old one moved the same way - so the rotation's own keyed turn still swings
	 * it about the rotation's pivot, as it swung it before.
	 */
	@Test
	fun aWarpUnderARotationMovesInItsParentsFrame() {
		val before = model()
		val after = before.withWarpsTransformed(mapOf(spun to turn))
		val rest = assertNotNull(warpLatticeAt(before, emptyMap(), spun))
		assertClose(moved(rest.world, turn), assertNotNull(warpLatticeAt(after, emptyMap(), spun)).world, tolerance, "the rest lattice lands on the affine")

		for (pose in poses) {
			val local = assertNotNull(warpLatticeAt(before, pose, spun)).local
			val world = rest.parentMapping.localToWorld(local)
			val expected = rest.parentMapping.worldToLocalLinearized(moved(world, turn), local, world, (0 until local.size / 2).toSet())
			assertClose(expected, assertNotNull(warpLatticeAt(after, pose, spun)).local, tolerance, "the lattice in its parent's space at $pose")
		}
	}

	@Test
	fun aWarpUnderAWarpLandsOnTheAffineAtRest() {
		val before = model()
		val after = before.withWarpsTransformed(mapOf(nested to turn))

		val rest = assertNotNull(warpLatticeAt(before, emptyMap(), nested)).world
		assertClose(moved(rest, turn), assertNotNull(warpLatticeAt(after, emptyMap(), nested)).world, 1e-2f, "the rest lattice")
		assertClose(assertNotNull(warpLatticeAt(before, emptyMap(), root)).world, assertNotNull(warpLatticeAt(after, emptyMap(), root)).world, 0f, "its parent stays put")
	}

	@Test
	fun theDragPreviewShowsWhatTheConfirmLands() {
		val before = model()

		for (warpId in listOf(root, nested, spun)) {
			val landed = assertNotNull(landedWarp(before, warpId, turn))
			val preview = assertNotNull(warpPreviewOf(before, landed))
			val previewed = assertNotNull(warpLatticeAt(before, emptyMap(), warpId, latticeOverrides = mapOf(warpId to preview))).world
			val committed = assertNotNull(warpLatticeAt(before.withWarpsReplaced(mapOf(warpId to landed)), emptyMap(), warpId)).world
			assertClose(committed, previewed, 0f, "$warpId: the override draws the landed lattice")
		}
	}
}