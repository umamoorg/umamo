package org.umamo.ui.workspace.rowdrag

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow

/**
 * Picks the row up on a long press and drags it: the press point is converted into window
 * coordinates through [holder] and seeds the drag there, so the initial target is the row itself
 * (i.e. none) and never a stale target left over from the previous drag; every move is converted
 * the same way; a release runs [onDrop] (the space reads the controller's target and applies the
 * move); a cancelled gesture ends the drag without dropping.  The long press keeps the gesture
 * distinct from a tap-to-select handler on the same row, so a press still selects first and only a
 * hold begins the drag.
 *
 * @param RowDragController    controller The space's drag state.
 * @param String               rowKey     The row's stable key.
 * @param Payload?             payload    What a drag of this row relocates, or null for a row that
 *   cannot be picked up (a heading, a lost binding) - the modifier is then a no-op.
 * @param RowCoordinatesHolder holder     The row's latest layout coordinates, for the window conversion.
 * @param Function             onDrop     Runs on release; read the controller's target inside it.  Pass
 *   a lambda over updated state, since the gesture re-keys only on the controller, key, and payload.
 * @return Modifier This modifier with the gesture attached, or unchanged when the row cannot be dragged.
 */
fun <Payload : Any> Modifier.dragRowOnLongPress(
	controller: RowDragController<Payload>,
	rowKey: String,
	payload: Payload?,
	holder: RowCoordinatesHolder,
	onDrop: () -> Unit,
): Modifier {
	if (payload == null) {
		return this
	}
	return pointerInput(controller, rowKey, payload) {
		detectDragGesturesAfterLongPress(
			onDragStart = { offset ->
				val bounds = holder.coordinates?.boundsInWindow()
				controller.start(rowKey, payload, (bounds?.left ?: 0f) + offset.x, (bounds?.top ?: 0f) + offset.y)
			},
			onDrag = { change, _ ->
				val bounds = holder.coordinates?.boundsInWindow()
				controller.drag((bounds?.left ?: 0f) + change.position.x, (bounds?.top ?: 0f) + change.position.y)
			},
			onDragEnd = { onDrop() },
			onDragCancel = { controller.end() },
		)
	}
}