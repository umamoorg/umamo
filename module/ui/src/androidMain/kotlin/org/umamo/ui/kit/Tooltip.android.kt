package org.umamo.ui.kit

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Touch tooltip: touch input has no hover, so the content renders unchanged - only the caller's layout
 * [modifier] is carried through.  A long-press tooltip using [TooltipCard] will land with the Android
 * target, at which point [text] starts being used.
 *
 * タッチはホバーが無いのでそのまま描画する（レイアウト modifier のみ適用）。長押しツールチップは
 * Android ターゲットで対応予定。
 *
 * @param String   text     The tooltip label (unused until long-press tooltips land).
 * @param Modifier modifier The layout modifier, applied to the wrapper.
 * @param Function content  The control the tooltip describes.
 */
@Composable
actual fun Tooltip(text: String, modifier: Modifier, content: @Composable () -> Unit) {
	Box(modifier = modifier) {
		content()
	}
}
