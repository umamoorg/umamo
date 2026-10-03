package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshRestPositions
import org.umamo.edit.MeshTopology
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorlds
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/**
 * One session mesh's live geometry at the neutral pose: its [DrawableWorldGeometry] (the rest arrays, the
 * posed rest shape, and its world projection, plus the deformer-chain mapping and the world->rest inverse),
 * along with its mesh and derived unique edges.  The Edit session spans several meshes, so the overlay
 * carries one of these per drawable.
 *
 * The three-space geometry is the SAME primitive the object gizmo and the Properties transform panel use, so
 * an Edit-mode drag inverts a transformed world shape back onto the rest arrays through the shared
 * [DrawableWorldGeometry.worldToRest] rather than an open-coded round trip.
 *
 * @property DrawableWorldGeometry worldGeometry The drawable's rest / displayed / world geometry and inverse.
 * @property DrawableMesh mesh The drawable's live mesh (positions, uvs, indices).
 * @property List<MeshElement.Edge> edges The mesh's unique edges, in first-encounter order.
 */
internal class EditMeshGeometry(
	val worldGeometry: DrawableWorldGeometry,
	val mesh: DrawableMesh,
	val edges: List<MeshElement.Edge>,
) {
	/** The drawable this geometry belongs to. */
	val drawableId: DrawableId get() = worldGeometry.drawableId

	/** The local-to-world deformer-chain projection. */
	val mapping: DrawableSpaceMapping get() = worldGeometry.mapping

	/** The local rest shape the movement transfer anchors on (the base + the neutral keyform blend). */
	val displayed: FloatArray get() = worldGeometry.displayed

	/** The displayed shape projected to world space. */
	val worldPosed: FloatArray get() = worldGeometry.world

	/** The geometry-source-agnostic view the shared element queries take. */
	val gizmo = GizmoMeshGeometry(drawableId, mesh.indices, edges, worldGeometry.world)

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
 * Each session mesh's geometry at the constant neutral pose Edit mode is pinned to: its rest shape
 * (displayed = base + the neutral keyform blend), its deformer-chain mapping, and its world projection.
 *
 * A drawable whose mapping cannot be built (a hidden ancestor) is skipped: it cannot be drawn, so it
 * cannot be edited - the same three-space primitive the object gizmo and Properties use.  An empty
 * result is therefore a real state, not a failure, and it is exactly the state the pointer-addressed
 * commands have to keep working in.  The capture is one batch, so the deformer chain bakes once per
 * commit rather than once per mesh.
 *
 * @param PuppetModel model The model to project.
 * @param List<DrawableId> drawableIds The session's mesh selection.
 * @return List The per-mesh geometry, skipping what cannot be projected.
 */
internal fun editMeshGeometries(model: PuppetModel, drawableIds: List<DrawableId>): List<EditMeshGeometry> {
	val drawableById = model.drawables.associateBy { drawable -> drawable.id }
	// Edit mode is pinned to the neutral pose, so the three-space geometry is captured at emptyMap().
	return captureDrawableWorlds(model, emptyMap(), drawableIds).mapNotNull { worldGeometry ->
		val mesh = drawableById[worldGeometry.drawableId]?.mesh ?: return@mapNotNull null
		EditMeshGeometry(
			worldGeometry = worldGeometry,
			mesh = mesh,
			edges = MeshTopology.uniqueEdges(mesh.indices),
		)
	}
}