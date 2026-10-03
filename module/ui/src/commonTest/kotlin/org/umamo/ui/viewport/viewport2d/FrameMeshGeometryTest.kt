package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.MeshRestPositions
import org.umamo.edit.MeshTopology
import org.umamo.edit.withMeshPositions
import org.umamo.runtime.model.Drawable
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertSame

/**
 * Pins the displayed frame's geometry the Edit overlay draws its wireframes from: posed from the frame's
 * positions, reused per drawable by instance identity, and sharing the live edge set while the topology
 * is the live one.
 */
class FrameMeshGeometryTest {
	private val liveModel = gizmoRigModel()
	private val liveGeometry = editMeshGeometries(liveModel, listOf(RIG_QUAD, RIG_OTHER))

	/** A frame posed from moved positions carries them to world, z negated like the live projection. */
	@Test
	fun theFramePosesItsOwnPositions() {
		val frameModel = liveModel.withMeshPositions(RIG_QUAD, MeshRestPositions.shared(floatArrayOf(5f, 1f, 20f, 0f, 20f, 20f, 0f, 20f)))

		val frame = frameMeshGeometries(frameModel, liveGeometry, HashMap())

		assertEquals(listOf(5f, -1f, 20f, -0f, 20f, -20f, 0f, -20f), frame.getValue(RIG_QUAD).worldPosed!!.toList())
	}

	/** An unchanged drawable instance reuses its entry; the drawable a drive replaced is posed again. */
	@Test
	fun anEntryIsReusedByDrawableIdentity() {
		val reuse = HashMap<DrawableId, Pair<Drawable, FrameMeshGeometry>>()
		val first = frameMeshGeometries(liveModel, liveGeometry, reuse)
		val folded = liveModel.withMeshPositions(RIG_QUAD, MeshRestPositions.shared(floatArrayOf(5f, 0f, 20f, 0f, 20f, 20f, 0f, 20f)))

		val second = frameMeshGeometries(folded, liveGeometry, reuse)

		assertSame(first.getValue(RIG_OTHER), second.getValue(RIG_OTHER), "the bystander carried over")
		assertNotSame(first.getValue(RIG_QUAD), second.getValue(RIG_QUAD), "the moved mesh was posed again")
	}

	/** While the frame shares the live indices array, it shares the live edge set too. */
	@Test
	fun theLiveEdgesAreSharedWhileTheTopologyIs() {
		val folded = liveModel.withMeshPositions(RIG_QUAD, MeshRestPositions.shared(floatArrayOf(5f, 0f, 20f, 0f, 20f, 20f, 0f, 20f)))

		val frame = frameMeshGeometries(folded, liveGeometry, HashMap())

		assertSame(liveGeometry.first { geometry -> geometry.drawableId == RIG_QUAD }.edges, frame.getValue(RIG_QUAD).edges)
	}

	/** A frame on other indices (a topology edit the raster has not caught up with) derives its own edges. */
	@Test
	fun anotherTopologyDerivesItsOwnEdges() {
		val triangleIndices = intArrayOf(0, 1, 2)
		val frameModel =
			liveModel.copy(
				drawables =
					liveModel.drawables.map { drawable ->
						if (drawable.id == RIG_QUAD) {
							drawable.copy(mesh = DrawableMesh.withLocalEqualToCanvas(drawable.mesh!!.positions, drawable.mesh!!.uvs, triangleIndices))
						} else {
							drawable
						}
					},
			)

		val frame = frameMeshGeometries(frameModel, liveGeometry, HashMap())

		assertEquals(MeshTopology.uniqueEdges(triangleIndices), frame.getValue(RIG_QUAD).edges)
		assertSame(triangleIndices, frame.getValue(RIG_QUAD).indices)
	}

	/** A drawable the frame does not hold has no entry, so the draw pass falls back to the live shape. */
	@Test
	fun aDrawableMissingFromTheFrameHasNoEntry() {
		val frameModel = liveModel.copy(drawables = liveModel.drawables.filter { drawable -> drawable.id != RIG_OTHER })

		val frame = frameMeshGeometries(frameModel, liveGeometry, HashMap())

		assertFalse(RIG_OTHER in frame)
		assertEquals(setOf(RIG_QUAD), frame.keys)
	}
}