package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Whether a press that has finished dispatching should end text entry and hand the keyboard back to the
 * shell root.
 *
 * @param Boolean textEntryActive         Whether a text editor has parked its cancel hook.
 * @param Boolean pressLandedOnTextEditor Whether the press landed on a text editor's own surface.
 * @param Boolean selfFocusedOverlayOpen  Whether an overlay that owns its own focus is open.
 * @return Boolean True when the shell should take focus back.
 */
internal fun shouldReleaseTextEntry(
	textEntryActive: Boolean,
	pressLandedOnTextEditor: Boolean,
	selfFocusedOverlayOpen: Boolean,
): Boolean = textEntryActive && !pressLandedOnTextEditor && !selfFocusedOverlayOpen

/**
 * Watches one node for the presses that end text entry, reporting each press twice: [beginPress] on the
 * Initial pass, before any descendant has seen it, and [settlePress] on the Final pass, once every
 * descendant has handled it.  Installed on the shell root, whose two calls clear and then read the
 * editor's press claim.  Consumes nothing.
 *
 * @param Function beginPress  Runs on each press's Initial pass, before any descendant sees it.
 * @param Function settlePress Runs on the same press's Final pass, after every descendant has seen it.
 */
internal suspend fun PointerInputScope.observeTextEntryPresses(
	beginPress: () -> Unit,
	settlePress: () -> Unit,
) {
	awaitPointerEventScope {
		while (true) {
			if (awaitPointerEvent(PointerEventPass.Initial).type != PointerEventType.Press) {
				continue
			}
			beginPress()
			// The same press again, now that it has reached whatever it landed on.
			awaitPointerEvent(PointerEventPass.Final)
			settlePress()
		}
	}
}