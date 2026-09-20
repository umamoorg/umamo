package org.umamo.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.pointerInput

/**
 * Marks a node as a text editor's own surface, so a host watching the window can tell a press ON the
 * editor from a press anywhere else.
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