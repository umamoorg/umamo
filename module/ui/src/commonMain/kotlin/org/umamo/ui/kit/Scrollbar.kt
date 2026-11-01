package org.umamo.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The thickness every scrollbar in the editor draws at.
 *
 * Shared rather than restated per bar: a scrollbar is a pointer target before it is a decoration, and one
 * that is thinner than the others is measurably harder to hit for no reason the user can see.
 */
val SCROLLBAR_THICKNESS: Dp = 8.dp

/** The shortest a scrollbar thumb may draw, so a long scroll still leaves something to grab. */
val SCROLLBAR_MIN_THUMB: Dp = 24.dp

/** The scrollbar thumb's corner radius - a full round on [SCROLLBAR_THICKNESS]. */
val SCROLLBAR_CORNER_RADIUS: Dp = 4.dp

/**
 * A draggable vertical scrollbar pinned to the right edge of the hosting Box, shown only while the
 * [scrollState] can actually scroll (its content overflows the viewport).  Overlay sibling of the
 * scrolling content; call it after the content inside a Box so it draws on top.  Touch-driven Android
 * draws none.
 *
 * @param ScrollState scrollState The scroll state of the verticalScroll content to drive and gate on.
 */
@Composable
expect fun BoxScope.VerticalScrollbarOverlay(scrollState: ScrollState)

/**
 * A draggable vertical scrollbar pinned to the right edge of the hosting Box, shown only while the
 * [listState] can actually scroll in either direction.  Overlay sibling of a LazyColumn; call it after
 * the list inside a Box so it draws on top.  Touch-driven Android draws none.
 *
 * @param LazyListState listState The LazyColumn's state to drive and gate on.
 */
@Composable
expect fun BoxScope.VerticalScrollbarOverlay(listState: LazyListState)