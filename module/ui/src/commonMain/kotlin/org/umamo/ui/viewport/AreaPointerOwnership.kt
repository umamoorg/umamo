package org.umamo.ui.viewport

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import org.umamo.edit.EditorSession
import org.umamo.edit.chromeStepsAsideForArea

/**
 * Whether the chrome floating over one of the area's must step aside for one of the area's gestures (see
 * [chromeStepsAsideForArea]), as composition state, so chrome recomposes the moment a gesture starts or ends.
 *
 * The armed deform brush is not one of them, so the toolbar and the sidebar stay usable while the brush is
 * armed; a brush drag in flight holds the chrome inert through the in-flight gesture flag.
 *
 * @param EditorSession? session The session whose tool latches are read, or null with no document open.
 * @param String areaId The area asking.
 * @return Boolean True when a gesture holds this area's chrome inert; false with no session.
 */
@Composable
internal fun rememberChromeStepsAsideForArea(session: EditorSession?, areaId: String): Boolean {
	if (session == null) {
		return false
	}

	val meshOperator = session.activeMeshOperator.collectAsState().value
	val objectOperator = session.activeObjectOperator.collectAsState().value
	val uvOperator = session.activeUvOperator.collectAsState().value
	val selectTool = session.activeSelectTool.collectAsState().value
	val viewportGestureActive = session.viewportGestureActive.collectAsState().value
	val zoomRegionArmedArea = session.zoomRegionArmedArea.collectAsState().value

	return chromeStepsAsideForArea(
		areaId = areaId,
		// The same precedence as the session's activeOperator; at most one of the three runs at a time.
		operatorAreaId = (meshOperator ?: objectOperator ?: uvOperator)?.areaId,
		selectToolAreaId = selectTool?.areaId,
		viewportGestureActive = viewportGestureActive,
		zoomRegionArmedAreaId = zoomRegionArmedArea,
	)
}

/**
 * Whether any gesture is live anywhere in the window: a modal operator, an armed select tool, deform brush, or Zoom
 * Region, or an in-flight box drag in whichever area, an open pie, or a parked which-axis question.  A surface's
 * context menu stays shut while one is, even in an area the gesture does not own: the right press belongs to that
 * gesture, and a menu opening beside it would act on a state about to change.
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
	val brush = session.activeBrush.collectAsState().value
	val viewportGestureActive = session.viewportGestureActive.collectAsState().value
	val zoomRegionArmedArea = session.zoomRegionArmedArea.collectAsState().value
	val pieMenu = session.activePieMenu.collectAsState().value
	val parameterChoice = session.pendingParameterChoice.collectAsState().value

	return meshOperator != null ||
		objectOperator != null ||
		uvOperator != null ||
		selectTool != null ||
		brush != null ||
		viewportGestureActive ||
		zoomRegionArmedArea != null ||
		pieMenu != null ||
		parameterChoice != null
}