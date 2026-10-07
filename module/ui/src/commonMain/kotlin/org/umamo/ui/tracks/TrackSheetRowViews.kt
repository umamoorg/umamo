package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.AtPointPositionProvider
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.button.DisclosureChevron
import org.umamo.ui.kit.menu.Menu
import org.umamo.ui.kit.menu.MenuItem
import org.umamo.ui.kit.menu.contextMenuGesture
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.drawIcon

/** Indent per nesting level in the label column. */
private val INDENT_PER_DEPTH: Dp = 12.dp

/** The chevron / icon slots in the label column. */
private val SLOT_WIDTH: Dp = 16.dp

/**
 * One display line: its label cell, the column separator, and its track lane.
 *
 * @param TrackRowLine line The row and what its tree position implies.
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Dp labelColumnWidth The label column's width.
 * @param TrackRowDecor decor The row's icon.
 * @param Function? onToggleExpanded Invoked when the chevron is clicked.
 * @param Function? onMarkClick Invoked when a click lands on a mark, with whether Shift was held.
 * @param Function? onTrackScrub Invoked as an empty-track drag moves, with the gesture's Shift state.
 * @param Function? onTrackScrubEnd Invoked when an empty-track drag is released.
 * @param Function? onMarkDrag Invoked on every move of a mark drag.
 * @param Function? onMarkDragEnd Invoked when a mark drag is released.
 * @param Function selectedMarkDragDelta The in-flight group-drag offset drawn on selected marks; null for none.
 * @param Function? laneMenuItems Builds the lane's context-menu items.
 * @param Function? labelMenuItems Builds the label cell's context-menu items.
 * @param Function? onLaneHover Reports the live hit under the pointer, or null on exit.
 * @param Function? onLaneBounds Reports the lane's window bounds.
 * @param Dp markRadius Half-extent of a drawn mark, and the track region's end inset.
 */
@Composable
internal fun TrackSheetRow(
	line: TrackRowLine,
	axis: TrackAxis,
	playhead: Float?,
	labelColumnWidth: Dp,
	decor: TrackRowDecor,
	onToggleExpanded: ((TrackRow) -> Unit)?,
	onMarkClick: ((TrackRow, TrackKeyMark, Boolean) -> Unit)?,
	onTrackScrub: ((TrackRow, Float, Boolean) -> Unit)?,
	onTrackScrubEnd: ((TrackRow, Float) -> Unit)?,
	onMarkDrag: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	selectedMarkDragDelta: () -> Float?,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)?,
	labelMenuItems: ((TrackRow) -> List<MenuItem>)?,
	onLaneHover: ((TrackRow, TrackLaneHit?) -> Unit)?,
	onLaneBounds: ((TrackRow, Rect) -> Unit)?,
	markRadius: Dp,
) {
	val colors = LocalUmamoColors.current
	val toneBackground = toneBackgroundOf(line.row.tone)
	// A collapsed GROUP summarizes its whole subtree, so folding a rig away still shows where its keys are.
	// Expanded, it shows only its own marks - the children are on screen carrying theirs.  A leaf is
	// neither: it shows its own marks, unfiltered and at their own ordinals.  (Routing a leaf through the
	// summary too would drop any non-editable mark it carries and renumber the rest to a summary ordinal -
	// wrong for a track that owns its keys directly rather than standing in for a whole subtree's.)
	val marks =
		remember(line.row, line.expandable, line.expanded) {
			if (line.expandable && !line.expanded) summarizedMarks(line.row) else line.row.marks
		}
	Row(modifier = Modifier.fillMaxWidth().height(TRACK_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
		TrackRowLabel(
			line = line,
			decor = decor,
			width = labelColumnWidth,
			background = toneBackground,
			onToggleExpanded = onToggleExpanded,
			menuItems = labelMenuItems,
		)
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
		TrackLane(
			row = line.row,
			marks = marks,
			axis = axis,
			playhead = playhead,
			background = toneBackground,
			modifier = Modifier.weight(1f),
			onMarkClick = onMarkClick,
			onTrackScrub = onTrackScrub,
			onTrackScrubEnd = onTrackScrubEnd,
			onMarkDrag = onMarkDrag,
			onMarkDragEnd = onMarkDragEnd,
			selectedMarkDragDelta = selectedMarkDragDelta,
			laneMenuItems = laneMenuItems,
			onLaneHover = onLaneHover,
			onLaneBounds = onLaneBounds,
			markRadius = markRadius,
		)
	}
	Spacer(modifier = Modifier.fillMaxWidth().height(TRACK_ROW_GAP))
}

/**
 * A row's label cell: chevron, icon, and the right-aligned name over its type.
 *
 * Right-aligned against the column separator so names of wildly different lengths still line up where the
 * eye next travels - into the track region.
 *
 * @param TrackRowLine line The row and its tree position.
 * @param TrackRowDecor decor The row's icon.
 * @param Dp width The label column's width.
 * @param Color background The row's tone fill.
 * @param Function? onToggleExpanded Invoked when the chevron is clicked.
 * @param Function? menuItems Builds this row's label context menu; null (or an empty list) shows none.
 */
@Composable
private fun TrackRowLabel(
	line: TrackRowLine,
	decor: TrackRowDecor,
	width: Dp,
	background: Color,
	onToggleExpanded: ((TrackRow) -> Unit)?,
	menuItems: ((TrackRow) -> List<MenuItem>)? = null,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	// Only wrap a name in a tooltip when it is ACTUALLY clipped: a tooltip that repeats text already fully
	// on screen is noise, and Tooltip treats a blank label as "no tooltip".
	var nameTruncated by remember(line.row.key) { mutableStateOf(false) }
	// Built ONCE per composition, not on the pointer event: the labels are localized through
	// stringResource, which only runs while composing.  An empty list means this row has nothing to
	// offer, so no gesture is attached at all rather than a blank popup opening on right-click.
	val items = menuItems?.invoke(line.row).orEmpty()
	var menuOpen by remember(line.row.key) { mutableStateOf(false) }
	var menuOffset by remember(line.row.key) { mutableStateOf(IntOffset.Zero) }
	Row(
		modifier =
			Modifier
				.width(width)
				.fillMaxHeight()
				.background(background)
				.then(
					if (items.isEmpty()) {
						Modifier
					} else {
						Modifier.contextMenuGesture { localOffset ->
							menuOffset = localOffset
							menuOpen = true
						}
					},
				)
				.padding(start = 4.dp + INDENT_PER_DEPTH * line.depth, end = 6.dp),
		verticalAlignment = Alignment.CenterVertically,
	) {
		if (line.expandable && onToggleExpanded != null) {
			ExpandChevron(expanded = line.expanded, onClick = { onToggleExpanded(line.row) })
		} else {
			Spacer(modifier = Modifier.width(SLOT_WIDTH))
		}
		Spacer(modifier = Modifier.weight(1f))
		if (decor.icon != null) {
			Box(modifier = Modifier.size(SLOT_WIDTH), contentAlignment = Alignment.Center) {
				Canvas(modifier = Modifier.size(SLOT_WIDTH)) {
					drawIcon(decor.icon, decor.iconTint ?: colors.text)
				}
			}
			Spacer(modifier = Modifier.width(4.dp))
		}
		Tooltip(text = if (nameTruncated) line.row.label else "") {
			Column(horizontalAlignment = Alignment.End) {
				Text(
					text = line.row.label,
					style = typography.bodyMedium,
					color = colors.text,
					maxLines = 1,
					textAlign = TextAlign.End,
					overflow = TextOverflow.Ellipsis,
					onTextLayout = { result -> nameTruncated = result.hasVisualOverflow },
				)
				if (line.row.detail != null) {
					Text(
						text = line.row.detail,
						style = typography.labelSmall,
						color = colors.textMuted,
						maxLines = 1,
						textAlign = TextAlign.End,
						overflow = TextOverflow.Ellipsis,
					)
				}
			}
		}
	}
	if (menuOpen) {
		Menu(items = items, onDismissRequest = { menuOpen = false }, positionProvider = AtPointPositionProvider(menuOffset))
	}
}

/**
 * The expand / collapse chevron, sharing the kit's [DisclosureChevron] with the outliner and parameter
 * groups so the art and the accessible label stay in one place.
 *
 * @param Boolean expanded Whether the row's children are shown.
 * @param Function onClick Invoked on click.
 */
@Composable
private fun ExpandChevron(expanded: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	// NOT focusable: a row can be disposed by the very edit its chevron is next to, and a disposed focus
	// owner leaves Compose with none, killing every keyboard shortcut.
	val toggleGesture =
		Modifier.pointerInput(onClick) {
			awaitEachGesture {
				awaitFirstDown(requireUnconsumed = false).consume()
				onClick()
			}
		}
	DisclosureChevron(
		expanded = expanded,
		tint = colors.textMuted,
		modifier = toggleGesture,
		glyphSize = SLOT_WIDTH,
	)
}

/**
 * The tone-to-fill mapping, the only place a row's abstract color band becomes a real color.
 *
 * @param TrackRowTone tone The row's band.
 * @return Color The fill to paint behind that row.
 */
@Composable
private fun toneBackgroundOf(tone: TrackRowTone): Color {
	val colors = LocalUmamoColors.current
	return when (tone) {
		TrackRowTone.Group -> colors.trackRowGroup
		TrackRowTone.Primary -> colors.trackRowPrimary
		TrackRowTone.Secondary -> colors.trackRowSecondary
		TrackRowTone.Alternate -> colors.trackRowAlternate
	}
}