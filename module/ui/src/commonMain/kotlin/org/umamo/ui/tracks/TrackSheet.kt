package org.umamo.ui.tracks

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.runtime.Composable
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.theme.UmamoIcon

/*
 * The track sheet widget family: a label column beside a domain-mapped track region, with a ruler and a
 * playhead.  Domain-agnostic by construction - everything it knows about the horizontal axis arrives as a
 * TrackAxis, and everything it knows about content arrives as TrackRows.
 *
 * This file is the entry point: the sheet, its public constants, and the types its callbacks carry.  The
 * rest of the package, by role:
 *
 *   TrackAxis.kt                the Compose-free model: the axis, the window, rows, marks, and their algebra
 *   TrackRuler.kt               the ruler strip, its tick labels, and the default tick formatter
 *   TrackSheetRowViews.kt       one display line: the label cell, its chevron and menu, and the row's tone
 *   TrackLane.kt                one row's lane: its state and menu, and the surface the loops and draw run on
 *   TrackLanePointerInput.kt    the lane's hover loop and its tap / scrub / drag loop, over bundled callbacks
 *   TrackLaneGeometry.kt        the pixel <-> domain mapping with the mark-radius inset, and the pick radius
 *   TrackSheetDraw.kt           the lane's draw pass: baseline, playhead, and marks
 *   TrackSheetMarquee.kt        the box-select marquee an owner stacks over its scrolling sheet
 *   TrackSheetLayers.kt         the backdrop under the scroll and the column separator over it
 *   TrackWindowControls.kt      the zoom / pan gestures and the window scrollbar
 */

/** The label column's default width; wide enough for an item name plus its type at the panel's type size. */
val TRACK_LABEL_COLUMN_DEFAULT_WIDTH: Dp = 180.dp

/** How narrow and how wide the label column may be dragged. */
val TRACK_LABEL_COLUMN_MIN_WIDTH: Dp = 80.dp

/** The widest the label column may be dragged, so the track region can never be squeezed away entirely. */
val TRACK_LABEL_COLUMN_MAX_WIDTH: Dp = 420.dp

/** Row height. Two lines of text (name over its type), so taller than a single-line tree row. */
val TRACK_ROW_HEIGHT: Dp = 32.dp

/** The ruler strip's height. */
val TRACK_RULER_HEIGHT: Dp = 20.dp

/**
 * The gap left between rows, showing the track region through.
 *
 * Small on purpose: enough to break the tone bands apart so the eye can follow one band left to right
 * across a dense sheet, not enough to read as spacing between unrelated things.
 */
val TRACK_ROW_GAP: Dp = 2.dp

/**
 * Half-extent of a drawn mark, in dp - the default for [TrackSheet]'s markRadius.
 *
 * It is also the amount the track region is inset at both ends, so a key at either end of the domain draws
 * fully inside the lane instead of half outside it.  Every axis has a key at each end, so without the
 * inset every track loses two marks to the panel edge.
 */
val TRACK_MARK_RADIUS: Dp = 6.dp

/**
 * Per-row presentation the sheet cannot derive from a [TrackRow] alone.
 *
 * An icon is a UI concept, not a domain one, but WHICH icon a row gets is a domain decision - so it
 * arrives through a provider from the sheet's owner rather than sitting on the Compose-free [TrackRow].
 *
 * @property UmamoIcon? icon The glyph shown beside the row's name, or null for none.
 * @property Color? iconTint The glyph's color, or null for the row's text color.
 */
data class TrackRowDecor(
	val icon: UmamoIcon? = null,
	val iconTint: Color? = null,
)

/**
 * Where a lane click or context-menu request landed: the row, the domain value, and the mark hit if any.
 *
 * @property TrackRow row The row under the pointer.
 * @property Float value The domain value under the pointer.
 * @property TrackKeyMark? mark The mark within pick range, or null for empty track.
 */
data class TrackLaneHit(
	val row: TrackRow,
	val value: Float,
	val mark: TrackKeyMark?,
)

/**
 * A sheet of labelled tracks over one horizontal [axis], with a ruler, a playhead, and a resizable
 * label column.
 *
 * Lays its rows out eagerly rather than lazily: a sheet is usually one section of a scrolling page, and a
 * lazy list nested in an outer scroll fights it for the gesture.  Track counts are in the tens.
 *
 * @param List<TrackRow> rows The root rows; nested children appear only while their parent is expanded.
 * @param TrackAxis axis The horizontal domain the marks map onto.
 * @param Float? playhead The domain value to draw the playhead at, or null for none.
 * @param Modifier modifier The layout modifier.
 * @param Dp labelColumnWidth The label column's current width; TrackSheetSeparatorOverlay resizes it.
 * @param Set<String> expandedKeys The keys of the rows whose children are shown.
 * @param Function? onToggleExpanded Invoked with a row whose chevron was clicked; null pins the tree.
 * @param Function decorFor The per-row icon provider.
 * @param Function formatTick Renders a ruler tick value; defaults to a trimmed decimal.
 * @param Function? onMarkClick Invoked with the row, the mark nearest a click, and whether the click was
 *   ADDITIVE (Shift held) - the owner decides what additive means, since only it holds the selection.
 * @param Function? onTrackScrub Invoked on press and on every move of a drag that missed every mark, with
 *   whether Shift was held when the gesture began - so a Shift+click that MISSES a mark can decline to
 *   clear the selection the user was building.
 * @param Function? onTrackScrubEnd Invoked when such a drag is released, to commit the value it landed on.
 * @param Function? onMarkDrag Invoked with the row, the dragged mark, and its live domain position on every
 *   move of a mark drag - what lets an owner preview a whole SELECTION following the one being dragged.
 * @param Function? onMarkDragEnd Invoked with the row, the dragged mark, and its released domain position.
 * @param Function selectedMarkDragDelta A domain offset drawn on every SELECTED mark while a group drag is
 *   in flight, so the rest of the selection travels with the one under the hand instead of jumping on
 *   release.  In this axis's own units; the owner converts, since only it knows what the group is being
 *   dragged along.  NULL means no group drag is in flight - distinct from 0f, which means one IS in flight
 *   but has been clamped to a standstill, and in that case the mark under the hand must stop with the rest
 *   instead of running on and snapping back.  A LAMBDA, read inside the lane's draw: a plain value would
 *   make every pointer frame of a drag recompose the whole sheet, where a draw-scope read only redraws.
 * @param Function? laneMenuItems Builds the context-menu items for a lane hit; null disables the menu.
 * @param Function? labelMenuItems Builds the context-menu items for a row's LABEL cell; null disables it.
 *   Separate from [laneMenuItems] because the two halves of a row answer different questions - the lane is
 *   about a key at a position, the label is about the thing the row names.
 * @param Function? onLaneHover Reports the live hit under the pointer, or null on exit, for hover-aimed commands.
 * @param Function? onLaneBounds Reports each lane's window bounds, so a region gesture can resolve rows.
 * @param Dp markRadius Half-extent of a drawn mark, and the inset applied at both ends of the track region.
 */
@Composable
fun TrackSheet(
	rows: List<TrackRow>,
	axis: TrackAxis,
	playhead: Float?,
	modifier: Modifier = Modifier,
	labelColumnWidth: Dp = TRACK_LABEL_COLUMN_DEFAULT_WIDTH,
	expandedKeys: Set<String> = emptySet(),
	onToggleExpanded: ((TrackRow) -> Unit)? = null,
	decorFor: (TrackRow) -> TrackRowDecor = { TrackRowDecor() },
	formatTick: (Float) -> String = ::defaultTickLabel,
	onMarkClick: ((TrackRow, TrackKeyMark, Boolean) -> Unit)? = null,
	onTrackScrub: ((TrackRow, Float, Boolean) -> Unit)? = null,
	onTrackScrubEnd: ((TrackRow, Float) -> Unit)? = null,
	onMarkDrag: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	selectedMarkDragDelta: () -> Float? = { null },
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
	labelMenuItems: ((TrackRow) -> List<MenuItem>)? = null,
	onLaneHover: ((TrackRow, TrackLaneHit?) -> Unit)? = null,
	onLaneBounds: ((TrackRow, Rect) -> Unit)? = null,
	markRadius: Dp = TRACK_MARK_RADIUS,
) {
	val lines = remember(rows, expandedKeys) { flattenTrackRows(rows, expandedKeys) }
	Column(modifier = modifier.fillMaxWidth()) {
		TrackRuler(
			axis = axis,
			playhead = playhead,
			labelColumnWidth = labelColumnWidth,
			formatTick = formatTick,
			markRadius = markRadius,
		)
		for (line in lines) {
			key(line.row.key) {
				TrackSheetRow(
					line = line,
					axis = axis,
					playhead = playhead,
					labelColumnWidth = labelColumnWidth,
					decor = decorFor(line.row),
					onToggleExpanded = onToggleExpanded,
					onMarkClick = onMarkClick,
					onTrackScrub = onTrackScrub,
					onTrackScrubEnd = onTrackScrubEnd,
					onMarkDrag = onMarkDrag,
					onMarkDragEnd = onMarkDragEnd,
					selectedMarkDragDelta = selectedMarkDragDelta,
					laneMenuItems = laneMenuItems,
					labelMenuItems = labelMenuItems,
					onLaneHover = onLaneHover,
					onLaneBounds = onLaneBounds,
					markRadius = markRadius,
				)
			}
		}
	}
}