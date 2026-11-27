package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.edit.SelectionTarget
import org.umamo.edit.transform.UvEditFrame
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.ObjectPickCandidate
import org.umamo.ui.viewport.asObjectPickCandidates
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ObjectPickController
import org.umamo.ui.viewport.gizmo.objectMarquee
import org.umamo.ui.viewport.gizmo.resolveObjectBoxSelection
import org.umamo.ui.viewport.gizmo.screenToWorld
import org.umamo.ui.viewport.gizmo.selectableDrawableTargets

/**
 * The UV Object overlay's marquee over whole islands (see uvIslandsInBox).  The circle stamp is a no-op on
 * purpose: a select tool cannot arm over a UV area in Object mode (CommandRouting.selectToolArea keeps
 * B / C Edit-only there), so an unforeseen arming degrades to an empty stroke.
 *
 * @param EditorSession session The session owning the object selection.
 * @param State<List<GizmoMeshGeometry>> geometries The shown islands' display geometry.
 * @return MarqueeSelectController<Selection> The marquee.
 */
internal fun uvObjectMarquee(session: EditorSession, geometries: State<List<GizmoMeshGeometry>>): MarqueeSelectController<Selection> =
	objectMarquee(
		session = session,
		stampStroke = { working, _, _, _, _, _ -> working },
		applyBox = { start, end, additive, boxCamera, boxSize ->
			val enclosed = selectableDrawableTargets(uvIslandsInBox(geometries.value, start, end, boxCamera, boxSize), session.model.value)
			session.setSelection(resolveObjectBoxSelection(session.selection.value, enclosed, additive))
		},
	)

/**
 * The idle click-pick / un-armed box flow bound to the island domain (see ObjectPickController).  It needs
 * no onBoxBegin, since the box tests the live display geometry directly rather than a snapshot.
 *
 * @param EditorSession session The session owning the object selection and the UV cursor.
 * @param MarqueeSelectController<Selection> marquee The area's marquee.
 * @param State<UvIslandPickController> islandPick The shown surface's island picker.
 * @param State<UvEditFrame> frame The shown surface's frame.
 * @param State<ViewportCamera> camera The frame camera.
 * @param State<IntSize> size The area size in pixels.
 * @param Function onOverlapRequest Opens the host's overlap picker for an Alt click with 2+ candidates.
 * @return ObjectPickController The controller.
 */
internal fun uvObjectPick(
	session: EditorSession,
	marquee: MarqueeSelectController<Selection>,
	islandPick: State<UvIslandPickController>,
	frame: State<UvEditFrame>,
	camera: State<ViewportCamera>,
	size: State<IntSize>,
	onOverlapRequest: (Offset, List<ObjectPickCandidate>) -> Unit,
): ObjectPickController =
	ObjectPickController(
		session = session,
		marquee = marquee,
		pickTopmost = { position ->
			val (displayX, displayY) = screenToWorld(position.x, position.y, camera.value, size.value)
			islandPick.value.topmostAt(displayX, displayY)?.let { drawableId -> SelectionTarget.Drawable(drawableId) }
		},
		pickStack = { position ->
			val (displayX, displayY) = screenToWorld(position.x, position.y, camera.value, size.value)
			islandPick.value.stackAt(displayX, displayY).asObjectPickCandidates()
		},
		onOverlapRequest = onOverlapRequest,
		placeCursor = { displayX, displayY -> placeUvCursor(session, frame.value, displayX, displayY) },
	)