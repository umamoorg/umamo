package org.umamo.ui.viewport.viewport2d

import org.umamo.edit.EditorSession
import org.umamo.edit.MeshChange
import org.umamo.edit.MeshOperatorKind
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.edit.mesh.commitMeshPositions
import org.umamo.edit.mesh.withMeshPositions
import org.umamo.edit.transform.ModalTransformCapture
import org.umamo.edit.transform.ProportionalInfluence
import org.umamo.edit.transform.TransformGestureParameters
import org.umamo.edit.transform.TransformPivotGroup
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeForm
import org.umamo.runtime.model.deformerId
import org.umamo.runtime.model.drawableId
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.LatticeLanding
import org.umamo.ui.transform.LatticeWorldGeometry
import org.umamo.ui.transform.OwnerWorldGeometry
import org.umamo.ui.transform.commitLatticeLanding
import org.umamo.ui.transform.latticeReshapeOf
import org.umamo.ui.transform.posedLatticeForms
import org.umamo.ui.viewport.gizmo.DRIVE_MIN_CHUNK_WEIGHT
import org.umamo.ui.viewport.gizmo.applyOperator
import org.umamo.ui.viewport.gizmo.mapInBalancedChunks
import org.umamo.ui.viewport.gizmo.slideVertexByFactor

/*
 * The 2D modal drive's compute, shared by the Edit, Pose, and Object transforms and the operation settings strip's
 * replays: each moving mesh's frozen world shape through the operator (or the Vertex Slide), back through its
 * deformer chain onto its rest arrays, then one batch fold into the model.  A moving lattice's points come out in
 * world space as they are.  At rest a lattice drag moves the cage and never the art, so nothing more happens per
 * frame and only the landing lowers the points onto the model (latticeReshapeOf); at the pose the art follows, so
 * each frame lowers them to the pending forms the renderer previews (posedLatticeForms).  Pure over its request, so
 * the drive worker can run it off the UI thread, and the per-owner step in parallel.
 */

/**
 * One moving owner's share of a drive, everything the compute reads, taken on the UI thread: the frozen
 * world shape and pivot groups, the proportional halo and moved set AS THEY WERE when the request was
 * made (the UI thread reassigns the capture's own on a radius or falloff change), and the frozen geometry
 * a mesh's inverse runs through.
 *
 * @property ElementOwner owner The drawable or the warp.
 * @property FloatArray positions The frozen world positions the operator transforms (never mutated).
 * @property List<TransformPivotGroup> groups The pivot groups its covered vertices turn about.
 * @property Map<Int, ProportionalInfluence> influence The proportional halo; empty when off.
 * @property Set<Int> movedIndices Every vertex the drive moves.
 * @property OwnerWorldGeometry geometry The frozen geometry: a mesh's, which the world shape inverts through, or a
 *   lattice's.
 */
internal class MeshDriveJob(
	val owner: ElementOwner,
	val positions: FloatArray,
	val groups: List<TransformPivotGroup>,
	val influence: Map<Int, ProportionalInfluence>,
	val movedIndices: Set<Int>,
	val geometry: OwnerWorldGeometry,
) {
	/** The drawable a mesh job moves, or null for a lattice. */
	val drawableId: DrawableId? get() = owner.drawableId
}

/**
 * The Vertex Slide's share of a drive: which vertex slides, and toward which edge, as the request's pointer
 * landed it.
 *
 * @property ElementOwner owner The mesh or lattice the vertex lives in.
 * @property Int activeVertex The sliding vertex.
 * @property SlideLanding landing The edge and factor.
 */
internal class SlideMove(
	val owner: ElementOwner,
	val activeVertex: Int,
	val landing: SlideLanding,
)

/**
 * One drive's request, resolved on the UI thread: the operator and the parameters its pointer
 * frame resolved to (resolving them advances the rotation tracker, which must see every event), the moving
 * meshes and lattices, the slide when one lands, the model the result folds onto, and the stage the lattices land
 * at.
 *
 * @property MeshOperatorKind operator The operator.
 * @property TransformGestureParameters parameters The parameters every mesh applies.
 * @property List<MeshDriveJob> jobs The moving meshes and lattices, in capture order.
 * @property SlideMove? slide The Vertex Slide's landing, or null (for a slide, the pointer landed on no edge).
 * @property PuppetModel baseModel The model the new rest shapes fold onto.
 * @property ElementEditStage stage Where the moving lattices were captured, and so how they land.
 */
internal class MeshDriveRequest(
	val operator: MeshOperatorKind,
	val parameters: TransformGestureParameters,
	val jobs: List<MeshDriveJob>,
	val slide: SlideMove?,
	val baseModel: PuppetModel,
	val stage: ElementEditStage = ElementEditStage.Rest,
)

/**
 * One drive's result.
 *
 * @property Map<DrawableId, MeshRestPositions> preview Each moving mesh's new rest shape, in capture order.
 * @property Map<DeformerId, FloatArray> latticeWorld Each moving lattice's new points in world space, interleaved
 *   x, z, in capture order.
 * @property PuppetModel folded The request's base model with the meshes folded in; the lattices are not - they land
 *   through [latticeLanding].
 * @property Map<DeformerId, Set<Int>> latticeMoved Each moving lattice's moved points.
 * @property Map<DeformerId, WarpLatticeForm> latticeForms At the pose, each moving lattice lowered to the pending form
 *   the renderer previews; empty at rest.
 */
internal class MeshDriveResult(
	val preview: Map<DrawableId, MeshRestPositions>,
	val latticeWorld: Map<DeformerId, FloatArray>,
	val folded: PuppetModel,
	val latticeMoved: Map<DeformerId, Set<Int>> = emptyMap(),
	val latticeForms: Map<DeformerId, WarpLatticeForm> = emptyMap(),
) {
	/**
	 * This result with its meshes folded onto [model] instead of the request's base: what a confirm lands over the
	 * model as it is when the gesture ends.
	 *
	 * @param PuppetModel model The model to fold onto.
	 * @return MeshDriveResult The refolded result.
	 */
	fun foldedOnto(model: PuppetModel): MeshDriveResult = MeshDriveResult(preview, latticeWorld, model.withMeshPositions(preview), latticeMoved, latticeForms)
}

/**
 * Commits a drive as one undo step: its lattices landed at [stage] when it moved any ([latticeLanding]), else its
 * meshes' new rest shapes, naming every vertex the drive moved.  The commit twin of the strip's amend, so a confirm,
 * a typed median, and a snap land a drive alike.
 *
 * @param MeshDriveResult result The drive.
 * @param ElementEditStage stage Where the moved geometry was captured.
 * @param MeshOperatorKind kind The operator the drive ran as.
 * @param Map<DrawableId, List<Int>> movedIndicesByDrawable Each moving mesh's moved vertices - the moved set, not just
 *   the covered one, since proportional editing moves weighted unselected vertices too.
 * @return Boolean True when a step was recorded; a drive that lands where it started records none, and so has no step
 *   for the operation strip to amend.
 */
internal fun EditorSession.commitDrive(
	result: MeshDriveResult,
	stage: ElementEditStage,
	kind: MeshOperatorKind,
	movedIndicesByDrawable: Map<DrawableId, List<Int>>,
): Boolean {
	result.latticeLanding(stage)?.let { landing -> return commitLatticeLanding(landing, kind) }

	val modelBefore = model.value

	if (result.preview.isNotEmpty()) {
		commitMeshPositions(MeshChange.TransformVertices(movedIndicesByDrawable, kind), result.preview)
	}

	return model.value !== modelBefore
}

/**
 * One job's share of a drive's result: a mesh's new rest shape, or a lattice's moved world points.
 */
internal sealed interface MeshJobOutcome {
	/**
	 * A mesh's new rest shape.
	 *
	 * @property DrawableId drawableId The drawable.
	 * @property MeshRestPositions rest Its new canvas mesh and base.
	 */
	class Rest(val drawableId: DrawableId, val rest: MeshRestPositions) : MeshJobOutcome

	/**
	 * A lattice's moved points.
	 *
	 * @property DeformerId deformerId The warp.
	 * @property FloatArray world Its points in world space, interleaved x, z.
	 */
	class Lattice(val deformerId: DeformerId, val world: FloatArray) : MeshJobOutcome
}

/**
 * The drive's jobs over a capture: one per entry with frozen geometry, its halo and moved set read now.
 *
 * @param ModalTransformCapture transform The capture.
 * @param Map<ElementOwner, OwnerWorldGeometry> geometryByOwner Each moving owner's frozen geometry.
 * @param Boolean wholeMeshes True for an Object-mode capture: whole drawables, no halo, every covered vertex.
 * @return List<MeshDriveJob> The jobs, in capture order.
 */
internal fun meshDriveJobs(
	transform: ModalTransformCapture,
	geometryByOwner: Map<ElementOwner, OwnerWorldGeometry>,
	wholeMeshes: Boolean,
): List<MeshDriveJob> =
	transform.entries.mapNotNull { entry ->
		val geometry = geometryByOwner[entry.owner] ?: return@mapNotNull null

		if (wholeMeshes) {
			MeshDriveJob(entry.owner, entry.positions, entry.groups, emptyMap(), entry.coveredIndices, geometry)
		} else {
			MeshDriveJob(entry.owner, entry.positions, entry.groups, entry.influence, entry.movedIndices, geometry)
		}
	}

/**
 * One owner's outcome under a request: its frozen world shape through the operator (a Vertex Slide moves only the
 * slide owner's vertex, and nothing when the pointer landed on no edge), then for a mesh inverted back onto the rest
 * arrays over its moved vertices, and for a lattice left in world space.  Pure; the parallel compute runs it per
 * owner on any thread.
 *
 * @param MeshDriveJob job The owner.
 * @param MeshDriveRequest request The request.
 * @return MeshJobOutcome The mesh's new rest shape, or the lattice's moved points.
 */
internal fun meshJobOutcome(job: MeshDriveJob, request: MeshDriveRequest): MeshJobOutcome {
	val transformedWorld =
		if (request.operator == MeshOperatorKind.VertexSlide) {
			val slide = request.slide

			if (slide != null && slide.owner == job.owner) {
				slideVertexByFactor(job.positions, slide.activeVertex, slide.landing.neighborIndex, slide.landing.factor)
			} else {
				job.positions
			}
		} else {
			applyOperator(request.operator, job.positions, job.groups, request.parameters, job.influence)
		}

	return job.geometry.outcomeOf(transformedWorld, job.movedIndices)
}

/**
 * What a moved world shape of this owner comes to: a mesh's shape inverted back onto its rest arrays over its moved
 * vertices, a lattice's points left in world space for its landing.  The one switch over the owner kinds a drive and
 * a snap read.
 *
 * @param FloatArray transformedWorld The moved world shape.
 * @param Set<Int> moved The vertices that moved.
 * @return MeshJobOutcome The outcome.
 */
internal fun OwnerWorldGeometry.outcomeOf(transformedWorld: FloatArray, moved: Set<Int>): MeshJobOutcome =
	when (this) {
		is DrawableWorldGeometry -> MeshJobOutcome.Rest(drawableId, worldToRest(transformedWorld, moved))
		is LatticeWorldGeometry -> MeshJobOutcome.Lattice(deformerId, transformedWorld)
	}

/**
 * The drive, one mesh after another: what a confirm settles with, and what a transform with no worker runs.
 *
 * @param MeshDriveRequest request The request.
 * @return MeshDriveResult The result.
 */
internal fun computeMeshDrive(request: MeshDriveRequest): MeshDriveResult = foldMeshDrive(request, request.jobs.map { job -> meshJobOutcome(job, request) })

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
 * Gathers each job's outcome and folds them onto the request's model ([foldDrive]).
 *
 * @param MeshDriveRequest request The request.
 * @param List<MeshJobOutcome> outcomes Each job's outcome, in job order.
 * @return MeshDriveResult The result.
 */
private fun foldMeshDrive(request: MeshDriveRequest, outcomes: List<MeshJobOutcome>): MeshDriveResult =
	foldDrive(request.baseModel, outcomes, request.jobs.mapNotNull { job -> job.owner.deformerId?.let { deformerId -> deformerId to job.movedIndices } }.toMap(), request.stage)

/**
 * Folds a drive's outcomes onto [baseModel] in one pass: the meshes' new rest shapes into the model, the lattices'
 * moved points kept in world space - and at the pose lowered to their pending forms too, so the frame can preview
 * them.  What a drive and a snap both land through.
 *
 * @param PuppetModel baseModel The model the new rest shapes fold onto.
 * @param List<MeshJobOutcome> outcomes Each moved owner's outcome, in order.
 * @param Map<DeformerId, Set<Int>> latticeMoved Each moved lattice's moved points.
 * @param ElementEditStage stage Where the moved geometry was captured.
 * @return MeshDriveResult The result.
 */
internal fun foldDrive(
	baseModel: PuppetModel,
	outcomes: List<MeshJobOutcome>,
	latticeMoved: Map<DeformerId, Set<Int>>,
	stage: ElementEditStage,
): MeshDriveResult {
	val preview = LinkedHashMap<DrawableId, MeshRestPositions>(outcomes.size)
	val latticeWorld = LinkedHashMap<DeformerId, FloatArray>()

	for (outcome in outcomes) {
		when (outcome) {
			is MeshJobOutcome.Rest -> preview[outcome.drawableId] = outcome.rest
			is MeshJobOutcome.Lattice -> latticeWorld[outcome.deformerId] = outcome.world
		}
	}

	val latticeForms =
		when (stage) {
			ElementEditStage.Rest -> emptyMap()
			is ElementEditStage.Posed -> posedLatticeForms(baseModel, stage, latticeWorld, latticeMoved)
		}

	return MeshDriveResult(preview, latticeWorld, baseModel.withMeshPositions(preview), latticeMoved, latticeForms)
}

/**
 * Where a drive's lattices land at [stage], or null when it moved none: at rest, a reshape of every form of each warp
 * over the folded meshes with its children kept in place ([latticeReshapeOf]); at the pose, the pending forms the drive
 * lowered them to ([foldDrive]).  What a confirm commits and an operation strip rerun amends with, so the two cannot
 * drift apart.
 *
 * @param ElementEditStage stage Where the lattices were captured.
 * @return LatticeLanding? The landing, or null when no lattice moved.
 */
internal fun MeshDriveResult.latticeLanding(stage: ElementEditStage): LatticeLanding? {
	if (latticeWorld.isEmpty()) {
		return null
	}

	return when (stage) {
		ElementEditStage.Rest -> LatticeLanding.Reshape(latticeWorld.keys.toList(), latticeReshapeOf(folded, latticeWorld))
		is ElementEditStage.Posed -> LatticeLanding.Posed(latticeForms)
	}
}