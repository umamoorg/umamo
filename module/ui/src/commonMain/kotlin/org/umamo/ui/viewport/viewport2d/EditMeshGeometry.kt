package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.umamo.edit.ElementTopology
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.edit.mesh.MeshRestPositions
import org.umamo.render.eval.ParentSpaceMapping
import org.umamo.runtime.model.DeformerId
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.ElementOwner
import org.umamo.runtime.model.PuppetModel
import org.umamo.runtime.model.WarpLatticeLayout
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.ElementEditStage
import org.umamo.ui.transform.LatticeWorldGeometry
import org.umamo.ui.transform.OwnerWorldGeometry
import org.umamo.ui.transform.captureEditModeMeshes
import org.umamo.ui.transform.captureLatticeWorld
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/**
 * One owner an Edit or Pose session shapes, as the 2D overlay picks and transforms it at the stage it edits at: a
 * drawable's mesh ([EditMeshGeometry]) at rest, or a warp's lattice ([EditLatticeGeometry]) at rest or at the pose.
 */
internal sealed interface EditOwnerGeometry {
	/** The owner: the drawable or the warp. */
	val owner: ElementOwner

	/** Its world capture, which a transform of it moves and inverts. */
	val worldGeometry: OwnerWorldGeometry

	/** Its vertices, edges, and faces: a mesh's triangles, or a lattice's points, grid lines, and cells. */
	val topology: ElementTopology

	/** Its vertices in world space at the stage's pose, interleaved x, y. */
	val worldPosed: FloatArray

	/** The geometry-source-agnostic view the shared element queries take. */
	val gizmo: GizmoMeshGeometry
}

/**
 * One session mesh's live geometry at the neutral pose: its [DrawableWorldGeometry] (the rest arrays, the
 * posed rest shape, and its world projection, plus the deformer-chain mapping and the world->rest inverse),
 * along with its mesh and its topology.  The Edit session spans several meshes, so the overlay
 * carries one of these per drawable.
 *
 * The geometry is the SAME primitive the object gizmo and the Properties transform panel use, so
 * an Edit-mode drag inverts a transformed world shape back onto the rest arrays through the shared
 * [DrawableWorldGeometry.worldToRest] rather than an open-coded round trip.
 *
 * @property DrawableWorldGeometry worldGeometry The drawable's rest / displayed / world geometry and inverse.
 * @property DrawableMesh mesh The drawable's live mesh (positions, localPositions, uvs, indices).
 */
internal class EditMeshGeometry(
	override val worldGeometry: DrawableWorldGeometry,
	val mesh: DrawableMesh,
) : EditOwnerGeometry {
	override val owner: ElementOwner = ElementOwner.Drawable(worldGeometry.drawableId)

	/** The mesh's vertices, edges, and faces; its edges are built on first use. */
	override val topology: ElementTopology = ElementTopology.ofMesh(mesh)

	/** The drawable this geometry belongs to. */
	val drawableId: DrawableId get() = worldGeometry.drawableId

	/** The local-to-world deformer-chain projection. */
	val mapping: ParentSpaceMapping get() = worldGeometry.mapping

	/** The local rest shape the movement transfer anchors on (the base + the neutral keyform blend). */
	val displayed: FloatArray get() = worldGeometry.displayed

	/** The displayed shape projected to world space. */
	override val worldPosed: FloatArray get() = worldGeometry.world

	override val gizmo = GizmoMeshGeometry(owner, topology, worldGeometry.world)

	/**
	 * Inverts a transformed WORLD shape back onto the rest arrays - the write-back a drag ends with.
	 *
	 * @param FloatArray transformedWorld The reshaped world positions.
	 * @param Set<Int> indices The vertices the transform touched.
	 * @return MeshRestPositions The new canvas mesh and base (fresh arrays).
	 */
	fun worldToRest(transformedWorld: FloatArray, indices: Set<Int>): MeshRestPositions = worldGeometry.worldToRest(transformedWorld, indices)
}

/**
 * One session lattice's geometry at its stage: the warp's lattice as that pose draws it, with its points, grid lines,
 * and cells.
 *
 * @property LatticeWorldGeometry worldGeometry The lattice's capture.
 */
internal class EditLatticeGeometry(
	override val worldGeometry: LatticeWorldGeometry,
) : EditOwnerGeometry {
	override val owner: ElementOwner get() = worldGeometry.owner

	/** The warp. */
	val deformerId: DeformerId get() = worldGeometry.deformerId

	override val topology: ElementTopology = ElementTopology.ofLattice(WarpLatticeLayout(worldGeometry.lattice.rows, worldGeometry.lattice.columns))

	override val worldPosed: FloatArray get() = worldGeometry.world

	override val gizmo = GizmoMeshGeometry(owner, topology, worldGeometry.world)
}

/**
 * Each session owner's geometry at [stage], in the session's order: a drawable's through [editMeshGeometries] (a mesh
 * session is always at rest), a warp's lattice as the stage's pose draws it.  An owner that cannot be drawn there (a
 * hidden ancestor, a lattice the pose leaves unkeyed) is skipped, so an empty result is a real state.
 *
 * @param PuppetModel model The model to project.
 * @param List<ElementOwner> owners The session's owners.
 * @param ElementEditStage stage Where the session edits.
 * @return List<EditOwnerGeometry> The per-owner geometry, skipping what cannot be projected.
 */
internal fun editOwnerGeometries(
	model: PuppetModel,
	owners: List<ElementOwner>,
	stage: ElementEditStage = ElementEditStage.Rest,
): List<EditOwnerGeometry> {
	val drawableIds = owners.mapNotNull { owner -> (owner as? ElementOwner.Drawable)?.id }
	val meshById = editMeshGeometries(model, drawableIds).associateBy { geometry -> geometry.owner }

	return owners.mapNotNull { owner ->
		when (owner) {
			is ElementOwner.Drawable -> meshById[owner]
			is ElementOwner.Deformer -> captureLatticeWorld(model, owner.id, stage)?.let { capture -> EditLatticeGeometry(capture) }
		}
	}
}

/**
 * Each session mesh's geometry at the constant neutral pose Edit mode is pinned to: its rest shape
 * (displayed = base + the neutral keyform blend), its deformer-chain mapping, and its world projection,
 * captured by [captureEditModeMeshes], with each mesh's topology.
 *
 * A drawable whose mapping cannot be built (a hidden ancestor) is skipped: it cannot be drawn, so it
 * cannot be edited - the same primitive the object gizmo and Properties use.  An empty
 * result is therefore a real state, not a failure, and it is exactly the state the pointer-addressed
 * commands have to keep working in.
 *
 * @param PuppetModel model The model to project.
 * @param List<DrawableId> drawableIds The session's mesh selection.
 * @return List The per-mesh geometry, skipping what cannot be projected.
 */
internal fun editMeshGeometries(model: PuppetModel, drawableIds: List<DrawableId>): List<EditMeshGeometry> =
	captureEditModeMeshes(model, drawableIds).map { captured ->
		EditMeshGeometry(
			worldGeometry = captured.geometry,
			mesh = captured.mesh,
		)
	}