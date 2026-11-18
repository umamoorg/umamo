package org.umamo.edit

/**
 * Whether one of a work surface area's gestures owns the pointer: a modal operator, select tool, deform brush, or
 * Zoom Region latched in this area, or an in-flight un-armed box drag, which carries no area because pointer capture
 * already pins its events to the dragging overlay.  While one does, the surface's navigation stops under it.
 *
 * The chrome floating over the surface answers to its own gate (see [chromeStepsAsideForArea]), because the
 * armed deform brush owns the navigation without holding the chrome.
 *
 * One gate serves both surfaces: an operator latches the area it runs in, and that area shows the surface the
 * operator belongs to, so a 2D viewport's area never matches a UV operator, nor a UV editor's a mesh operator.
 *
 * @param String areaId The area asking.
 * @param String? operatorAreaId The area of the running modal operator, whichever surface it belongs to, or null.
 * @param String? selectToolAreaId The area of the armed select tool, or null.
 * @param String? brushAreaId The area of the armed deform brush, or null.
 * @param Boolean viewportGestureActive Whether an un-armed box drag is in flight.
 * @param String? zoomRegionArmedAreaId The area Zoom Region is armed in, or null.
 * @return Boolean True when a gesture owns this area's pointer.
 */
fun gestureOwnsAreaPointer(
	areaId: String,
	operatorAreaId: String?,
	selectToolAreaId: String?,
	brushAreaId: String?,
	viewportGestureActive: Boolean,
	zoomRegionArmedAreaId: String?,
): Boolean =
	operatorAreaId == areaId ||
		selectToolAreaId == areaId ||
		brushAreaId == areaId ||
		viewportGestureActive ||
		zoomRegionArmedAreaId == areaId

/**
 * Whether the chrome floating over one of a work surface area's must step aside for one of the area's gestures:
 * a modal operator, a select tool, or Zoom Region latched in the area, or a drag in flight over the surface.
 *
 * The armed deform brush is NOT one of them.  The brush is a tool the rigger keeps armed rather than a gesture
 * awaiting confirmation, and the chrome over the surface has to stay usable while it is armed: otherwise the
 * toolbar cannot switch tools and the sidebar cannot reach the brush's own settings.  A brush drag in flight still
 * holds the chrome inert, through the in-flight gesture flag.
 *
 * @param String areaId The area asking.
 * @param String? operatorAreaId The area of the running modal operator, whichever surface it belongs to, or null.
 * @param String? selectToolAreaId The area of the armed select tool, or null.
 * @param Boolean viewportGestureActive Whether a drag is in flight over a surface.
 * @param String? zoomRegionArmedAreaId The area Zoom Region is armed in, or null.
 * @return Boolean True when a gesture holds this area's chrome inert.
 */
fun chromeStepsAsideForArea(
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
fun SessionToolLatches.gestureOwnsAreaPointer(areaId: String): Boolean =
	gestureOwnsAreaPointer(
		areaId = areaId,
		operatorAreaId = activeOperator?.areaId,
		selectToolAreaId = activeSelectTool.value?.areaId,
		brushAreaId = activeBrush.value?.areaId,
		viewportGestureActive = viewportGestureActive.value,
		zoomRegionArmedAreaId = zoomRegionArmedArea.value,
	)

/**
 * Whether the chrome floating over one of the area's must step aside right now (see [chromeStepsAsideForArea]),
 * read straight from the session's latches, the way the chrome reads it as composition state.
 *
 * @param String areaId The area asking.
 * @return Boolean True when a gesture holds this area's chrome inert.
 */
fun SessionToolLatches.chromeStepsAsideForArea(areaId: String): Boolean =
	chromeStepsAsideForArea(
		areaId = areaId,
		operatorAreaId = activeOperator?.areaId,
		selectToolAreaId = activeSelectTool.value?.areaId,
		viewportGestureActive = viewportGestureActive.value,
		zoomRegionArmedAreaId = zoomRegionArmedArea.value,
	)