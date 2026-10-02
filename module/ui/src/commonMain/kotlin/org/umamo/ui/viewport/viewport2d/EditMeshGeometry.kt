package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import org.umamo.edit.MeshBaseMove
import org.umamo.edit.MeshElement
import org.umamo.edit.MeshTopology
import org.umamo.render.eval.DrawableSpaceMapping
import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel
import org.umamo.ui.transform.DrawableWorldGeometry
import org.umamo.ui.transform.captureDrawableWorld
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/**
 * One session mesh's live geometry at the neutral pose: its three-space [DrawableWorldGeometry] (base, the
 * posed rest shape, and its world projection, plus the deformer-chain mapping and the world->base inverse),
 * along with its mesh and derived unique edges.  The Edit session spans several meshes, so the overlay
 * carries one of these per drawable.
 *
 * The three-space geometry is the SAME primitive the object gizmo and the Properties transform panel use, so
 * an Edit-mode drag inverts a transformed world shape back onto the base mesh through the shared
 * [DrawableWorldGeometry.worldToBase] rather than an open-coded round trip.
 *
 * @property DrawableWorldGeometry worldGeometry The drawable's base / displayed / world geometry and inverse.
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

	/** The local rest shape the movement transfer anchors on (base + the neutral keyform blend). */
	val displayed: FloatArray get() = worldGeometry.displayed

	/** The displayed shape projected to world space. */
	val worldPosed: FloatArray get() = worldGeometry.world

	/** The geometry-source-agnostic view the shared element queries and wireframe draw take. */
	val gizmo = GizmoMeshGeometry(drawableId, mesh.indices, edges, worldGeometry.world)

	/**
	 * Inverts a transformed WORLD shape back onto the base mesh - the write-back a drag ends with.
	 *
	 * @param FloatArray transformedWorld The reshaped world positions.
	 * @param Set<Int> indices The vertices the transform touched.
	 * @return FloatArray The new base positions (a fresh array).
	 */
	fun worldToBase(transformedWorld: FloatArray, indices: Set<Int>): FloatArray = worldGeometry.worldToBase(transformedWorld, indices)

	/**
	 * Inverts a transformed world shape onto the base mesh with the keyform movement kept beside it
	 * ([DrawableWorldGeometry.worldToBaseMove]).
	 *
	 * @param FloatArray transformedWorld The transformed world positions.
	 * @param Set<Int>   indices          The vertices the transform touched.
	 * @return MeshBaseMove The new base positions and the keyform movement.
	 */
	fun worldToBaseMove(transformedWorld: FloatArray, indices: Set<Int>): MeshBaseMove = worldGeometry.worldToBaseMove(transformedWorld, indices)
}

/**
 * Each session mesh's geometry at the constant neutral pose Edit mode is pinned to: its rest shape
 * (displayed = base + the neutral keyform blend), its deformer-chain mapping, and its world projection.
 *
 * A drawable whose mapping cannot be built (a hidden ancestor) is skipped: it cannot be drawn, so it
 * cannot be edited - the same three-space primitive the object gizmo and Properties use.  An empty
 * result is therefore a real state, not a failure, and it is exactly the state the pointer-addressed
 * commands have to keep working in.
 *
 * @param PuppetModel model The model to project.
 * @param List<DrawableId> drawableIds The session's mesh selection.
 * @return List The per-mesh geometry, skipping what cannot be projected.
 */
internal fun editMeshGeometries(model: PuppetModel, drawableIds: List<DrawableId>): List<EditMeshGeometry> =
	drawableIds.mapNotNull { drawableId ->
		val mesh = model.drawables.firstOrNull { it.id == drawableId }?.mesh ?: return@mapNotNull null
		// Edit mode is pinned to the neutral pose, so the three-space geometry is captured at emptyMap().
		val worldGeometry = captureDrawableWorld(model, emptyMap(), drawableId) ?: return@mapNotNull null
		EditMeshGeometry(
			worldGeometry = worldGeometry,
			mesh = mesh,
			edges = MeshTopology.uniqueEdges(mesh.indices),
		)
	}