package org.umamo.ui.viewport.uv

import androidx.compose.runtime.State
import androidx.compose.ui.input.pointer.PointerInputScope
import androidx.compose.ui.unit.IntSize
import org.umamo.edit.EditorSession
import org.umamo.render.ViewportCamera
import org.umamo.ui.viewport.gizmo.ObjectPickController

/**
 * The UV Object overlay's pointer loop.  Every event records the pointer, then goes to exactly one branch:
 * the placement gesture while a UV operator is latched here, and otherwise the click pick and the un-armed
 * box.  While another area's UV operator, any viewport-owned operator, or ANY armed select tool is live
 * (none can belong to a UV area in Object mode), or Zoom Region is armed here, the loop takes nothing and
 * drops an in-flight box - Escape and Enter stay global through the shell ladder, and navigation falls
 * through to the layer below.  Only primary-driven events are consumed while idle, so pan / zoom and the
 * plain right-click (the context menu) fall through.
 *
 * It runs for the life of its pointerInput, keyed on the area, and keeps the arguments it started with:
 * each is fixed for the area's life or a State holder read per event.
 *
 * @param String areaId The overlay's area.
 * @param EditorSession session The session owning the latches and the selection.
 * @param UvPlacementModalTransform modalTransform The area's placement gesture (its gesture state and commit side).
 * @param ObjectPickController objectPick The area's click pick and box flow.
 * @param State<ViewportCamera> liveCamera The frame camera.
 * @param State<IntSize> liveSize The area size in pixels.
 */
internal suspend fun PointerInputScope.uvObjectGizmoPointerLoop(
	areaId: String,
	session: EditorSession,
	modalTransform: UvPlacementModalTransform,
	objectPick: ObjectPickController,
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
			// A gesture belongs to its initiating area: another area's UV operator, or any viewport-owned
			// operator or armed tool, leaves this overlay fully inert - dropping an in-flight box.
			if (session.activeMeshOperator.value != null ||
				session.activeObjectOperator.value != null ||
				(latchedUvOperator != null && latchedUvOperator.areaId != areaId) ||
				session.activeSelectTool.value != null
			) {
				objectPick.cancel()
				continue
			}
			val activeCamera = liveCamera.value
			val size = liveSize.value
			// Zoom Region armed for this area: the region overlay above owns the next drag.  One armed mid-drag
			// leaves this drag's events here (the hit path is fixed at the press), so the box in flight is
			// abandoned rather than left to follow the pointer after Zoom Region disarms.
			if (session.zoomRegionArmedArea.value == areaId) {
				objectPick.cancel()
				continue
			}
			if (latchedUvOperator != null) {
				// MODAL: the placement gesture owns the pointer through the shared controller (stale discard,
				// virtual pointer, cursor wrap, LMB-confirm / RMB-cancel).
				objectPick.cancel()
				gesture.lastPointer = gesture.modalController.handleEvent(event, change, modalTransform, activeCamera, size, gesture.areaScreenOrigin)
			} else {
				// Box select can never arm over a UV area in Object mode, so the flow runs un-armed.
				objectPick.handleEvent(event, change, false, activeCamera, size)
			}
		}
	}
}