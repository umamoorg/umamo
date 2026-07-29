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

/**
 * Touch text-field context-menu trigger: the same long press, with no attempt to outrank the platform.
 *
 * The desktop actual has to beat the text field's built-in secondary-click menu; here the corresponding
 * gesture is a long press, and on a touch screen long-pressing text means SELECT A WORD, which is correct
 * platform behaviour and not ours to take away.  So this loses to the platform where the two collide, and a
 * field inside a context-menu area does not offer that area's items on Android.  Revisit alongside the
 * Android viewport - until that lands the editor does not run here, so the gap costs nothing today.
 *
 * タッチのトリガーは長押しだが、長押しは単語選択という OS 標準の意味を持つため、そちらを優先する。
 *
 * @param Function onContextMenu Called with the press point in local coordinates.
 * @return Modifier The modifier with the detector attached.
 */
internal actual fun Modifier.textEditContextMenuGesture(onContextMenu: (IntOffset) -> Unit): Modifier =
	contextMenuGesture(onContextMenu)
