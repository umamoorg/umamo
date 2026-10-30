package org.umamo.ui.transform

import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.BlendMode
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.OrgChild
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.assertArrayNear
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_BODY
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_HEAD
import org.umamo.ui.viewport.viewport2d.LATTICE_RIG_PARAMETER
import org.umamo.ui.viewport.viewport2d.latticeRigModel
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Pins the Edit-mode lattice reshape as it lowers onto the model: the dragged warp's rest lattice lands on the
 * dragged points, its every pose moves by the same parent-space offset, its children rest exactly where they rested,
 * nested edited warps each land where they were dragged, and a drag that folds the cage over a child reports it.
 */
class LatticeReshapeTest {
	private val inner = DrawableId("inner")

	// How close a landed world point must be, in world units: well inside the keep-in-place landing tolerance.
	private val tolerance = 1e-3f

	/**
	 * The shared lattice rig with a quad of art inside the body warp, in its normalized space.
	 *
	 * @return PuppetModel The model.
	 */
	private fun model(): PuppetModel {
		val base = latticeRigModel()
		val local = floatArrayOf(0.1f, 0.1f, 0.4f, 0.1f, 0.4f, 0.4f, 0.1f, 0.4f)
		val art =
			Drawable(
				id = inner,
				name = "inner",
				parentDeformerId = LATTICE_RIG_BODY,
				blendMode = BlendMode.Normal,
				maskedBy = emptyList(),
				mesh = DrawableMesh(floatArrayOf(4f, 4f, 16f, 4f, 16f, 16f, 4f, 16f), local, FloatArray(8), intArrayOf(0, 1, 2, 0, 2, 3)),
				geometryGrid = null,
			)

		return base.copy(drawables = base.drawables + art, rootChildren = base.rootChildren + OrgChild.Drawable(inner))
	}

	/**
	 * A warp's lattice in world space at [parameterValue].
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId deformerId The warp.
	 * @param Float? parameterValue The rig parameter's value, or null for the default.
	 * @return FloatArray The world points.
	 */
	private fun latticeWorld(model: PuppetModel, deformerId: DeformerId, parameterValue: Float? = null): FloatArray {
		val pose = parameterValue?.let { value -> mapOf(LATTICE_RIG_PARAMETER to value) }.orEmpty()

		return warpLatticeAt(model, pose, deformerId)!!.world
	}

	/**
	 * The inner art's rest world positions.
	 *
	 * @param PuppetModel model The model.
	 * @return FloatArray The world positions.
	 */
	private fun innerWorld(model: PuppetModel): FloatArray = captureDrawableWorlds(model, emptyMap(), listOf(inner)).single().world

	/**
	 * [world] with every point moved by ([deltaX], [deltaZ]), or only the points in [indices] when given.
	 *
	 * @param FloatArray world The world points.
	 * @param Float deltaX The x move.
	 * @param Float deltaZ The z move.
	 * @param Set<Int>? indices The points to move, or null for all.
	 * @return FloatArray The moved points.
	 */
	private fun moved(world: FloatArray, deltaX: Float, deltaZ: Float, indices: Set<Int>? = null): FloatArray =
		FloatArray(world.size) { componentIndex ->
			val inSet = indices == null || componentIndex / 2 in indices

			when {
				!inSet -> world[componentIndex]
				componentIndex % 2 == 0 -> world[componentIndex] + deltaX
				else -> world[componentIndex] + deltaZ
			}
		}

	@Test
	fun aDraggedLatticeLandsWhereItWasDraggedAtEveryPose() {
		val before = model()
		val target = moved(latticeWorld(before, LATTICE_RIG_BODY), 0f, 0f).also { points -> points[6] += 12f }
		val reshape = latticeReshapeOf(before, mapOf(LATTICE_RIG_BODY to target))

		assertArrayNear(target, latticeWorld(reshape.model, LATTICE_RIG_BODY), tolerance, "the rest lattice lands on the drag")

		for (parameterValue in listOf(-1f, -0.3f, 0.6f, 1f)) {
			val offset = FloatArray(target.size) { componentIndex -> latticeWorld(reshape.model, LATTICE_RIG_BODY, parameterValue)[componentIndex] - latticeWorld(before, LATTICE_RIG_BODY, parameterValue)[componentIndex] }
			val restOffset = FloatArray(target.size) { componentIndex -> target[componentIndex] - latticeWorld(before, LATTICE_RIG_BODY)[componentIndex] }
			assertArrayNear(restOffset, offset, tolerance, "the pose at $parameterValue moves by the same offset")
		}

		assertEquals(0, reshape.unconvertedCount)
	}

	@Test
	fun theChildrenRestWhereTheyRested() {
		val before = model()
		val target =
			moved(latticeWorld(before, LATTICE_RIG_BODY), 0f, 0f).also { points ->
				points[6] += 12f
				points[7] -= 8f
			}
		val reshape = latticeReshapeOf(before, mapOf(LATTICE_RIG_BODY to target))

		assertArrayNear(innerWorld(before), innerWorld(reshape.model), tolerance, "the art inside the cage does not move")
		assertArrayNear(latticeWorld(before, LATTICE_RIG_HEAD), latticeWorld(reshape.model, LATTICE_RIG_HEAD), tolerance, "the warp inside the cage does not move")
		assertEquals(0, reshape.unconvertedCount)
	}

	@Test
	fun nestedEditedWarpsEachLandWhereTheyWereDragged() {
		val before = model()
		val bodyTarget = moved(latticeWorld(before, LATTICE_RIG_BODY), 5f, -3f, setOf(1, 3))
		val headTarget = moved(latticeWorld(before, LATTICE_RIG_HEAD), -2f, 4f, setOf(0))
		// Listed child first: the reshape takes parents first whatever the order it is handed.
		val reshape = latticeReshapeOf(before, linkedMapOf(LATTICE_RIG_HEAD to headTarget, LATTICE_RIG_BODY to bodyTarget))

		assertArrayNear(bodyTarget, latticeWorld(reshape.model, LATTICE_RIG_BODY), tolerance, "the body lands on its drag")
		assertArrayNear(headTarget, latticeWorld(reshape.model, LATTICE_RIG_HEAD), tolerance, "the head lands on its own drag, not where the body's reshape carried it")
		assertArrayNear(innerWorld(before), innerWorld(reshape.model), tolerance, "the art stays put through both")
	}

	@Test
	fun aDragThatFoldsTheCageOverAChildReportsIt() {
		val before = model()
		// The bottom-right corner pulled past the top-left one folds the cage over everything inside it.
		val world = latticeWorld(before, LATTICE_RIG_BODY)
		val target =
			world.copyOf().also { points ->
				points[6] = world[0] - 30f
				points[7] = world[1] + 30f
			}
		val reshape = latticeReshapeOf(before, mapOf(LATTICE_RIG_BODY to target))

		assertTrue(reshape.unconvertedCount > 0, "a child the folded cage cannot reach is reported")
		assertArrayNear(target, latticeWorld(reshape.model, LATTICE_RIG_BODY), tolerance, "the reshape still stands")
	}

	@Test
	fun aDragThatMovesNothingChangesNothing() {
		val before = model()
		val reshape = latticeReshapeOf(before, mapOf(LATTICE_RIG_BODY to latticeWorld(before, LATTICE_RIG_BODY)))

		assertTrue(reshape.model === before, "the same model comes back")
	}
}