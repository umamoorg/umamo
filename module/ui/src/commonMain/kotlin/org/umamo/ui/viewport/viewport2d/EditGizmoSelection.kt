package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.edit.transform.BrushStroke
import org.umamo.ui.viewport.LiveMeshPreview
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.MeshPickController
import org.umamo.ui.viewport.gizmo.brushMarquee
import org.umamo.ui.viewport.gizmo.meshMarquee

/**
 * The marquee (box + circle) machinery over the session's meshes (see meshMarquee).  The overlay holds one
 * per area, so every callback reads [geometries] when it runs.  A circle stroke publishes what it has
 * painted as the session's mesh preview, which the renderer's mesh overlay shows until the release commits
 * it.
 *
 * @param EditorSession session The session owning the mesh selection and the armed tool.
 * @param State<List<EditOwnerGeometry>> geometries The session owners' live geometry.
 * @return MarqueeSelectController<MeshSelection> The marquee.
 */
internal fun editMarquee(session: EditorSession, geometries: State<List<EditOwnerGeometry>>): MarqueeSelectController<MeshSelection> =
	meshMarquee(
		session = session,
		geometries = { geometries.value.map { it.gizmo } },
		previewStroke = { stroke -> session.setMeshPreviewSelection(stroke) },
	)

/**
 * The element pick and box select over the session's meshes (see MeshPickController), placing the
 * viewport's 2D cursor.  The overlay holds one per area, so the geometry is read when a press lands.
 *
 * @param EditorSession session The session owning the mesh selection and the cursor.
 * @param MarqueeSelectController<MeshSelection> marquee The area's marquee.
 * @param State<List<EditOwnerGeometry>> geometries The session owners' live geometry.
 * @return MeshPickController The controller.
 */
internal fun editMeshPick(
	session: EditorSession,
	marquee: MarqueeSelectController<MeshSelection>,
	geometries: State<List<EditOwnerGeometry>>,
): MeshPickController =
	MeshPickController(
		session = session,
		marquee = marquee,
		geometries = { geometries.value.map { it.gizmo } },
		placeCursor = session::setCursor2d,
	)

/**
 * The deform brush's machinery over the session's warps (see brushMarquee), publishing what the stroke
 * would give the lattices into the window's live preview, which the cage follows until the release
 * commits it.
 *
 * @param EditorSession session The session owning the brush latch and the model.
 * @param LiveMeshPreview? livePreview The window's live mesh preview, or null outside a shell; a brush
 *   built without one keeps a private slot nothing reads.
 * @return MarqueeSelectController The brush's controller.
 */
internal fun editBrushMarquee(session: EditorSession, livePreview: LiveMeshPreview?): MarqueeSelectController<BrushStroke> {
	val preview = livePreview ?: LiveMeshPreview()

	return brushMarquee(session) { world ->
		if (world == null) {
			preview.clearLattices()
		} else {
			preview.showLattices(world, emptyMap())
		}
	}
}