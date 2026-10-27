package org.umamo.ui.transform

import org.umamo.runtime.model.DrawableId
import org.umamo.runtime.model.DrawableMesh
import org.umamo.runtime.model.PuppetModel

/**
 * One mesh in the Edit-mode session as captured at the neutral pose Edit mode is pinned to: its world
 * geometry and the mesh that geometry was captured from.
 *
 * @property DrawableWorldGeometry geometry The mesh's rest arrays, displayed shape, world projection, and mapping.
 * @property DrawableMesh mesh The drawable's live mesh (positions, localPositions, uvs, indices).
 */
internal class EditModeMesh(val geometry: DrawableWorldGeometry, val mesh: DrawableMesh)

/**
 * Captures the meshes of an Edit-mode session at the constant neutral pose Edit mode is pinned to, so every
 * Edit-mode reader (the gizmo overlay, Shift+S, the sidebar's Vertex / Median panel) measures the same
 * shapes.  The capture is one batch, so the deformer chain bakes once rather than once per mesh, and a mesh
 * whose mapping cannot be built (a hidden ancestor) is skipped: it cannot be drawn, so it cannot be edited.
 *
 * @param PuppetModel model The committed model.
 * @param List<DrawableId> drawableIds The session's meshes.
 * @return List<EditModeMesh> The captured meshes in request order, skipping what cannot be projected.
 */
internal fun captureEditModeMeshes(model: PuppetModel, drawableIds: List<DrawableId>): List<EditModeMesh> {
	val drawableById = model.drawables.associateBy { drawable -> drawable.id }

	// Edit mode is pinned to the neutral pose, so the geometry is captured at emptyMap().
	return captureDrawableWorlds(model, emptyMap(), drawableIds).mapNotNull { geometry ->
		drawableById[geometry.drawableId]?.mesh?.let { mesh -> EditModeMesh(geometry, mesh) }
	}
}