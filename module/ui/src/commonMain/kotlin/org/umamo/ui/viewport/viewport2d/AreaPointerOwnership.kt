package org.umamo.ui.viewport.viewport2d

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.umamo.edit.EditorSession

/**
 * Whether one of a 2D viewport area's gestures owns the pointer: the area's modal operator (G / S / R), its
 * armed Box / Circle select tool, its armed Zoom Region, or an in-flight un-armed box drag, which carries no
 * area because pointer capture already pins its events to the dragging overlay.  While one does, the gesture
 * follows the pointer everywhere in the area: the surface's navigation stops panning and zooming under it,
 * and the chrome floating over the surface lets the pointer through so the gesture can be confirmed or
 * cancelled over it, as a Blender modal operator can.  A gesture latched in another area owns nothing here.
 *
 * @param String areaId The area asking.
 * @param String? meshOperatorAreaId The area of the running Edit-mode operator, or null.
 * @param String? objectOperatorAreaId The area of the running Object-mode operator, or null.
 * @param String? selectToolAreaId The area of the armed select tool, or null.
 * @param Boolean viewportGestureActive Whether an un-armed box drag is in flight.
 * @param String? zoomRegionArmedAreaId The area Zoom Region is armed in, or null.
 * @return Boolean True when a gesture owns this area's pointer.
 */
internal fun viewport2dGestureOwnsPointer(
	areaId: String,
	meshOperatorAreaId: String?,
	objectOperatorAreaId: String?,
	selectToolAreaId: String?,
	viewportGestureActive: Boolean,
	zoomRegionArmedAreaId: String?,
): Boolean =
	meshOperatorAreaId == areaId ||
		objectOperatorAreaId == areaId ||
		selectToolAreaId == areaId ||
		viewportGestureActive ||
		zoomRegionArmedAreaId == areaId

/**
 * Whether one of the area's gestures owns the pointer right now (see [viewport2dGestureOwnsPointer]), read
 * the way the session's pointer loop reads it.
 *
 * @param EditorSession session The session whose tool latches are read.
 * @param String areaId The area asking.
 * @return Boolean True when a gesture owns this area's pointer.
 */
internal fun EditorSession.gestureOwnsViewport2dPointer(areaId: String): Boolean =
	viewport2dGestureOwnsPointer(
		areaId = areaId,
		meshOperatorAreaId = activeMeshOperator.value?.areaId,
		objectOperatorAreaId = activeObjectOperator.value?.areaId,
		selectToolAreaId = activeSelectTool.value?.areaId,
		viewportGestureActive = viewportGestureActive.value,
		zoomRegionArmedAreaId = zoomRegionArmedArea.value,
	)

/**
 * Whether one of the area's gestures owns the pointer (see [viewport2dGestureOwnsPointer]), as composition
 * state, so chrome that steps aside for a gesture recomposes the moment one starts or ends.
 *
 * @param EditorSession? session The session whose tool latches are read, or null with no document open.
 * @param String areaId The area asking.
 * @return Boolean True when a gesture owns this area's pointer; false with no session.
 */
@Composable
internal fun rememberViewport2dGestureOwnsPointer(session: EditorSession?, areaId: String): Boolean {
	if (session == null) {
		return false
	}

	val meshOperator = session.activeMeshOperator.collectAsState().value
	val objectOperator = session.activeObjectOperator.collectAsState().value
	val selectTool = session.activeSelectTool.collectAsState().value
	val viewportGestureActive = session.viewportGestureActive.collectAsState().value
	val zoomRegionArmedArea = session.zoomRegionArmedArea.collectAsState().value

	return viewport2dGestureOwnsPointer(
		areaId = areaId,
		meshOperatorAreaId = meshOperator?.areaId,
		objectOperatorAreaId = objectOperator?.areaId,
		selectToolAreaId = selectTool?.areaId,
		viewportGestureActive = viewportGestureActive,
		zoomRegionArmedAreaId = zoomRegionArmedArea,
	)
}