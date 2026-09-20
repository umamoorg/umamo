package org.umamo.ui.workspace

import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerInputScope

/**
 * Whether a press that has finished dispatching should end text entry and hand the keyboard back to the
 * shell root.
 *
 * Blender's rule: a press anywhere that is not the field itself ends text entry, so the keyboard cannot
 * stay trapped in a header filter while the user works somewhere else.  A press on the editor's own
 * surface is only a caret move, and [pressLandedOnTextEditor] is how the editor says so.
 *
 * The overlay case is the one exception, and it is a rule rather than a patch: while an overlay that owns
 * its own focus is open the modal ladder has already stood the root's shortcuts down, so a release would
 * hand the keyboard to a root that refuses to use it - and the command palette keeps its list navigation
 * on an ancestor of its own search field, which would leave the focus path with it.
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
 * The work belongs on the Final pass because taking focus back is not inert: an inline rename commits on
 * focus loss and a number entry commits and leaves the composition, so acting while the press was still
 * on its way to its target would rewrite the tree mid-dispatch.
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