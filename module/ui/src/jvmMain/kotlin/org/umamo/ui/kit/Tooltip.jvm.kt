package org.umamo.ui.kit

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.TooltipArea
import androidx.compose.foundation.TooltipPlacement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp

/** How long the pointer must dwell over the content before the tooltip appears. */
private const val TOOLTIP_DELAY_MILLIS = 500

/**
 * Desktop tooltip: [TooltipArea] reveals the label after a mouse-dwell over the content, placed just
 * below the cursor.  A blank label attaches nothing (the content is wrapped only to carry [modifier]),
 * so callers can pass an unconditional description.
 *
 * デスクトップのツールチップ。マウス静止後にカーソル下へラベルを表示する。空ラベルは何も表示しない。
 *
 * @param String   text     The tooltip label; blank attaches no tooltip.
 * @param Modifier modifier The layout modifier, applied to the wrapper.
 * @param Function content  The control the tooltip describes.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
actual fun Tooltip(text: String, modifier: Modifier, content: @Composable () -> Unit) {
	if (text.isBlank()) {
		Box(modifier = modifier) {
			content()
		}
		return
	}
	TooltipArea(
		tooltip = { TooltipCard(text) },
		modifier = modifier,
		delayMillis = TOOLTIP_DELAY_MILLIS,
		// Follow the cursor rather than anchoring the component, matching the Blender-style pointer focus;
		// the downward offset keeps the card clear of the pointer itself.
		tooltipPlacement = TooltipPlacement.CursorPoint(offset = DpOffset(0.dp, 16.dp)),
		content = content,
	)
}
