package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.MeshPickController

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