package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.MeshSelectionOps
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.MeshPickController
import org.umamo.ui.viewport.gizmo.circleSelection
import org.umamo.ui.viewport.gizmo.elementsInBox

/**
 * The marquee (box + circle) machinery over mesh elements: the stroke / rubber-band state and event
 * rules are shared (MarqueeSelectController); these callbacks bind them to the element domain.  The
 * overlay holds one per area, so every callback reads the session and [geometries] when it runs.  A
 * circle stroke publishes what it has painted as the session's mesh preview, which the renderer's mesh
 * overlay shows until the release commits it.
 *
 * @param EditorSession session The session owning the mesh selection and the armed tool.
 * @param State<List<EditMeshGeometry>> geometries The session meshes' live geometry.
 * @return MarqueeSelectController<MeshSelection> The marquee.
 */
internal fun editMarquee(session: EditorSession, geometries: State<List<EditMeshGeometry>>): MarqueeSelectController<MeshSelection> =
	MarqueeSelectController(
		seedStroke = { session.meshSelection.value },
		stampStroke = { working, erasing, center, radiusPx, stampCamera, stampSize ->
			circleSelection(working, erasing, center, radiusPx, geometries.value.map { it.gizmo }, stampCamera, stampSize)
		},
		commitStroke = { stroke -> session.setMeshSelection(stroke) },
		previewStroke = { stroke -> session.setMeshPreviewSelection(stroke) },
		applyBox = { start, end, additive, boxCamera, boxSize ->
			val selection = session.meshSelection.value
			val insideByDrawable =
				geometries.value.associate { geometry ->
					geometry.drawableId to elementsInBox(selection.selectMode, geometry.gizmo, start, end, boxCamera, boxSize)
				}
			session.setMeshSelection(MeshSelectionOps.box(selection, insideByDrawable, additive = additive))
		},
		setCircleRadius = { radiusPx -> session.setCircleRadius(radiusPx) },
		clearTool = { session.clearSelectTool() },
		setGestureActive = { active -> session.setViewportGestureActive(active) },
	)

/**
 * The element pick and box select over the session's meshes (see MeshPickController), placing the
 * viewport's 2D cursor.  The overlay holds one per area, so the geometry is read when a press lands.
 *
 * @param EditorSession session The session owning the mesh selection and the cursor.
 * @param MarqueeSelectController<MeshSelection> marquee The area's marquee.
 * @param State<List<EditMeshGeometry>> geometries The session meshes' live geometry.
 * @return MeshPickController The controller.
 */
internal fun editMeshPick(
	session: EditorSession,
	marquee: MarqueeSelectController<MeshSelection>,
	geometries: State<List<EditMeshGeometry>>,
): MeshPickController =
	MeshPickController(
		session = session,
		marquee = marquee,
		geometries = { geometries.value.map { it.gizmo } },
		placeCursor = session::setCursor2d,
	)