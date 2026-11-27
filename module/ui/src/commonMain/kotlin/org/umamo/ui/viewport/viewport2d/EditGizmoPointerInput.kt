package org.umamo.ui.viewport.viewport2d

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
 * The Edit overlay's pointer loop, routing each event to whichever of the modal transform, the circle
 * brush, or the element pick and box select this area owns.
 *
 * It runs for the life of its pointerInput, keyed on the area, and keeps the arguments it started with:
 * each is fixed for the area's life or a State holder read per event.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches and the selection.
 * @param EditModalTransform modalTransform The area's modal transform (its gesture state and commit side).
 * @param MarqueeSelectController<MeshSelection> marquee The area's box / circle machinery.
 * @param MeshPickController meshPick The area's element pick and box select.
 * @param LatticeHover latticeHover The area's lattice hover, which follows the idle pointer (Pose mode's idle lattices).
 * @param State<ViewportCamera> liveCamera The area camera.
 * @param State<IntSize> liveSize The area size in pixels.
 */
internal suspend fun PointerInputScope.editGizmoPointerLoop(
	areaId: String,
	session: EditorSession,
	modalTransform: EditModalTransform,
	marquee: MarqueeSelectController<MeshSelection>,
	meshPick: MeshPickController,
	latticeHover: LatticeHover,
	liveCamera: State<ViewportCamera>,
	liveSize: State<IntSize>,
) {
	val gesture = modalTransform.gesture
	awaitPointerEventScope {
		while (true) {
			val event = awaitPointerEvent()
			val change = event.changes.firstOrNull() ?: continue
			gesture.lastPointer = change.position
			val latchedOperator = session.activeMeshOperator.value
			val latchedTool = session.activeSelectTool.value

			// A gesture belongs to its initiating area: while another viewport's operator or tool is
			// live - or a UV operator, which can never belong to a viewport area - this overlay is
			// fully inert (no drive, no picks, no marquee).  Escape and Enter stay global through
			// the shell ladder, and navigation (pan / zoom) still falls through.
			if ((latchedOperator != null && latchedOperator.areaId != areaId) ||
				(latchedTool != null && latchedTool.areaId != areaId) ||
				session.activeUvOperator.value != null
			) {
				meshPick.cancel()
				latticeHover.clear()

				continue
			}

			val operator = latchedOperator
			val selectTool = latchedTool
			val activeCamera = liveCamera.value
			val size = liveSize.value

			// Zoom Region armed for this area: the region overlay above owns the next drag.  One armed
			// mid-drag leaves this drag's events here (the hit path is fixed at the press), so the box
			// in flight is abandoned rather than left to follow the pointer after Zoom Region disarms.
			if (session.zoomRegionArmedArea.value == areaId) {
				meshPick.cancel()
				latticeHover.clear()

				continue
			}

			// A transform or the circle tool armed mid-drag supersedes the box.
			if (operator != null || selectTool is ActiveSelectTool.Circle) {
				meshPick.cancel()
				latticeHover.clear()
			}

			if (operator != null) {
				// MODAL: the shared controller drives the transform over the captured shape and
				// swallows every event (stale discard, virtual-pointer drive, cursor wrap,
				// RMB-cancel / LMB-confirm, proportional-radius scroll via the target).
				gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTransform, activeCamera, size, gesture.areaScreenOrigin)
			} else if (selectTool is ActiveSelectTool.Circle) {
				// CIRCLE SELECT: the shared controller paints / erases / commits the stroke and
				// consumes every event (paired with the navigation gate so MMB / wheel do not
				// also pan / zoom); see MarqueeSelectController.handleCircleEvent.
				marquee.handleCircleEvent(event, change, selectTool.radiusPx, activeCamera, size)
			} else {
				// ELEMENT PICK AND BOX SELECT, armed or not: the flow shared with the UV editor and
				// Object mode (Shift+RightClick places the viewport's 2D cursor here).  Only
				// primary-driven events and right-clicks are consumed, so middle-drag pan and wheel
				// zoom fall through.  The lattice hover follows the same events first: Pose mode's idle lattices
				// light up where Alt+Q would take them, and Edit mode draws none, so nothing lights there.
				latticeHover.track(event, change)
				meshPick.handleEvent(event, change, selectTool is ActiveSelectTool.BoxArmed, activeCamera, size)
			}
		}
	}
}