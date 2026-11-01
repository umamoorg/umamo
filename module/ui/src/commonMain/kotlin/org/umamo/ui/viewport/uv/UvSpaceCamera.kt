package org.umamo.ui.viewport.uv

import org.umamo.edit.EditorMode
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshTopology
import org.umamo.ui.viewport.PuppetViewportService
import org.umamo.ui.viewport.ServiceCameraController
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry

/**
 * The UV editor's camera controller for one UV-editor area.  Frame Selected frames the covered UV
 * bounds in the editor's display space: in Edit mode the covered vertices of the mesh selection, in
 * Object mode every vertex of the shown geometries (the selected drawables).  A one-texel floor keeps a
 * single-vertex frame from exploding the zoom to its clamp.
 *
 * @property PuppetViewportService service The render service holding this area's camera.
 * @property EditorSession session The session whose mode / mesh selection Frame Selected reads.
 * @property String areaId The UV editor area this controller drives.
 * @property Function geometries The display-space geometries Frame Selected frames, re-read on each
 *   call; the host's supplier narrows Object mode to the selected islands.
 */
internal class UvSpaceCamera(
	service: PuppetViewportService,
	session: EditorSession,
	areaId: String,
	private val geometries: () -> List<GizmoMeshGeometry>,
) : ServiceCameraController(service, session, areaId) {
	override fun frameSelected() {
		// Mirror the 2D viewport's mode split (ViewportSpaceCamera.frameSelected): Edit mode frames the
		// covered (selected) vertices; Object mode has no element selection, so it frames every vertex
		// of the supplied geometries - the host's supplier narrows Object mode to the SELECTED islands
		// (the shown list is every visible island on the surface).  Without this branch Object-mode Frame
		// Selected is a no-op, because meshSelection is empty there and coveredVertexIndices returns
		// nothing.
		val editMode = session.mode.value == EditorMode.Edit
		val selection = session.meshSelection.value
		var minX = Float.MAX_VALUE
		var minY = Float.MAX_VALUE
		var maxX = -Float.MAX_VALUE
		var maxY = -Float.MAX_VALUE

		for (geometry in geometries()) {
			val vertexIndices: Iterable<Int> =
				if (editMode) {
					MeshTopology.coveredVertexIndices(selection.elementsOf(geometry.owner), geometry.topology)
				} else {
					0 until geometry.positions.size / 2
				}

			for (vertexIndex in vertexIndices) {
				minX = minOf(minX, geometry.positions[vertexIndex * 2])
				maxX = maxOf(maxX, geometry.positions[vertexIndex * 2])
				minY = minOf(minY, geometry.positions[vertexIndex * 2 + 1])
				maxY = maxOf(maxY, geometry.positions[vertexIndex * 2 + 1])
			}
		}

		if (minX > maxX) {
			return
		}

		// A one-texel floor keeps a single-vertex frame from exploding the zoom to its clamp.
		service.fitWorldRect(areaId, minX, minY, maxOf(maxX, minX + 1f), maxOf(maxY, minY + 1f))
	}
}