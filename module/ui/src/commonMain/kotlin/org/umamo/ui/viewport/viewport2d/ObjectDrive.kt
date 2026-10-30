package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.runtime.model.Deformer
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.deformerId
import org.umamo.ui.transform.landedWarp
import org.umamo.ui.transform.warpPreviewOf
import org.umamo.ui.transform.withWarpsReplaced
import org.umamo.ui.viewport.gizmo.gestureAffine

/*
 * The Object-mode drive over whole objects: the drawables through the mesh drive (each one's world shape inverted
 * onto its rest arrays), and each warp moved as a whole by the gesture's affine about its pivot group - every form
 * it carries, its art following.  Shared by the modal transform and the operation strip's rerun, so the two land
 * the same.  Pure over its request, so the drive worker can run it off the UI thread.
 */

/**
 * One Object-mode drive's request.
 *
 * @property MeshDriveRequest mesh The drawables' share, as the mesh drive takes it.
 * @property Map<DeformerId, FloatArray> warpAffines Each moving warp's world affine for this frame.
 */
internal class ObjectDriveRequest(
	val mesh: MeshDriveRequest,
	val warpAffines: Map<DeformerId, FloatArray>,
)

/**
 * One Object-mode drive's result.
 *
 * @property MeshDriveResult mesh The drawables' new rest shapes and the folded model.
 * @property Map<DeformerId, Deformer.Warp> landedWarps Each moving warp moved as a whole.
 * @property Map<DeformerId, WarpLatticeForm> previews Each moving warp's grid lattice at the defaults, what the
 *   renderer's lattice preview shows.
 */
internal class ObjectDriveResult(
	val mesh: MeshDriveResult,
	val landedWarps: Map<DeformerId, Deformer.Warp>,
	val previews: Map<DeformerId, WarpLatticeForm>,
) {
	/** The model the drive lands as: the drawables folded in and the warps swapped for their moved forms. */
	val landed: PuppetModel get() = mesh.folded.withWarpsReplaced(landedWarps)
}

/**
 * Each warp entry's world affine under [parameters]: the operator about the entry's pivot group (its own centroid
 * for Individual Origins, the shared anchor otherwise).
 *
 * @param ModalTransformCapture transform The capture.
 * @param MeshOperatorKind operator The operator.
 * @param TransformGestureParameters parameters The numbers to apply.
 * @return Map<DeformerId, FloatArray> The warps' affines, in capture order.
 */
internal fun warpAffinesOf(transform: ModalTransformCapture, operator: MeshOperatorKind, parameters: TransformGestureParameters): Map<DeformerId, FloatArray> {
	val affines = LinkedHashMap<DeformerId, FloatArray>()

	for (entry in transform.entries) {
		val deformerId = entry.owner.deformerId ?: continue
		val group = entry.groups.firstOrNull() ?: continue
		affines[deformerId] = gestureAffine(operator, parameters, group)
	}

	return affines
}

/**
 * The drive, the drawables one after another and then the warps: what a confirm settles with and what a strip
 * rerun lands.
 *
 * @param ObjectDriveRequest request The request.
 * @return ObjectDriveResult The result.
 */
internal fun computeObjectDrive(request: ObjectDriveRequest): ObjectDriveResult = withWarps(request, computeMeshDrive(request.mesh))

/**
 * The drive with the drawables in parallel chunks on the caller's dispatcher; the same result as [computeObjectDrive].
 *
 * @param ObjectDriveRequest request The request.
 * @return ObjectDriveResult The result.
 */
internal suspend fun computeObjectDriveParallel(request: ObjectDriveRequest): ObjectDriveResult = withWarps(request, computeMeshDriveParallel(request.mesh))

/**
 * Moves each warp of [request] as a whole over its base model, beside the drawables' result.
 *
 * @param ObjectDriveRequest request The request.
 * @param MeshDriveResult mesh The drawables' result.
 * @return ObjectDriveResult The whole result.
 */
private fun withWarps(request: ObjectDriveRequest, mesh: MeshDriveResult): ObjectDriveResult {
	if (request.warpAffines.isEmpty()) {
		return ObjectDriveResult(mesh, emptyMap(), emptyMap())
	}

	val base = request.mesh.baseModel
	val landedWarps = LinkedHashMap<DeformerId, Deformer.Warp>(request.warpAffines.size)
	val previews = LinkedHashMap<DeformerId, WarpLatticeForm>(request.warpAffines.size)

	for ((warpId, affine) in request.warpAffines) {
		val landed = landedWarp(base, warpId, affine) ?: continue
		landedWarps[warpId] = landed
		warpPreviewOf(base, landed)?.let { preview -> previews[warpId] = preview }
	}

	return ObjectDriveResult(mesh, landedWarps, previews)
}