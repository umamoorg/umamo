package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.umamo.edit.EditorSession

/**
 * Whether one of a work surface area's gestures owns the pointer: the area's modal operator (G / S / R in the 2D
 * viewport or the UV editor), its armed Box / Circle select tool, its armed Zoom Region, or an in-flight
 * un-armed box drag, which carries no area because pointer capture already pins its events to the dragging
 * overlay.  While one does, the gesture follows the pointer everywhere in the area: the surface's navigation
 * stops panning and zooming under it, and the chrome floating over the surface lets the pointer through so the
 * gesture can be confirmed or cancelled over it, as a Blender modal operator can.  A gesture latched in another
 * area owns nothing here.
 *
 * One gate serves both surfaces: an operator latches the area it runs in, and that area shows the surface the
 * operator belongs to, so a 2D viewport's area never matches a UV operator, nor a UV editor's a mesh operator.
 *
 * @param String areaId The area asking.
 * @param String? operatorAreaId The area of the running modal operator, whichever surface it belongs to, or null.
 * @param String? selectToolAreaId The area of the armed select tool, or null.
 * @param Boolean viewportGestureActive Whether an un-armed box drag is in flight.
 * @param String? zoomRegionArmedAreaId The area Zoom Region is armed in, or null.
 * @return Boolean True when a gesture owns this area's pointer.
 */
internal fun gestureOwnsAreaPointer(
	areaId: String,
	operatorAreaId: String?,
	selectToolAreaId: String?,
	viewportGestureActive: Boolean,
	zoomRegionArmedAreaId: String?,
): Boolean =
	operatorAreaId == areaId ||
		selectToolAreaId == areaId ||
		viewportGestureActive ||
		zoomRegionArmedAreaId == areaId

/**
 * Whether one of the area's gestures owns the pointer right now (see [gestureOwnsAreaPointer]), read straight
 * from the session's latches, the way a surface's navigation loop reads it on each pointer event.
 *
 * @param String areaId The area asking.
 * @return Boolean True when a gesture owns this area's pointer.
 */
internal fun EditorSession.gestureOwnsAreaPointer(areaId: String): Boolean =
	gestureOwnsAreaPointer(
		areaId = areaId,
		operatorAreaId = activeOperator?.areaId,
		selectToolAreaId = activeSelectTool.value?.areaId,
		viewportGestureActive = viewportGestureActive.value,
		zoomRegionArmedAreaId = zoomRegionArmedArea.value,
	)

/**
 * Whether one of the area's gestures owns the pointer (see [gestureOwnsAreaPointer]), as composition state, so
 * chrome that steps aside for a gesture recomposes the moment one starts or ends.
 *
 * @param EditorSession? session The session whose tool latches are read, or null with no document open.
 * @param String areaId The area asking.
 * @return Boolean True when a gesture owns this area's pointer; false with no session.
 */
@Composable
internal fun rememberGestureOwnsAreaPointer(session: EditorSession?, areaId: String): Boolean {
	if (session == null) {
		return false
	}

	val meshOperator = session.activeMeshOperator.collectAsState().value
	val objectOperator = session.activeObjectOperator.collectAsState().value
	val uvOperator = session.activeUvOperator.collectAsState().value
	val selectTool = session.activeSelectTool.collectAsState().value
	val viewportGestureActive = session.viewportGestureActive.collectAsState().value
	val zoomRegionArmedArea = session.zoomRegionArmedArea.collectAsState().value

	return gestureOwnsAreaPointer(
		areaId = areaId,
		// The same precedence as the session's activeOperator; at most one of the three runs at a time.
		operatorAreaId = (meshOperator ?: objectOperator ?: uvOperator)?.areaId,
		selectToolAreaId = selectTool?.areaId,
		viewportGestureActive = viewportGestureActive,
		zoomRegionArmedAreaId = zoomRegionArmedArea,
	)
}

/**
 * Whether any gesture is live anywhere in the window: a modal operator, an armed select tool or Zoom Region, or an
 * in-flight box drag in whichever area, an open pie, or a parked which-axis question.  A surface's context menu stays
 * shut while one is, even in an area the gesture does not own: the right press belongs to that gesture, and a menu
 * opening beside it would act on a state about to change.
 *
 * @param EditorSession? session The session whose latches are read, or null with no document open.
 * @return Boolean True when a gesture is live; false with no session.
 */
@Composable
internal fun rememberAnyGestureLive(session: EditorSession?): Boolean {
	if (session == null) {
		return false
	}

	val meshOperator = session.activeMeshOperator.collectAsState().value
	val objectOperator = session.activeObjectOperator.collectAsState().value
	val uvOperator = session.activeUvOperator.collectAsState().value
	val selectTool = session.activeSelectTool.collectAsState().value
	val viewportGestureActive = session.viewportGestureActive.collectAsState().value
	val zoomRegionArmedArea = session.zoomRegionArmedArea.collectAsState().value
	val pieMenu = session.activePieMenu.collectAsState().value
	val parameterChoice = session.pendingParameterChoice.collectAsState().value

	return meshOperator != null ||
		objectOperator != null ||
		uvOperator != null ||
		selectTool != null ||
		viewportGestureActive ||
		zoomRegionArmedArea != null ||
		pieMenu != null ||
		parameterChoice != null
}