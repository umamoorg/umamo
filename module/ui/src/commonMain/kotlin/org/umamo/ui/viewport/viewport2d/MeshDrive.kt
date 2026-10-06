package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.ProportionalInfluence
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformPivotGroup
import org.umamo.edit.withMeshPositions
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.viewport.gizmo.DRIVE_MIN_CHUNK_WEIGHT
import org.umamo.ui.viewport.gizmo.applyOperator
import org.umamo.ui.viewport.gizmo.mapInBalancedChunks
import org.umamo.ui.viewport.gizmo.slideVertexByFactor

/*
 * The 2D modal drive's compute, shared by the Edit and Object transforms and the operation settings strip's
 * replays: each moving mesh's frozen world shape through the operator (or the Vertex Slide), back through its
 * deformer chain onto the base mesh, then one batch fold into the model.  Pure over its request, so the
 * drive worker can run it off the UI thread, and the per-mesh step in parallel.
 */

/**
 * One moving mesh's share of a drive, everything the compute reads, taken on the UI thread: the frozen
 * world shape and pivot groups, the proportional halo and moved set AS THEY WERE when the request was
 * made (the UI thread reassigns the capture's own on a radius or falloff change), and the frozen geometry
 * the inverse runs through.
 *
 * @property DrawableId drawableId The drawable.
 * @property FloatArray positions The frozen world positions the operator transforms (never mutated).
 * @property List<TransformPivotGroup> groups The pivot groups its covered vertices turn about.
 * @property Map<Int, ProportionalInfluence> influence The proportional halo; empty when off.
 * @property Set<Int> movedIndices Every vertex the drive moves.
 * @property DrawableWorldGeometry geometry The frozen geometry the world shape inverts through.
 */
internal class MeshDriveJob(
	val drawableId: DrawableId,
	val positions: FloatArray,
	val groups: List<TransformPivotGroup>,
	val influence: Map<Int, ProportionalInfluence>,
	val movedIndices: Set<Int>,
	val geometry: DrawableWorldGeometry,
)

/**
 * The Vertex Slide's share of a drive: which vertex slides, and toward which edge, as the request's pointer
 * landed it.
 *
 * @property DrawableId drawableId The mesh the vertex lives in.
 * @property Int activeVertex The sliding vertex.
 * @property SlideLanding landing The edge and factor.
 */
internal class SlideMove(
	val drawableId: DrawableId,
	val activeVertex: Int,
	val landing: SlideLanding,
)

/**
 * One drive's request, resolved on the UI thread: the operator and the parameters its pointer
 * frame resolved to (resolving them advances the rotation tracker, which must see every event), the moving
 * meshes, the slide when one lands, and the model the result folds onto.
 *
 * @property MeshOperatorKind operator The operator.
 * @property TransformGestureParameters parameters The parameters every mesh applies.
 * @property List<MeshDriveJob> jobs The moving meshes, in capture order.
 * @property SlideMove? slide The Vertex Slide's landing, or null (for a slide, the pointer landed on no edge).
 * @property PuppetModel baseModel The model the new positions fold onto.
 */
internal class MeshDriveRequest(
	val operator: MeshOperatorKind,
	val parameters: TransformGestureParameters,
	val jobs: List<MeshDriveJob>,
	val slide: SlideMove?,
	val baseModel: PuppetModel,
)

/**
 * One drive's result.
 *
 * @property Map<DrawableId, FloatArray> preview Each moving mesh's new base positions, in capture order.
 * @property PuppetModel folded The request's base model with them folded in.
 */
internal class MeshDriveResult(
	val preview: Map<DrawableId, FloatArray>,
	val folded: PuppetModel,
)

/**
 * The drive's jobs over a capture: one per entry with frozen geometry, its halo and moved set read now.
 *
 * @param ModalTransformCapture transform The capture.
 * @param Map<DrawableId, DrawableWorldGeometry> geometryById Each moving drawable's frozen geometry.
 * @param Boolean wholeMeshes True for an Object-mode capture: whole drawables, no halo, every covered vertex.
 * @return List<MeshDriveJob> The jobs, in capture order.
 */
internal fun meshDriveJobs(
	transform: ModalTransformCapture,
	geometryById: Map<DrawableId, DrawableWorldGeometry>,
	wholeMeshes: Boolean,
): List<MeshDriveJob> =
	transform.entries.mapNotNull { entry ->
		val geometry = geometryById[entry.drawableId] ?: return@mapNotNull null
		if (wholeMeshes) {
			MeshDriveJob(entry.drawableId, entry.positions, entry.groups, emptyMap(), entry.coveredIndices, geometry)
		} else {
			MeshDriveJob(entry.drawableId, entry.positions, entry.groups, entry.influence, entry.movedIndices, geometry)
		}
	}

/**
 * One mesh's new base positions under a request: its frozen world shape through the operator (a Vertex
 * Slide moves only the slide mesh's vertex, and nothing when the pointer landed on no edge), inverted back
 * onto the base mesh over its moved vertices.  Pure; the parallel compute runs it per mesh on any thread.
 *
 * @param MeshDriveJob job The mesh.
 * @param MeshDriveRequest request The request.
 * @return FloatArray The new base positions.
 */
internal fun meshJobBase(job: MeshDriveJob, request: MeshDriveRequest): FloatArray {
	val transformedWorld =
		if (request.operator == MeshOperatorKind.VertexSlide) {
			val slide = request.slide
			if (slide != null && slide.drawableId == job.drawableId) {
				slideVertexByFactor(job.positions, slide.activeVertex, slide.landing.neighborIndex, slide.landing.factor)
			} else {
				job.positions
			}
		} else {
			applyOperator(request.operator, job.positions, job.groups, request.parameters, job.influence)
		}
	return job.geometry.worldToBase(transformedWorld, job.movedIndices)
}

/**
 * The drive, one mesh after another: what a confirm settles with, and what a transform with no worker runs.
 *
 * @param MeshDriveRequest request The request.
 * @return MeshDriveResult The result.
 */
internal fun computeMeshDrive(request: MeshDriveRequest): MeshDriveResult = foldMeshDrive(request, request.jobs.map { job -> meshJobBase(job, request) })

/**
 * The drive with its meshes in parallel chunks on the caller's dispatcher, weighted by how many vertices
 * each moves; the same result as [computeMeshDrive], element for element.
 *
 * @param MeshDriveRequest request The request.
 * @param Int minChunkWeight The least weight a chunk carries (tests lower it to force chunking).
 * @return MeshDriveResult The result.
 */
internal suspend fun computeMeshDriveParallel(request: MeshDriveRequest, minChunkWeight: Int = DRIVE_MIN_CHUNK_WEIGHT): MeshDriveResult {
	val bases = mapInBalancedChunks(request.jobs, { job -> job.movedIndices.size + 1 }, minChunkWeight) { job -> meshJobBase(job, request) }
	return foldMeshDrive(request, bases)
}

/**
 * Pairs each job with its new base positions and folds them onto the request's model in one pass.
 *
 * @param MeshDriveRequest request The request.
 * @param List<FloatArray> bases Each job's new base positions, in job order.
 * @return MeshDriveResult The result.
 */
private fun foldMeshDrive(request: MeshDriveRequest, bases: List<FloatArray>): MeshDriveResult {
	val preview = LinkedHashMap<DrawableId, FloatArray>(request.jobs.size)
	for ((jobIndex, job) in request.jobs.withIndex()) {
		preview[job.drawableId] = bases[jobIndex]
	}
	return MeshDriveResult(preview, request.baseModel.withMeshPositions(preview))
}