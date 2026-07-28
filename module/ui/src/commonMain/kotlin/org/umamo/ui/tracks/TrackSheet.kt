package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.DraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.positionChange
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.AtPointPositionProvider
import org.umamo.ui.kit.Menu
import org.umamo.ui.kit.MenuItem
import org.umamo.ui.kit.Text
import org.umamo.ui.kit.Tooltip
import org.umamo.ui.kit.contextMenuGesture
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoCursors
import org.umamo.ui.theme.LocalUmamoIcons
import org.umamo.ui.theme.LocalUmamoTypography
import org.umamo.ui.theme.UmamoIcon
import org.umamo.ui.theme.drawIcon
import org.umamo.ui.theme.umamoPointerIcon
import kotlin.math.abs

/*
 * The track sheet widget family: a label column beside a domain-mapped track region, with a ruler and a
 * playhead.  Domain-agnostic by construction - everything it knows about the horizontal axis arrives as a
 * TrackAxis, and everything it knows about content arrives as TrackRows.
 *
 * トラックシートのウィジェット群。ラベル列＋領域マップされたトラック領域、ルーラー、再生ヘッド。
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

/** Indent per nesting level in the label column. */
private val INDENT_PER_DEPTH: Dp = 12.dp

/** Half-extent of a drawn mark, in dp. */
private val MARK_RADIUS: Dp = 4.dp

/** The chevron / icon slots in the label column. */
private val SLOT_WIDTH: Dp = 16.dp

/** The draggable width of the column separator; wider than the hairline it draws so it is easy to grab. */
private val SEPARATOR_GRAB_WIDTH: Dp = 5.dp

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
 * @param Dp labelColumnWidth The label column's current width.
 * @param Function? onLabelColumnWidthChange Receives a new width as the separator is dragged; null pins it.
 * @param Set<String> expandedKeys The keys of the rows whose children are shown.
 * @param Function? onToggleExpanded Invoked with a row whose chevron was clicked; null pins the tree.
 * @param Function decorFor The per-row icon provider.
 * @param Function formatTick Renders a ruler tick value; defaults to a trimmed decimal.
 * @param Function? onMarkClick Invoked with the row and the mark nearest a click, when one is in range.
 * @param Function? onTrackClick Invoked with the row and the clicked domain value when no mark is in range.
 * @param Function? onMarkDragEnd Invoked with the row, the dragged mark, and its released domain position.
 * @param Function? laneMenuItems Builds the context-menu items for a lane hit; null disables the menu.
 */
@Composable
fun TrackSheet(
	rows: List<TrackRow>,
	axis: TrackAxis,
	playhead: Float?,
	modifier: Modifier = Modifier,
	labelColumnWidth: Dp = TRACK_LABEL_COLUMN_DEFAULT_WIDTH,
	onLabelColumnWidthChange: ((Dp) -> Unit)? = null,
	expandedKeys: Set<String> = emptySet(),
	onToggleExpanded: ((TrackRow) -> Unit)? = null,
	decorFor: (TrackRow) -> TrackRowDecor = { TrackRowDecor() },
	formatTick: (Float) -> String = ::defaultTickLabel,
	onMarkClick: ((TrackRow, TrackKeyMark) -> Unit)? = null,
	onTrackClick: ((TrackRow, Float) -> Unit)? = null,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
) {
	val lines = remember(rows, expandedKeys) { flattenTrackRows(rows, expandedKeys) }
	Column(modifier = modifier.fillMaxWidth()) {
		TrackRuler(
			axis = axis,
			playhead = playhead,
			labelColumnWidth = labelColumnWidth,
			onLabelColumnWidthChange = onLabelColumnWidthChange,
			formatTick = formatTick,
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
					onTrackClick = onTrackClick,
					onMarkDragEnd = onMarkDragEnd,
					laneMenuItems = laneMenuItems,
				)
			}
		}
	}
}

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
 * One display line: its label cell, the column separator, and its track lane.
 *
 * @param TrackRowLine line The row and what its tree position implies.
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Dp labelColumnWidth The label column's width.
 * @param TrackRowDecor decor The row's icon.
 * @param Function? onToggleExpanded Invoked when the chevron is clicked.
 * @param Function? onMarkClick Invoked when a click lands on a mark.
 * @param Function? onTrackClick Invoked when a click lands on empty track.
 * @param Function? onMarkDragEnd Invoked when a mark drag is released.
 * @param Function? laneMenuItems Builds the lane's context-menu items.
 */
@Composable
private fun TrackSheetRow(
	line: TrackRowLine,
	axis: TrackAxis,
	playhead: Float?,
	labelColumnWidth: Dp,
	decor: TrackRowDecor,
	onToggleExpanded: ((TrackRow) -> Unit)?,
	onMarkClick: ((TrackRow, TrackKeyMark) -> Unit)?,
	onTrackClick: ((TrackRow, Float) -> Unit)?,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)?,
) {
	val colors = LocalUmamoColors.current
	val toneBackground = toneBackgroundOf(line.row.tone)
	// A collapsed group summarizes its whole subtree, so folding a rig away still shows WHERE its keys
	// are.  Expanded, it shows only its own marks - the children are on screen carrying theirs.
	val marks = remember(line.row, line.expanded) { if (line.expanded) line.row.marks else summarizedMarks(line.row) }
	Row(modifier = Modifier.fillMaxWidth().height(TRACK_ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
		TrackRowLabel(
			line = line,
			decor = decor,
			width = labelColumnWidth,
			background = toneBackground,
			onToggleExpanded = onToggleExpanded,
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
			onTrackClick = onTrackClick,
			onMarkDragEnd = onMarkDragEnd,
			laneMenuItems = laneMenuItems,
		)
	}
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
 */
@Composable
private fun TrackRowLabel(
	line: TrackRowLine,
	decor: TrackRowDecor,
	width: Dp,
	background: Color,
	onToggleExpanded: ((TrackRow) -> Unit)?,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	// Only wrap a name in a tooltip when it is ACTUALLY clipped: a tooltip that repeats text already fully
	// on screen is noise, and Tooltip treats a blank label as "no tooltip".
	var nameTruncated by remember(line.row.key) { mutableStateOf(false) }
	Row(
		modifier =
			Modifier
				.width(width)
				.fillMaxHeight()
				.background(background)
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
}

/**
 * The expand / collapse chevron, reusing the same right / down pair the outliner and parameter groups use.
 *
 * @param Boolean expanded Whether the row's children are shown.
 * @param Function onClick Invoked on click.
 */
@Composable
private fun ExpandChevron(expanded: Boolean, onClick: () -> Unit) {
	val colors = LocalUmamoColors.current
	val icons = LocalUmamoIcons
	Box(
		modifier =
			Modifier
				.size(SLOT_WIDTH)
				// NOT focusable: a row can be disposed by the very edit its chevron is next to, and a
				// disposed focus owner leaves Compose with none, killing every keyboard shortcut.
				.pointerInput(onClick) {
					awaitEachGesture {
						awaitFirstDown(requireUnconsumed = false).consume()
						onClick()
					}
				},
		contentAlignment = Alignment.Center,
	) {
		Canvas(modifier = Modifier.size(SLOT_WIDTH)) {
			drawIcon(if (expanded) icons.chevronDown else icons.chevronRight, colors.textMuted)
		}
	}
}

/**
 * The ruler strip: evenly spaced domain ticks above the track lanes, with the playhead and the draggable
 * column separator.
 *
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Dp labelColumnWidth The label column's width.
 * @param Function? onLabelColumnWidthChange Receives a new width as the separator is dragged.
 * @param Function formatTick Renders a tick value.
 */
@Composable
private fun TrackRuler(
	axis: TrackAxis,
	playhead: Float?,
	labelColumnWidth: Dp,
	onLabelColumnWidthChange: ((Dp) -> Unit)?,
	formatTick: (Float) -> String,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Row(modifier = Modifier.fillMaxWidth().height(TRACK_RULER_HEIGHT).background(colors.headerBackground)) {
		Box(modifier = Modifier.width(labelColumnWidth))
		ColumnSeparator(labelColumnWidth = labelColumnWidth, onLabelColumnWidthChange = onLabelColumnWidthChange)
		Box(modifier = Modifier.weight(1f).fillMaxSize()) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val inset = MARK_RADIUS.toPx()
				for (tick in axis.ticks()) {
					val x = laneX(axis.fractionOf(tick), size.width, inset)
					drawLine(colors.panelBorder, Offset(x, size.height * 0.5f), Offset(x, size.height), strokeWidth = 1f)
				}
				playhead?.let { value -> drawPlayhead(value, axis, colors.accent, inset) }
			}
			// Tick labels ride above the canvas so they are not clipped by the lane below.
			for (tick in axis.ticks()) {
				Text(
					text = formatTick(tick),
					style = typography.labelSmall,
					color = colors.textMuted,
					maxLines = 1,
					modifier = Modifier.padding(start = 2.dp).offsetByFraction(axis.fractionOf(tick)),
				)
			}
		}
	}
}

/**
 * The hairline between the label column and the track region, draggable to resize the two.
 *
 * The same affordance the area splitters use, at panel scale: a wider invisible grab band around a thin
 * drawn line, with the horizontal-resize cursor on hover.
 *
 * @param Dp labelColumnWidth The current width, which the drag is applied to.
 * @param Function? onLabelColumnWidthChange Receives the new width; null draws a plain, fixed hairline.
 */
@Composable
private fun ColumnSeparator(labelColumnWidth: Dp, onLabelColumnWidthChange: ((Dp) -> Unit)?) {
	val colors = LocalUmamoColors.current
	if (onLabelColumnWidthChange == null) {
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
		return
	}
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
	Box(
		modifier =
			Modifier
				.width(SEPARATOR_GRAB_WIDTH)
				.fillMaxHeight()
				.pointerHoverIcon(resizeCursor)
				.draggable(state = dragState, orientation = Orientation.Horizontal),
		contentAlignment = Alignment.Center,
	) {
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
	}
}

/**
 * One row's track lane: its marks positioned along the axis, over the playhead.
 *
 * @param TrackRow row The row the lane belongs to.
 * @param List<TrackKeyMark> marks The marks to draw (a collapsed group draws its subtree's).
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Color background The row's tone fill.
 * @param Modifier modifier The layout modifier.
 * @param Function? onMarkClick Invoked when a click lands on a mark.
 * @param Function? onTrackClick Invoked when a click lands on empty track.
 * @param Function? onMarkDragEnd Invoked when a mark drag is released.
 * @param Function? laneMenuItems Builds the lane's context-menu items.
 */
@Composable
private fun TrackLane(
	row: TrackRow,
	marks: List<TrackKeyMark>,
	axis: TrackAxis,
	playhead: Float?,
	background: Color,
	modifier: Modifier = Modifier,
	onMarkClick: ((TrackRow, TrackKeyMark) -> Unit)?,
	onTrackClick: ((TrackRow, Float) -> Unit)?,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
) {
	val colors = LocalUmamoColors.current
	val density = LocalDensity.current
	// The in-flight drag position, so the mark follows the pointer before the model is touched. The move is
	// committed on release: a per-frame commit would push an undo step for every pixel of the drag.
	var draggingMark by remember(row.key) { mutableStateOf<TrackKeyMark?>(null) }
	var dragDomainValue by remember(row.key) { mutableStateOf(0f) }
	// The context menu's items depend on WHERE it was opened (over a key, or over empty track), so the hit
	// is resolved at request time and the items are built from it - a fixed ContextMenuArea, whose items
	// are chosen at composition, cannot express that.
	var menuHit by remember(row.key) { mutableStateOf<TrackLaneHit?>(null) }
	var menuAnchor by remember(row.key) { mutableStateOf(IntOffset.Zero) }
	// The lane's measured width, so the pixel->domain mapping is available OUTSIDE a draw or pointer scope
	// (the context-menu gesture reports a raw offset and has neither).
	var laneWidth by remember(row.key) { mutableStateOf(0) }
	val touchSlop = LocalViewConfiguration.current.touchSlop
	val markRadius = with(density) { MARK_RADIUS.toPx() }
	Box(modifier = modifier.fillMaxHeight().background(background)) {
		Canvas(
			modifier =
				Modifier
					.fillMaxSize()
					.onSizeChanged { measured -> laneWidth = measured.width }
					.then(
						if (laneMenuItems == null) {
							Modifier
						} else {
							Modifier.contextMenuGesture { localOffset ->
								menuAnchor = localOffset
								val value = domainAt(localOffset.x.toFloat(), axis, laneWidth, markRadius)
								val tolerance = pickTolerance(axis, laneWidth, markRadius)
								menuHit = TrackLaneHit(row, value, nearestMark(marks, value, tolerance))
							}
						},
					)
					// ONE gesture handler for tap AND drag. Two separate pointerInput blocks raced: the drag
					// detector consumed the down before the tap detector saw it, so clicking a mark did nothing
					// and neither selection nor dragging worked. Deciding between them from a single stream is
					// the only way they cannot fight.
					.pointerInput(row.key, marks, axis, onMarkClick, onTrackClick, onMarkDragEnd) {
						if (onMarkClick == null && onTrackClick == null && onMarkDragEnd == null) {
							return@pointerInput
						}
						awaitEachGesture {
							val down = awaitFirstDown(requireUnconsumed = false)
							// A secondary (right) press belongs to the context menu.  Without this it would
							// also run the tap path, so opening the menu over empty track would clear the
							// selection and opening it over a key would scrub the pose onto it.
							if (currentEvent.buttons.isSecondaryPressed) {
								return@awaitEachGesture
							}
							val pressedValue = domainAt(down.position.x, axis, size.width, markRadius)
							val hitMark = nearestMark(marks, pressedValue, pickTolerance(axis, size.width, markRadius))
							var dragging = false
							var releaseValue = pressedValue
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
								if (!change.pressed) {
									break
								}
								if (change.positionChange().x != 0f || change.positionChange().y != 0f) {
									if (!dragging && abs(change.position.x - down.position.x) > touchSlop && hitMark != null) {
										dragging = true
										draggingMark = hitMark
									}
									if (dragging) {
										releaseValue = domainAt(change.position.x, axis, size.width, markRadius)
										dragDomainValue = releaseValue
										change.consume()
									}
								}
							}
							when {
								dragging && hitMark != null -> onMarkDragEnd?.invoke(row, hitMark, releaseValue)
								hitMark != null -> onMarkClick?.invoke(row, hitMark)
								else -> onTrackClick?.invoke(row, pressedValue)
							}
							draggingMark = null
						}
					},
		) {
			val inset = MARK_RADIUS.toPx()
			// A hairline baseline makes an empty track legible as a track rather than as blank panel.
			drawLine(
				colors.panelBorder,
				Offset(0f, size.height * 0.5f),
				Offset(size.width, size.height * 0.5f),
				strokeWidth = 1f,
			)
			playhead?.let { value -> drawPlayhead(value, axis, colors.accent, inset) }
			for (mark in marks) {
				// A mark being dragged draws at the pointer, not at its stored position, so the gesture reads as
				// direct manipulation rather than as a jump on release.
				val drawnPosition = if (mark == draggingMark) dragDomainValue else mark.position
				val x = laneX(axis.fractionOf(drawnPosition), size.width, inset)
				val fill = if (mark.selected) colors.accent else colors.controlGlyph
				drawMark(mark.shape, Offset(x, size.height * 0.5f), inset, fill, colors.panelBackground)
			}
		}
		val hit = menuHit
		if (laneMenuItems != null && hit != null) {
			Menu(
				items = laneMenuItems(hit),
				onDismissRequest = { menuHit = null },
				positionProvider = AtPointPositionProvider(menuAnchor),
				focusable = true,
			)
		}
	}
}

/**
 * Maps a fraction across the lane to a pixel x, inset by [markRadius] at both ends.
 *
 * Without the inset a mark at either end of the domain draws half outside the lane and reads as clipped -
 * which is exactly what a key at a parameter's min or max is, and every axis has two of those.
 *
 * @param Float fraction The 0..1 position across the domain.
 * @param Float laneWidth The lane's pixel width.
 * @param Float markRadius The half-extent of a mark, in pixels.
 * @return Float The pixel x to draw at.
 */
private fun laneX(fraction: Float, laneWidth: Float, markRadius: Float): Float {
	val usable = laneWidth - markRadius * 2f
	return if (usable <= 0f) laneWidth * 0.5f else markRadius + fraction * usable
}

/**
 * The inverse of [laneX]: the domain value under a pixel x.
 *
 * @param Float x The pixel x within the lane.
 * @param TrackAxis axis The domain.
 * @param Int laneWidth The lane's pixel width.
 * @param Float markRadius The half-extent of a mark, in pixels.
 * @return Float The domain value there.
 */
private fun domainAt(x: Float, axis: TrackAxis, laneWidth: Int, markRadius: Float): Float {
	val usable = laneWidth - markRadius * 2f
	return if (usable <= 0f) axis.valueAt(0.5f) else axis.valueAt((x - markRadius) / usable)
}

/**
 * The pick radius for a mark, in DOMAIN units, so it stays a constant number of pixels however wide the
 * panel is or however large the domain is.
 *
 * @param TrackAxis axis The domain.
 * @param Int laneWidth The lane's pixel width.
 * @param Float markRadius The half-extent of a mark, in pixels.
 * @return Float The tolerance, in domain units.
 */
private fun pickTolerance(axis: TrackAxis, laneWidth: Int, markRadius: Float): Float {
	val usable = laneWidth - markRadius * 2f
	return if (usable <= 0f) 0f else abs(axis.span) * (markRadius * 1.5f / usable)
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

/** Draws the playhead as a full-height vertical line at [value]'s position. */
private fun DrawScope.drawPlayhead(value: Float, axis: TrackAxis, color: Color, inset: Float) {
	val x = laneX(axis.fractionOf(value), size.width, inset)
	drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
}

/**
 * Draws one mark centered on [center].
 *
 * Every shape is filled then outlined in the panel color, so marks that land on top of each other (two
 * channels keyed at the same value) stay countable instead of merging into one blob.
 */
private fun DrawScope.drawMark(shape: TrackKeyShape, center: Offset, radius: Float, fill: Color, outline: Color) {
	when (shape) {
		TrackKeyShape.Circle -> {
			drawCircle(fill, radius, center)
			drawCircle(outline, radius, center, style = Stroke(width = 1f))
		}

		TrackKeyShape.Square -> {
			val topLeft = Offset(center.x - radius, center.y - radius)
			val size = Size(radius * 2, radius * 2)
			drawRect(fill, topLeft, size)
			drawRect(outline, topLeft, size, style = Stroke(width = 1f))
		}

		TrackKeyShape.Diamond -> {
			val path =
				Path().apply {
					moveTo(center.x, center.y - radius)
					lineTo(center.x + radius, center.y)
					lineTo(center.x, center.y + radius)
					lineTo(center.x - radius, center.y)
					close()
				}
			drawPath(path, fill)
			drawPath(path, outline, style = Stroke(width = 1f))
		}
	}
}

/** Positions a composable at [fraction] across its parent's width. */
private fun Modifier.offsetByFraction(fraction: Float): Modifier =
	this.layout { measurable, constraints ->
		val placeable = measurable.measure(constraints)
		layout(placeable.width, placeable.height) {
			placeable.placeRelative((constraints.maxWidth * fraction).toInt(), 0)
		}
	}

/**
 * A tick label with a trailing ".0" trimmed, so an integral domain rules 10 / 20 rather than 10.0 / 20.0.
 *
 * @param Float value The tick value.
 * @return String The label.
 */
fun defaultTickLabel(value: Float): String {
	val rounded = (value * 100f).toInt() / 100f
	return if (rounded == rounded.toInt().toFloat()) rounded.toInt().toString() else rounded.toString()
}
