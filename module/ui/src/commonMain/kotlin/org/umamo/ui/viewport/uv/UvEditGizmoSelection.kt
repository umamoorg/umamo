package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.runtime.model.DrawableId
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.MeshHighlightSets
import org.umamo.ui.viewport.gizmo.MeshPickController
import org.umamo.ui.viewport.gizmo.buildHighlightSets

/**
 * The element pick and box select over the shown meshes (see MeshPickController), the flow shared with the
 * 2D viewport, placing the UV cursor on Shift+RightClick (the viewport's 2D-cursor gesture, in texture
 * space).  The overlay holds one per area, so the geometry and the frame are read when a press lands.
 *
 * @param EditorSession session The session owning the mesh selection and the UV cursor.
 * @param MarqueeSelectController<MeshSelection> marquee The area's marquee.
 * @param State<List<GizmoMeshGeometry>> geometries The shown meshes' display geometry.
 * @param State<UvEditFrame> frame The shown surface's frame.
 * @return MeshPickController The controller.
 */
internal fun uvEditMeshPick(
	session: EditorSession,
	marquee: MarqueeSelectController<MeshSelection>,
	geometries: State<List<GizmoMeshGeometry>>,
	frame: State<UvEditFrame>,
): MeshPickController =
	MeshPickController(
		session = session,
		marquee = marquee,
		geometries = { geometries.value },
		placeCursor = { displayX, displayY -> placeUvCursor(session, frame.value, displayX, displayY) },
	)

/**
 * What the wireframe pass highlights per shown mesh and domain (Blender's derive-up / flush-down rules).
 *
 * @param MeshSelection selection The selection to show: the live circle stroke while one is in flight, else
 *   the committed selection.
 * @param List<GizmoMeshGeometry> geometries The shown meshes' display geometry.
 * @return Map<DrawableId, MeshHighlightSets> Each shown mesh's highlight sets.
 */
internal fun uvEditHighlights(selection: MeshSelection, geometries: List<GizmoMeshGeometry>): Map<DrawableId, MeshHighlightSets> =
	geometries.associate { geometry ->
		geometry.drawableId to
			buildHighlightSets(
				elements = selection.elementsOf(geometry.drawableId),
				active = selection.activeElement?.takeIf { activeElement -> activeElement.drawableId == geometry.drawableId }?.element,
				selectMode = selection.selectMode,
				triangleIndices = geometry.indices,
			)
	}