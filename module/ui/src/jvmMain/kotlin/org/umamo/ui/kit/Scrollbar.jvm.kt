package org.umamo.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.ScrollbarStyle
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors

/**
 * The flat, palette-keyed scrollbar style shared by every overlay below: a thin rounded thumb tinted
 * from [LocalUmamoColors].scrollbarThumb that brightens on hover.  Foundation's ScrollbarStyle (not
 * Material), so it stays within the project's no-Material design system while reusing the well-tested
 * bar widget.
 *
 * @return ScrollbarStyle The themed style for the active color scheme.
 */
@Composable
private fun umamoScrollbarStyle(): ScrollbarStyle {
	val colors = LocalUmamoColors.current
	return ScrollbarStyle(
		minimalHeight = 24.dp,
		thickness = 8.dp,
		shape = RoundedCornerShape(4.dp),
		hoverDurationMillis = 300,
		unhoverColor = colors.scrollbarThumb,
		hoverColor = colors.scrollbarThumbHover,
	)
}

/**
 * Desktop actual: Foundation's draggable VerticalScrollbar, gated on the content overflowing.
 *
 * デスクトップ実装：内容がはみ出すときのみ Foundation のスクロールバーを描画する。
 *
 * @param ScrollState scrollState The scroll state of the verticalScroll content to drive and gate on.
 */
@Composable
actual fun BoxScope.VerticalScrollbarOverlay(scrollState: ScrollState) {
	if (scrollState.maxValue > 0) {
		VerticalScrollbar(
			adapter = rememberScrollbarAdapter(scrollState),
			style = umamoScrollbarStyle(),
			modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
		)
	}
}

/**
 * Desktop actual: Foundation's draggable VerticalScrollbar for a LazyColumn, gated on it being scrollable.
 *
 * デスクトップ実装：LazyColumn がスクロール可能なときのみバーを描画する。
 *
 * @param LazyListState listState The LazyColumn's state to drive and gate on.
 */
@Composable
actual fun BoxScope.VerticalScrollbarOverlay(listState: LazyListState) {
	if (listState.canScrollForward || listState.canScrollBackward) {
		VerticalScrollbar(
			adapter = rememberScrollbarAdapter(listState),
			style = umamoScrollbarStyle(),
			modifier = Modifier.align(Alignment.CenterEnd).fillMaxHeight(),
		)
	}
}
