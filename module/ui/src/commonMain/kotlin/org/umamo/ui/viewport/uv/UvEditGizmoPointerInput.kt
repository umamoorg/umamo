package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.MeshSelection
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.MeshPickController

/**
 * The UV Edit overlay's pointer loop.  Every event records the pointer, then goes to exactly one of three
 * branches: the modal transform while a UV operator is latched here, the circle brush while it is armed
 * here, and otherwise the element pick and the box select, armed or not.  While another area owns a gesture
 * (or a viewport-owned operator runs, which can never belong to a UV area), or Zoom Region is armed here,
 * the loop takes nothing.  Whatever takes the area over mid-drag - another area, a transform, the circle
 * tool, Zoom Region - abandons an in-flight box first.
 *
 * It runs for the life of its pointerInput, keyed on the area, and keeps the arguments it started with:
 * each is fixed for the area's life or a State holder read per event.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches and the selection.
 * @param UvEditModalTransform modalTransform The area's modal transform (its gesture state and commit side).
 * @param MarqueeSelectController<MeshSelection> marquee The area's box / circle machinery.
 * @param MeshPickController meshPick The area's element pick and box select.
 * @param State<ViewportCamera> liveCamera The area camera.
 * @param State<IntSize> liveSize The area size in pixels.
 */
internal suspend fun PointerInputScope.uvEditGizmoPointerLoop(
	areaId: String,
	session: EditorSession,
	modalTransform: UvEditModalTransform,
	marquee: MarqueeSelectController<MeshSelection>,
	meshPick: MeshPickController,
	liveCamera: State<ViewportCamera>,
	liveSize: State<IntSize>,
) {
	val gesture = modalTransform.gesture
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			gesture.lastPointer = change.position
			val latchedUvOperator = session.activeUvOperator.value
			val latchedTool = session.activeSelectTool.value
			// A gesture belongs to its initiating area, and the viewport-owned operators can never belong to a
			// UV area: while any of them (or another area's UV operator / armed tool) is live, this overlay is
			// fully inert - Escape and Enter stay global through the shell key ladder, and navigation still
			// falls through to the layer below.
			if (session.activeMeshOperator.value != null ||
				session.activeObjectOperator.value != null ||
				(latchedUvOperator != null && latchedUvOperator.areaId != areaId) ||
				(latchedTool != null && latchedTool.areaId != areaId)
			) {
				meshPick.cancel()
				continue
			}
			val activeCamera = liveCamera.value
			val size = liveSize.value
			// Zoom Region armed for this area: the region overlay above owns the next drag.  One armed mid-drag
			// leaves this drag's events here (the hit path is fixed at the press), so the box in flight is
			// abandoned rather than left to follow the pointer after Zoom Region disarms.
			if (session.zoomRegionArmedArea.value == areaId) {
				meshPick.cancel()
				continue
			}
			// A transform or the circle tool armed mid-drag supersedes the box.
			if (latchedUvOperator != null || latchedTool is ActiveSelectTool.Circle) {
				meshPick.cancel()
			}
			if (latchedUvOperator != null) {
				// MODAL: the shared controller drives the transform over the captured mapping and swallows
				// every event.
				gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTransform, activeCamera, size, gesture.areaScreenOrigin)
			} else if (latchedTool is ActiveSelectTool.Circle) {
				// CIRCLE SELECT: the shared controller paints / erases / commits the stroke and consumes every
				// event (paired with the navigation gate so MMB / wheel do not also pan / zoom).
				marquee.handleCircleEvent(event, change, latchedTool.radiusPx, activeCamera, size)
			} else {
				// ELEMENT PICK AND BOX SELECT, armed or not: the flow shared with the 2D viewport.  Only
				// primary-driven events and right-clicks are consumed, so middle-drag pan and wheel zoom fall
				// through.
				meshPick.handleEvent(event, change, latchedTool is ActiveSelectTool.BoxArmed, activeCamera, size)
			}
		}
	}
}