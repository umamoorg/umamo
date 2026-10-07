package org.umamo.ui.tracks

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.umamoPointerIcon

/** The draggable width of the column separator; wider than the hairline it draws so it is easy to grab. */
private val SEPARATOR_GRAB_WIDTH: Dp = 5.dp

/**
 * The always-visible backdrop for a sheet region: the label column's fill beside the track region's.
 *
 * Drawn behind the scrolled content rather than per row, so the two columns read as columns even where
 * there are no rows - below the last track, and in a section that is empty.  Callers stack this under
 * their scroll container.
 *
 * @param Dp labelColumnWidth The label column's current width, so the split lines up with the rows.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun TrackSheetBackdrop(labelColumnWidth: Dp, modifier: Modifier = Modifier) {
	val colors = LocalUmamoColors.current
	Row(modifier = modifier.fillMaxSize()) {
		Box(modifier = Modifier.width(labelColumnWidth).fillMaxHeight().background(colors.panelBackground))
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
		Box(modifier = Modifier.weight(1f).fillMaxHeight().background(colors.trackRegionBackground))
	}
}

/**
 * The draggable column separator, as a full-height overlay over a whole scrolling sheet.
 *
 * An overlay rather than a per-row divider because the grab band has to run the WHOLE panel: a separator
 * you can only catch in the 20dp ruler is a target the user has to hunt for, and with several sections
 * stacked there is no single row that spans them anyway.  Callers stack this in FRONT of their scroll
 * container, at the same [labelColumnWidth] the sheets are drawn with.
 *
 * The rows still draw their own hairline at the same x, so the line is continuous where the overlay is
 * merely transparent.
 *
 * @param Dp labelColumnWidth The current width, which the drag is applied to.
 * @param Function onLabelColumnWidthChange Receives the new width, clamped to the sheet's bounds.
 * @param Modifier modifier The layout modifier.
 * @param Function onDraggingChange Reports whether a resize is in flight, so the caller can hold the cursor.
 */
@Composable
fun TrackSheetSeparatorOverlay(
	labelColumnWidth: Dp,
	onLabelColumnWidthChange: (Dp) -> Unit,
	modifier: Modifier = Modifier,
	onDraggingChange: (Boolean) -> Unit = {},
) {
	val density = LocalDensity.current
	// The callback and the width are read at DRAG time, not captured at construction: the drag state
	// outlives a recomposition, and a captured width would make every delta apply to a stale base.
	val latestWidth by rememberUpdatedState(labelColumnWidth)
	val latestCallback by rememberUpdatedState(onLabelColumnWidthChange)
	val dragState =
		remember(density) {
			DraggableState { deltaPx ->
				val delta = with(density) { deltaPx.toDp() }
				latestCallback(
					(latestWidth + delta).coerceIn(TRACK_LABEL_COLUMN_MIN_WIDTH, TRACK_LABEL_COLUMN_MAX_WIDTH),
				)
			}
		}
	val resizeCursor = umamoPointerIcon(LocalUmamoCursors.ewScroll)
	val latestDragging by rememberUpdatedState(onDraggingChange)
	Row(modifier = modifier.fillMaxSize()) {
		// A transparent spacer positions the grab band; it must not intercept anything, so the band is
		// offset back by half its width to straddle the hairline the rows draw.
		Spacer(modifier = Modifier.width(labelColumnWidth + 1.dp - SEPARATOR_GRAB_WIDTH / 2))
		Box(
			modifier =
				Modifier
					.width(SEPARATOR_GRAB_WIDTH)
					.fillMaxHeight()
					.pointerHoverIcon(resizeCursor)
					.draggable(
						state = dragState,
						orientation = Orientation.Horizontal,
						onDragStarted = { latestDragging(true) },
						onDragStopped = { latestDragging(false) },
					),
		)
	}
}