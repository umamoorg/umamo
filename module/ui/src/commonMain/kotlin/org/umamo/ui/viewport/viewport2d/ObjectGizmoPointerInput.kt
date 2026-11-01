package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.ActiveSelectTool
import org.umamo.edit.EditorSession
import org.umamo.edit.Selection
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.MarqueeSelectController
import org.umamo.ui.viewport.gizmo.ObjectPickController

/**
 * The Object overlay's pointer loop.  Every event records the pointer, then goes to exactly one branch: the
 * modal transform while an operator is latched here, the circle brush while it is armed here, and
 * otherwise the click pick and the box select, armed or not, with the lattice hover beside them; every other
 * branch clears the hover.  While another area owns a gesture (or a UV operator runs, which never belongs to a
 * viewport), or Zoom Region is armed here, the loop takes nothing.  Whatever takes the area over mid-drag -
 * another area, a transform, the circle tool, Zoom Region - abandons an in-flight box first.
 *
 * It runs for the life of its pointerInput, keyed on the area, and keeps the arguments it started with:
 * each is fixed for the area's life or a State holder read per event.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches and the selection.
 * @param ObjectModalTransform modalTransform The area's modal transform (its gesture state and commit side).
 * @param MarqueeSelectController<Selection> marquee The area's box / circle machinery.
 * @param ObjectPickController objectPick The area's click pick and box flows.
 * @param LatticeHover latticeHover The area's lattice hover, which follows the idle pointer.
 * @param State<ViewportCamera> liveCamera The area camera.
 * @param State<IntSize> liveSize The area size in pixels.
 */
internal suspend fun PointerInputScope.objectGizmoPointerLoop(
	areaId: String,
	session: EditorSession,
	modalTransform: ObjectModalTransform,
	marquee: MarqueeSelectController<Selection>,
	objectPick: ObjectPickController,
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
			val latchedOperator = session.activeObjectOperator.value
			val latchedTool = session.activeSelectTool.value

			// A gesture belongs to its initiating area: while another viewport's operator or tool is
			// live - or a UV operator, which can never belong to a viewport area - this overlay is
			// fully inert (no drive, no picks, no marquee).  Escape and Enter stay global through
			// the shell ladder, and navigation (pan / zoom) still falls through.
			if ((latchedOperator != null && latchedOperator.areaId != areaId) ||
				(latchedTool != null && latchedTool.areaId != areaId) ||
				session.activeUvOperator.value != null
			) {
				objectPick.cancel()
				latticeHover.clear()

				continue
			}

			val operator = latchedOperator
			val tool = latchedTool
			val activeCamera = liveCamera.value
			val size = liveSize.value

			// Zoom Region armed for this area: the region overlay above owns the next drag.  One armed
			// mid-drag leaves this drag's events here (the hit path is fixed at the press), so the box
			// in flight is abandoned rather than landed.
			if (session.zoomRegionArmedArea.value == areaId) {
				objectPick.cancel()
				latticeHover.clear()

				continue
			}

			// A transform or the circle tool armed mid-drag supersedes the box (an armed box that changes
			// state under the drag is handled by the box flow itself).
			if (operator != null || tool is ActiveSelectTool.Circle) {
				objectPick.cancel()
				latticeHover.clear()
			}

			if (operator != null) {
				// MODAL transform: the shared controller drives every captured drawable over the
				// shared pivot and swallows every event (stale discard, virtual-pointer drive,
				// cursor wrap, RMB-cancel / LMB-confirm).
				gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTransform, activeCamera, size, gesture.areaScreenOrigin)
			} else if (tool is ActiveSelectTool.Circle) {
				// CIRCLE SELECT: the shared controller paints drawables by centroid, previews the
				// stroke through the GPU tint, and consumes every event; see
				// MarqueeSelectController.handleCircleEvent.
				marquee.handleCircleEvent(event, change, tool.radiusPx, activeCamera, size)
			} else {
				// CLICK PICK AND BOX SELECT, armed (Blender's B) or not: the flow shared with the Edit
				// overlay and the UV editor - a drag boxes (Shift adds), a sub-threshold release is the
				// click pick (replace / toggle / Alt overlap) or, armed, just disarms, and
				// Shift+RightClick places the 2D cursor.  Only primary-driven events and right-clicks are
				// consumed, so middle-drag pan and wheel zoom fall through to the navigation layer.  The lattice
				// hover follows the same events first, so the lattice a click would take lights up before it.
				latticeHover.track(event, change)
				objectPick.handleEvent(event, change, tool is ActiveSelectTool.BoxArmed, activeCamera, size)
			}
		}
	}
}