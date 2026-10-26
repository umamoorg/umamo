package org.umamo.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Keeps the pointer from falling through this element to a sibling drawn beneath it, such as the viewport
 * under a panel floating over it.
 *
 * Compose offers a pointer event to overlapping siblings from the top down and stops at the first one with a
 * pointer handler under the pointer.  A background or padding has no handler, so without one a click on a
 * panel's empty body goes to the sibling underneath.  The handler added here only listens and consumes
 * nothing: the element's ancestors still see every event (an area's hover tracking and its context menu
 * among them), and the controls inside the element keep handling their own.
 *
 * @param Boolean enabled Whether to block; false returns this modifier unchanged and lets the pointer through.
 * @return Modifier This modifier with the blocking handler appended.
 */
fun Modifier.blocksPointerFallThrough(enabled: Boolean = true): Modifier {
	if (!enabled) {
		return this
	}

	return pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				awaitPointerEvent()
			}
		}
	}
}