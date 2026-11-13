package org.umamo.ui.viewport.viewport2d

import org.umamo.ui.transform.MeshDriveRequest
import org.umamo.ui.transform.MeshDriveResult
import org.umamo.ui.transform.ObjectDriveRequest
import org.umamo.ui.transform.ObjectDriveResult
import org.umamo.ui.transform.computeMeshDrive
import org.umamo.ui.transform.computeObjectDrive
import org.umamo.ui.transform.foldMeshDrive
import org.umamo.ui.transform.meshJobOutcome
import org.umamo.ui.transform.withWarps
import org.umamo.ui.viewport.gizmo.DRIVE_MIN_CHUNK_WEIGHT
import org.umamo.ui.viewport.gizmo.mapInBalancedChunks

/*
 * The 2D modal drives with their per-owner step in parallel, as the drive worker runs them off the UI thread.  The
 * compute is the sequential drives' own (MeshDrive.kt and ObjectDrive.kt in org.umamo.ui.transform); only the
 * chunking is added here, because the chunking helper belongs to the gizmo kit, which that package does not see.
 */

/**
 * The drive with its meshes in parallel chunks on the caller's dispatcher, weighted by how many vertices
 * each moves; the same result as [computeMeshDrive], element for element.
 *
 * @param MeshDriveRequest request The request.
 * @param Int minChunkWeight The least weight a chunk carries (tests lower it to force chunking).
 * @return MeshDriveResult The result.
 */
internal suspend fun computeMeshDriveParallel(request: MeshDriveRequest, minChunkWeight: Int = DRIVE_MIN_CHUNK_WEIGHT): MeshDriveResult {
	val outcomes = mapInBalancedChunks(request.jobs, { job -> job.movedIndices.size + 1 }, minChunkWeight) { job -> meshJobOutcome(job, request) }

	return foldMeshDrive(request, outcomes)
}

/**
 * The drive with the drawables in parallel chunks on the caller's dispatcher; the same result as [computeObjectDrive].
 *
 * @param ObjectDriveRequest request The request.
 * @return ObjectDriveResult The result.
 */
internal suspend fun computeObjectDriveParallel(request: ObjectDriveRequest): ObjectDriveResult = withWarps(request, computeMeshDriveParallel(request.mesh))