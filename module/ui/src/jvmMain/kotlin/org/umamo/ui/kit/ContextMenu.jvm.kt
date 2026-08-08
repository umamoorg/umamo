package org.umamo.ui.kit

import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

/**
 * Desktop context-menu trigger: a secondary (right) mouse-button press.  Watches the raw pointer stream
 * and fires on the press whose button state has the secondary button down, consuming it so it does not
 * also reach the content beneath.
 *
 * An already-consumed press is skipped, so a nested context menu wins over an enclosing one: the inner
 * area consumes the press in the main pass before the outer area sees it, and the outer area then skips
 * it instead of opening a second menu on top (e.g. a row's menu suppresses the whole panel's area menu).
 *
 * デスクトップのトリガーは右ボタン押下。生のポインタ列を監視し、押下時に通知して消費する。既に消費済みの
 * 押下は無視するので、内側のコンテキストメニューが外側より優先される。
 *
 * @param Function onContextMenu Called with the press point in local coordinates.
 * @return Modifier The modifier with the secondary-click detector attached.
 */
internal actual fun Modifier.contextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier =
	secondaryClickGesture(PointerEventPass.Main, onContextMenu)

/**
 * Desktop text-field context-menu trigger: the same secondary-click detector on the INITIAL pass.
 *
 * The initial pass runs outermost-first, which is the whole point: the text-field primitive's own menu
 * handler sits on an inner node and watches the main pass, so watching a pass earlier and consuming there is
 * what lets the kit's menu replace it rather than lose to it.  Consuming a secondary press costs the field
 * nothing - it uses the primary button for caret placement and selection.
 *
 * @param Function onContextMenu Called with the press point in local coordinates.
 * @return Modifier The modifier with the detector attached.
 */
internal actual fun Modifier.textEditContextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier =
	secondaryClickGesture(PointerEventPass.Initial, onContextMenu)

/**
 * A secondary-press detector on [pass], reporting the press point and consuming it.
 *
 * @param PointerEventPass pass The pointer pass to watch.
 * @param Function onContextMenu Called with the press point in local coordinates.
 * @return Modifier The modifier with the detector attached.
 */
private fun Modifier.secondaryClickGesture(pass: PointerEventPass, onContextMenu: (IntOffset) -> Unit): Modifier =
	this.pointerInput(pass) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent(pass)
				val change = event.changes.first()
				if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
					change.consume()
					onContextMenu(change.position.toIntOffset())
				}
			}
		}
	}