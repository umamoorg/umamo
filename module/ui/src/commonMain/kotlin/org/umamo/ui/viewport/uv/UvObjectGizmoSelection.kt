package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.render.ViewportCamera
import org.umamo.render.pick.PickCandidate
import org.umamo.ui.viewport.gizmo.GizmoMeshGeometry
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ObjectPickController
import org.umamo.ui.viewport.gizmo.objectMarquee
import org.umamo.ui.viewport.gizmo.resolveObjectBoxSelection
import org.umamo.ui.viewport.gizmo.screenToWorld
import org.umamo.ui.viewport.gizmo.selectableDrawableTargets

/**
 * The UV Object overlay's marquee over whole islands: a box encloses every island with a vertex inside it
 * (see uvIslandsInBox), tested against the shown geometry as the box lands.  The circle callbacks are
 * dormant - a select tool cannot arm over a UV area in Object mode (CommandRouting.selectToolArea keeps
 * B / C Edit-only there) - so they stay sanely wired only so an unforeseen arming degrades to a no-op
 * stroke.  The overlay holds one per area.
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
 * The idle click-pick / un-armed box flow bound to the island domain (see ObjectPickController): the pick
 * seams unproject the click into display space and run the CPU island picker, and Shift+RightClick places
 * the UV cursor through the shown surface's frame.  No onBoxBegin - there is no centroid cache to snapshot,
 * the box tests the live display geometry directly.  The overlay holds one per area, so the picker, the
 * frame, and the camera are read when a click lands.
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
	onOverlapRequest: (Offset, List<PickCandidate>) -> Unit,
): ObjectPickController =
	ObjectPickController(
		session = session,
		marquee = marquee,
		pickTopmost = { position ->
			val (displayX, displayY) = screenToWorld(position.x, position.y, camera.value, size.value)
			islandPick.value.topmostAt(displayX, displayY)
		},
		pickStack = { position ->
			val (displayX, displayY) = screenToWorld(position.x, position.y, camera.value, size.value)
			islandPick.value.stackAt(displayX, displayY)
		},
		onOverlapRequest = onOverlapRequest,
		placeCursor = { displayX, displayY -> placeUvCursor(session, frame.value, displayX, displayY) },
	)