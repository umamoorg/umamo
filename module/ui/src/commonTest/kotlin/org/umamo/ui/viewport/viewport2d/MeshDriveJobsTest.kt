package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ProportionalEditState
import org.umamo.edit.ProportionalFalloff
import org.umamo.edit.TransformPivotMode
import org.umamo.edit.transform.IndividualOriginScope
import org.umamo.edit.transform.ModalCaptureSource
import org.umamo.edit.transform.buildModalTransformCapture
import org.umamo.runtime.model.ElementOwner
import org.umamo.ui.transform.captureDrawableWorlds
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

/**
 * Pins which capture entries the 2D drive takes as jobs: one per entry with something to move, so a bystander the
 * halo does not reach is never inverted, folded, or committed - under a whole-mesh capture too.
 */
class MeshDriveJobsTest {
	/** A source with nothing covered gets no job in either branch, and a halo reaching it gives it one. */
	@Test
	fun anEntryWithNothingToMoveGetsNoJob() {
		val geometries = captureDrawableWorlds(gizmoRigModel(), emptyMap(), listOf(RIG_QUAD, RIG_OTHER))
		val (quad, other) = geometries
		// The quad's vertex 1 at (20, 0) is covered; the triangle beside it, with its (40, 0) corner 20 away, has nothing.
		val sources =
			listOf(
				ModalCaptureSource(quad.owner, quad.world, ElementTopology.unconnected(quad.world.size / 2), setOf(1)),
				ModalCaptureSource(other.owner, other.world, ElementTopology.unconnected(other.world.size / 2), emptySet()),
			)
		val transform = assertNotNull(buildModalTransformCapture(sources, TransformPivotMode.MedianPoint, IndividualOriginScope.WholeMesh, MeshOperatorKind.Grab, null, null))
		val geometryByOwner = geometries.associateBy { geometry -> geometry.owner }
		val quadOnly = listOf<ElementOwner>(ElementOwner.Drawable(RIG_QUAD))

		assertEquals(quadOnly, meshDriveJobs(transform, geometryByOwner, wholeMeshes = true).map { job -> job.owner }, "a whole-mesh capture moves only what it covers")
		assertEquals(quadOnly, meshDriveJobs(transform, geometryByOwner, wholeMeshes = false).map { job -> job.owner }, "the bystander has no halo yet")

		transform.applyProportional(ProportionalEditState(ProportionalFalloff.Linear, 30f), 30f)
		assertEquals(quadOnly + ElementOwner.Drawable(RIG_OTHER), meshDriveJobs(transform, geometryByOwner, wholeMeshes = false).map { job -> job.owner }, "the halo reaching it gives it a job")
		assertEquals(quadOnly, meshDriveJobs(transform, geometryByOwner, wholeMeshes = true).map { job -> job.owner }, "a whole-mesh capture reads no halo")
	}
}