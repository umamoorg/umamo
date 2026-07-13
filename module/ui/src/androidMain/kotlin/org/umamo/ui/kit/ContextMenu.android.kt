package org.umamo.ui.kit

import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset

/**
 * Touch context-menu trigger: a long press (there is no secondary button on a touch screen).  Uses the
 * standard tap-gesture detector, which fires onLongPress with the press point once the finger has been
 * held past the long-press timeout without moving.
 *
 * タッチのトリガーは長押し（タッチには副ボタンが無い）。長押しタイムアウト経過時に押下点で通知する。
 *
 * @param Function onContextMenu Called with the press point in local coordinates.
 * @return Modifier The modifier with the long-press detector attached.
 */
internal actual fun Modifier.contextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier =
	this.pointerInput(Unit) {
		detectTapGestures(onLongPress = { offset -> onContextMenu(offset.toIntOffset()) })
	}
