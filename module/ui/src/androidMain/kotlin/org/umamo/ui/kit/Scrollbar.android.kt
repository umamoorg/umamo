package org.umamo.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable

/**
 * Android actual: a no-op.  Touch scrolling has its own overscroll affordance and Foundation's draggable
 * VerticalScrollbar is desktop-only, so there is no scrollbar to draw here - the overlay stays callable
 * from shared UI without pulling a desktop API onto Android.
 *
 * Android 実装：何もしない（タッチのオーバースクロール表示があり、デスクトップ専用のバーは描かない）。
 *
 * @param ScrollState scrollState Unused on Android; present to match the shared signature.
 */
@Composable
actual fun BoxScope.VerticalScrollbarOverlay(scrollState: ScrollState) {
}

/**
 * Android actual: a no-op, for the same reason as the [ScrollState] overload (touch scrolling needs no
 * desktop-style draggable bar).
 *
 * Android 実装：何もしない（LazyColumn 版も同様）。
 *
 * @param LazyListState listState Unused on Android; present to match the shared signature.
 */
@Composable
actual fun BoxScope.VerticalScrollbarOverlay(listState: LazyListState) {
}
