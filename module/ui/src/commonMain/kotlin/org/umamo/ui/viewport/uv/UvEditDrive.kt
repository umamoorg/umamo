package org.umamo.ui.viewport.uv

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.ModalTransformCapture
import org.umamo.edit.ProportionalInfluence
import org.umamo.edit.TransformGestureParameters
import org.umamo.edit.TransformPivotGroup
import org.umamo.edit.withMeshUvs
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.viewport.gizmo.applyOperator

/*
 * The UV Edit modal drive's compute: each moving mesh's frozen display coordinates through the operator,
 * converted back through the gesture's frame to stored coordinates, then one batch fold into the model.
 * Pure over its request, so the drive worker runs it off the UI thread; there is no deformer
 * inverse here, so it runs one mesh after another.
 */

/**
 * One moving mesh's share of a UV drive, taken on the UI thread: the frozen display coordinates and pivot
 * groups, and the proportional halo and moved set AS THEY WERE when the request was made (the UI thread
 * reassigns the capture's own on a radius or falloff change).
 *
 * @property DrawableId drawableId The drawable.
 * @property FloatArray positions The frozen display coordinates the operator transforms (never mutated).
 * @property List<TransformPivotGroup> groups The pivot groups its covered vertices turn about.
 * @property Map<Int, ProportionalInfluence> influence The proportional halo; empty when off.
 * @property Set<Int> movedIndices Every vertex the drive moves.
 */
internal class UvDriveJob(
	val drawableId: DrawableId,
	val positions: FloatArray,
	val groups: List<TransformPivotGroup>,
	val influence: Map<Int, ProportionalInfluence>,
	val movedIndices: Set<Int>,
)

/**
 * One UV drive's request, resolved on the UI thread: resolving the parameters advances the rotation
 * tracker, which must see every pointer event, and each mesh's halo is read as it is when the request is
 * made.
 *
 * @property MeshOperatorKind operator The operator.
 * @property TransformGestureParameters parameters The parameters every mesh applies, in display texels.
 * @property List<UvDriveJob> jobs The moving meshes, in capture order.
 * @property UvEditFrame frame The gesture's frame, which converts display coordinates back to stored ones.
 * @property PuppetModel baseModel The model the new coordinates fold onto.
 */
internal class UvDriveRequest(
	val operator: MeshOperatorKind,
	val parameters: TransformGestureParameters,
	val jobs: List<UvDriveJob>,
	val frame: UvEditFrame,
	val baseModel: PuppetModel,
)

/**
 * One UV drive's result.
 *
 * @property Map<DrawableId, FloatArray> preview Each moving mesh's transformed display coordinates, in
 *   capture order: what the confirm converts and commits.
 * @property Map<DrawableId, FloatArray> storedUvs The same, converted whole to stored coordinates.
 * @property PuppetModel folded The request's base model with [storedUvs] folded in.
 */
internal class UvDriveResult(
	val preview: Map<DrawableId, FloatArray>,
	val storedUvs: Map<DrawableId, FloatArray>,
	val folded: PuppetModel,
)

/**
 * The drive's jobs over a capture, each mesh's halo and moved set read now.
 *
 * @param ModalTransformCapture transform The capture.
 * @return List<UvDriveJob> The jobs, in capture order.
 */
internal fun uvDriveJobs(transform: ModalTransformCapture): List<UvDriveJob> =
	transform.entries.map { entry ->
		UvDriveJob(entry.drawableId, entry.positions, entry.groups, entry.influence, entry.movedIndices)
	}

/**
 * The drive: each mesh's display coordinates through the operator, converted whole to stored coordinates
 * (the preview is transient and never committed, so the drift the commit avoids is invisible here), and
 * folded onto the request's model in one pass.
 *
 * @param UvDriveRequest request The request.
 * @return UvDriveResult The result.
 */
internal fun computeUvDrive(request: UvDriveRequest): UvDriveResult {
	val preview = LinkedHashMap<DrawableId, FloatArray>(request.jobs.size)
	val storedUvs = LinkedHashMap<DrawableId, FloatArray>(request.jobs.size)

	for (job in request.jobs) {
		val transformedDisplay = applyOperator(request.operator, job.positions, job.groups, request.parameters, job.influence)
		preview[job.drawableId] = transformedDisplay
		storedUvs[job.drawableId] = request.frame.storedUvs(transformedDisplay)
	}

	return UvDriveResult(preview, storedUvs, request.baseModel.withMeshUvs(storedUvs))
}