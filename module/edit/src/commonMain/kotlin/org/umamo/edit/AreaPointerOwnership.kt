package org.umamo.edit

/**
 * Whether one of a work surface area's gestures owns the pointer: a modal operator, select tool, or Zoom Region
 * latched in this area, or an in-flight un-armed box drag, which carries no area because pointer capture already
 * pins its events to the dragging overlay.  While one does, the surface's navigation stops under it and the chrome
 * floating over the surface lets the pointer through, so the gesture can be confirmed or cancelled anywhere in the
 * area, as a Blender modal operator can.
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
fun gestureOwnsAreaPointer(
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
		viewportGestureActive = viewportGestureActive.value,
		zoomRegionArmedAreaId = zoomRegionArmedArea.value,
	)