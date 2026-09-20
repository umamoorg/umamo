package org.umamo.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Marks a node as a text editor's own surface, so a host watching the window can tell a press ON the
 * editor from a press anywhere else.
 *
 * That difference is the whole of the rule: a press outside the field ends text entry and hands the
 * keyboard back, while a press on the field is only a caret move.  The claim is set on the Initial pass,
 * after the host has cleared it on that same pass (an ancestor sees a pass before its descendants), and
 * the host reads it on the Final pass once every descendant has handled the press.  Nothing is consumed -
 * a claim must never cost the field its caret placement or its selection drag.
 *
 * The claim is unconditional rather than gated on focus, because "a press landed on a text editor" is the
 * question being asked: gating it would push focus out to the host and pull it straight back when the
 * user presses one field while another one is focused.
 *
 * The cursor is not this modifier's business.  A text field already wears the platform's text pointer
 * from Foundation, and the shell claims that same pointer window-wide while text entry is live, so the
 * two agree without anything here overriding either.
 *
 * @param InlineEditController controller The seam this editor shares with its host.
 * @return Modifier The node, carrying the editor's press claim.
 */
fun Modifier.textEntrySurface(controller: InlineEditController): Modifier =
	this.pointerInput(controller) {
		awaitPointerEventScope {
			while (true) {
				if (awaitPointerEvent(PointerEventPass.Initial).type == PointerEventType.Press) {
					controller.pressLandedOnTextEditor = true
				}
			}
		}
	}