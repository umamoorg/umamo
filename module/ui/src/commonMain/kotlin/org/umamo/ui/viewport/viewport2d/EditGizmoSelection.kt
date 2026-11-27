package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.ui.viewport.ViewportOverlayState
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.MeshPickController
import org.umamo.ui.viewport.gizmo.meshMarquee

/**
 * How this area's picks treat what faces away, by how its overlays draw it: front first where the area dims the back,
 * front only where it dims the back to nothing, and plain where it does not dim.  No area state (a standalone shell)
 * dims at the default opacity, as a fresh area does.
 *
 * @return BackFacingPick The area's rule.
 */
internal fun ViewportOverlayState?.backFacingPick(): BackFacingPick =
	when {
		this == null -> BackFacingPick.FrontFirst
		!effectiveDimBackFacing -> BackFacingPick.Plain
		backFacingOpacity <= 0f -> BackFacingPick.FrontOnly
		else -> BackFacingPick.FrontFirst
	}

/**
 * The marquee (box + circle) machinery over the session's meshes (see meshMarquee).  The overlay holds one
 * per area, so every callback reads [geometries] when it runs.  A circle stroke publishes what it has
 * painted as the session's mesh preview, which the renderer's mesh overlay shows until the release commits
 * it.
 *
 * @param EditorSession session The session owning the mesh selection and the armed tool.
 * @param State<List<EditOwnerGeometry>> geometries The session owners' live geometry.
 * @param Function backFacingPick How the area picks what faces away, read when a box lands or a brush stamps.
 * @return MarqueeSelectController<MeshSelection> The marquee.
 */
internal fun editMarquee(
	session: EditorSession,
	geometries: State<List<EditOwnerGeometry>>,
	backFacingPick: () -> BackFacingPick,
): MarqueeSelectController<MeshSelection> =
	meshMarquee(
		session = session,
		geometries = {
			val pick = backFacingPick()
			geometries.value.map { geometry -> geometry.gizmoFor(pick) }
		},
		previewStroke = { stroke -> session.setMeshPreviewSelection(stroke) },
	)

/**
 * The element pick and box select over the session's meshes (see MeshPickController), placing the
 * viewport's 2D cursor.  The overlay holds one per area, so the geometry is read when a press lands.
 *
 * @param EditorSession session The session owning the mesh selection and the cursor.
 * @param MarqueeSelectController<MeshSelection> marquee The area's marquee.
 * @param State<List<EditOwnerGeometry>> geometries The session owners' live geometry.
 * @param Function backFacingPick How the area picks what faces away, read when a press lands.
 * @return MeshPickController The controller.
 */
internal fun editMeshPick(
	session: EditorSession,
	marquee: MarqueeSelectController<MeshSelection>,
	geometries: State<List<EditOwnerGeometry>>,
	backFacingPick: () -> BackFacingPick,
): MeshPickController =
	MeshPickController(
		session = session,
		marquee = marquee,
		geometries = {
			val pick = backFacingPick()
			geometries.value.map { geometry -> geometry.gizmoFor(pick) }
		},
		placeCursor = session::setCursor2d,
	)