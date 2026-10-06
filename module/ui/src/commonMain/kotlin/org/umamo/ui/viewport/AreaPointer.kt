package org.umamo.ui.viewport

import androidx.compose.runtime.MutableState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Records where the pointer last was over this node into [pointer], in the node's local pixels: a viewport
 * host's own record of its area's pointer.  The pointer-addressed keymap requests (Select Linked, Alt+Q,
 * Rip) read it rather than an overlay's, because the overlays that would otherwise know do not mount in the
 * very states those requests still have to answer in.
 *
 * Watch-only: it observes the Initial pass without consuming, so the record is current whichever descendant
 * owns the gesture, and the navigation loop and the overlays still see every event.  With several pointers
 * down it follows the event's first change, the one the overlay and navigation loops act on.
 *
 * @param String areaId The hosting area.
 * @param MutableState<Offset> pointer The record to write.
 * @return Modifier This modifier with the observer attached.
 */
internal fun Modifier.tracksAreaPointer(areaId: String, pointer: MutableState<Offset>): Modifier =
	pointerInput(areaId, pointer) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(PointerEventPass.Initial)
				event.changes.firstOrNull()?.let { change -> pointer.value = change.position }
			}
		}
	}