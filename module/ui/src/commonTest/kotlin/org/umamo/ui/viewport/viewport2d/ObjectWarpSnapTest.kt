package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.DeformerChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.SelectionTarget
import org.umamo.edit.SnapKind
import org.umamo.render.eval.warpLatticeAt
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins Object-mode Shift+S over warps: a warp snaps as a whole by its lattice's centroid, the warp inside it
 * following, as one "Move Objects" step, and the cursor snaps read a warp's centroid like a drawable's.
 *
 * The lattice rig's body warp spans world x 0..40 and z -40..0, so its centroid is (20, -20).
 */
class ObjectWarpSnapTest {
	/**
	 * A warp's rest lattice in world space.
	 *
	 * @param PuppetModel model The model.
	 * @param DeformerId warpId The warp.
	 * @return FloatArray The world points.
	 */
	private fun restWorld(model: PuppetModel, warpId: DeformerId): FloatArray = assertNotNull(warpLatticeAt(model, emptyMap(), warpId)).world

	/**
	 * The mean of a lattice's points.
	 *
	 * @param FloatArray world The world points.
	 * @return Pair<Float, Float> The centroid.
	 */
	private fun centroid(world: FloatArray): Pair<Float, Float> {
		val count = world.size / 2

		return (0 until count).sumOf { pointIndex -> world[pointIndex * 2].toDouble() }.toFloat() / count to
			(0 until count).sumOf { pointIndex -> world[pointIndex * 2 + 1].toDouble() }.toFloat() / count
	}

	@Test
	fun aWarpSnapsToTheCursorAsAWhole() {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		val headBefore = restWorld(session.model.value, LATTICE_RIG_HEAD)
		session.setCursor2d(100f, -100f)

		handleObjectSnapRequest(session, SnapKind.SelectionToCursor, 1f)

		val (centerX, centerZ) = centroid(restWorld(session.model.value, LATTICE_RIG_BODY))
		assertEquals(100f, centerX, 1e-3f, "the lattice's centroid is on the cursor")
		assertEquals(-100f, centerZ, 1e-3f)
		assertEquals(headBefore[0] + 80f, restWorld(session.model.value, LATTICE_RIG_HEAD)[0], 1e-3f, "the warp inside followed")
		assertEquals("change.object.move", session.historyView.value.steps.last().labelKey)
	}

	@Test
	fun theCursorSnapsReadAWarpsCentroid() {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))

		handleObjectSnapRequest(session, SnapKind.CursorToSelected, 1f)

		val cursor = assertNotNull(session.cursor2d.value)
		assertEquals(20f, cursor.worldX, 1e-3f)
		assertEquals(-20f, cursor.worldZ, 1e-3f)
	}

	/** Object mode draws a warp's pending lattice, so the cursor snaps read the lattice as drawn, not as keyed. */
	@Test
	fun theCursorSnapsReadAPendingLatticeAsTheViewportDrawsIt() {
		val session = latticeRigSession(listOf(SelectionTarget.Deformer(LATTICE_RIG_BODY)))
		val shifted = WarpLatticeForm(floatArrayOf(10f, 0f, 50f, 0f, 10f, 40f, 50f, 40f))
		session.commitPendingLatticeEdits(DeformerChange.PoseLattice(listOf(LATTICE_RIG_BODY), MeshOperatorKind.Grab), mapOf(LATTICE_RIG_BODY to shifted))

		handleObjectSnapRequest(session, SnapKind.CursorToSelected, 1f)

		val cursor = assertNotNull(session.cursor2d.value)
		assertEquals(30f, cursor.worldX, 1e-3f, "the pending lattice sits 10 to the right of the keyed one")
		assertEquals(-20f, cursor.worldZ, 1e-3f)
	}
}