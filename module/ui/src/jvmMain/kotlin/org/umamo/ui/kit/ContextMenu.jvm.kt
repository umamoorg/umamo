package org.umamo.ui.kit

import androidx.compose.ui.Modifier
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
	this.pointerInput(Unit) {
		awaitPointerEventScope {
			while (true) {
				val event = awaitPointerEvent()
				val change = event.changes.first()
				if (event.type == PointerEventType.Press && event.buttons.isSecondaryPressed && !change.isConsumed) {
					change.consume()
					onContextMenu(change.position.toIntOffset())
				}
			}
		}
	}
