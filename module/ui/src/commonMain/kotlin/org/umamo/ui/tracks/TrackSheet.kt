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
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isShiftPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
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
import org.umamo.ui.kit.SCROLLBAR_CORNER_RADIUS
import org.umamo.ui.kit.SCROLLBAR_MIN_THUMB
import org.umamo.ui.kit.SCROLLBAR_THICKNESS
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

/**
 * The gap left between rows, showing the track region through.
 *
 * Small on purpose: enough to break the tone bands apart so the eye can follow one band left to right
 * across a dense sheet, not enough to read as spacing between unrelated things.
 */
val TRACK_ROW_GAP: Dp = 2.dp

/** Indent per nesting level in the label column. */
private val INDENT_PER_DEPTH: Dp = 12.dp

/**
 * Half-extent of a drawn mark, in dp - the default for [TrackSheet]'s markRadius.
 *
 * It is also the amount the track region is inset at both ends, so a key at either end of the domain draws
 * fully inside the lane instead of half outside it.  Every axis has a key at each end, so without the
 * inset every track loses two marks to the panel edge.
 */
val TRACK_MARK_RADIUS: Dp = 6.dp

/** The chevron / icon slots in the label column. */
private val SLOT_WIDTH: Dp = 16.dp

/** The draggable width of the column separator; wider than the hairline it draws so it is easy to grab. */
private val SEPARATOR_GRAB_WIDTH: Dp = 5.dp

/** How much one wheel notch zooms; a fixed ratio, so a notch feels the same however far in you are. */
private const val ZOOM_STEP: Float = 0.85f

/** How far one wheel notch pans, as a fraction of the VISIBLE width. */
private const val PAN_STEP: Float = 0.15f

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
 * @param Function? onMarkClick Invoked with the row and the mark nearest a click, when one is in range.
 * @param Function? onTrackScrub Invoked on press and on every move of a drag that missed every mark.
 * @param Function? onTrackScrubEnd Invoked when such a drag is released, to commit the value it landed on.
 * @param Function? onMarkDragEnd Invoked with the row, the dragged mark, and its released domain position.
 * @param Function? laneMenuItems Builds the context-menu items for a lane hit; null disables the menu.
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
	onMarkClick: ((TrackRow, TrackKeyMark) -> Unit)? = null,
	onTrackScrub: ((TrackRow, Float) -> Unit)? = null,
	onTrackScrubEnd: ((TrackRow, Float) -> Unit)? = null,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
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
					onMarkDragEnd = onMarkDragEnd,
					laneMenuItems = laneMenuItems,
					markRadius = markRadius,
				)
			}
		}
	}
}

/**
 * The zoom / pan gestures for a whole track sheet: Ctrl+wheel zooms about the pointer, Shift+wheel pans,
 * and a tertiary (middle) drag pans.
 *
 * Attached by the sheet's owner across its whole scrolling region, not per lane, because the window is
 * shared by every track and section - the zoom belongs to the view, not to a row.
 *
 * PLAIN wheel is deliberately left alone: the sheet scrolls vertically through hundreds of tracks, and
 * taking the unmodified wheel for zoom would cost the more common gesture to serve the rarer one.  This
 * matches every NLE that has real vertical overflow.
 *
 * @param TrackWindow window The current window.
 * @param Function onWindowChange Receives the new window.
 * @param Dp labelColumnWidth The label column's width, so a gesture over the labels is ignored.
 * @param Function onPanningChange Reports whether a pan is in flight, so the caller can hold the cursor.
 * @return Modifier The modifier carrying both gestures.
 */
@Composable
fun Modifier.trackWindowGestures(
	window: TrackWindow,
	onWindowChange: (TrackWindow) -> Unit,
	labelColumnWidth: Dp,
	onPanningChange: (Boolean) -> Unit = {},
): Modifier {
	val density = LocalDensity.current
	val latestWindow by rememberUpdatedState(window)
	val latestCallback by rememberUpdatedState(onWindowChange)
	val latestPanning by rememberUpdatedState(onPanningChange)
	val labelWidthPx = with(density) { labelColumnWidth.toPx() }
	return this
		.pointerInput(labelWidthPx) {
			awaitPointerEventScope {
				while (true) {
					// The INITIAL pass, deliberately: the Main pass runs child-first, so the vertical scroll
					// container has already claimed the wheel by the time it reaches here - which is why
					// Ctrl+wheel used to zoom and scroll at once.
					val event = awaitPointerEvent(PointerEventPass.Initial)
					if (event.type != PointerEventType.Scroll) {
						continue
					}
					val change = event.changes.firstOrNull() ?: continue
					val laneWidth = size.width - labelWidthPx
					if (change.position.x < labelWidthPx || laneWidth <= 0f) {
						continue
					}
					val horizontal = change.scrollDelta.x
					if (horizontal != 0f) {
						latestCallback(latestWindow.pannedBy(horizontal * latestWindow.span * PAN_STEP))
						change.consume()
						continue
					}
					val scroll = change.scrollDelta.y
					if (scroll == 0f) {
						continue
					}
					when {
						event.keyboardModifiers.isCtrlPressed -> {
							val focus = ((change.position.x - labelWidthPx) / laneWidth).coerceIn(0f, 1f)
							// A notch is a fixed RATIO, so zooming feels the same however far in you are.
							latestCallback(latestWindow.zoomedBy(if (scroll > 0f) 1f / ZOOM_STEP else ZOOM_STEP, focus))
							change.consume()
						}

						event.keyboardModifiers.isShiftPressed -> {
							latestCallback(latestWindow.pannedBy(scroll * latestWindow.span * PAN_STEP))
							change.consume()
						}

						// Anything else is the vertical scroll, which belongs to the scroll container.
						else -> Unit
					}
				}
			}
		}
		.pointerInput(labelWidthPx) {
			awaitPointerEventScope {
				while (true) {
					// The raw stream on the INITIAL pass, not awaitFirstDown.  Two reasons, both load-bearing:
					// Initial is the only pass that beats the lanes and the scroll container, which are
					// children and would otherwise claim a middle press first; and awaitFirstDown does not
					// resolve a down on that pass at all - it waits forever - which is why this silently did
					// nothing.  contextMenuGesture watches the raw stream for the same reason.
					val press = awaitPointerEvent(PointerEventPass.Initial)
					if (press.type != PointerEventType.Press || !press.buttons.isTertiaryPressed) {
						continue
					}
					val down = press.changes.first()
					if (down.position.x < labelWidthPx) {
						continue
					}
					down.consume()
					latestPanning(true)
					val laneWidth = size.width - labelWidthPx
					var lastX = down.position.x
					while (true) {
						val event = awaitPointerEvent(PointerEventPass.Initial)
						val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
						if (!change.pressed) {
							change.consume()
							break
						}
						if (laneWidth > 0f && change.position.x != lastX) {
							// Dragging right moves the CONTENT right, so the window moves left - the direct
							// manipulation a hand tool has, not a scrollbar's inverted sense.
							val moved = (change.position.x - lastX) / laneWidth
							latestCallback(latestWindow.pannedBy(-moved * latestWindow.span))
							lastX = change.position.x
						}
						change.consume()
					}
					latestPanning(false)
				}
			}
		}
}

/**
 * The horizontal window indicator under a track sheet: a thumb showing which slice of the domain is on
 * screen, draggable to pan.
 *
 * Not a scrollbar over a scroll container - there is no scrolling content to attach to - so it reads the
 * window directly and reports a new one.  Hidden when the whole domain is framed, since a full-width thumb
 * says nothing.
 *
 * @param TrackWindow window The current window.
 * @param Function onWindowChange Receives the new window as the thumb is dragged.
 * @param Dp labelColumnWidth The label column's width, so the track starts under the tracks.
 * @param Modifier modifier The layout modifier.
 */
@Composable
fun TrackWindowScrollbar(
	window: TrackWindow,
	onWindowChange: (TrackWindow) -> Unit,
	labelColumnWidth: Dp,
	modifier: Modifier = Modifier,
) {
	val colors = LocalUmamoColors.current
	if (window.span >= 1f) {
		return
	}
	val latestWindow by rememberUpdatedState(window)
	val latestCallback by rememberUpdatedState(onWindowChange)
	var trackWidth by remember { mutableStateOf(0) }
	val dragState =
		remember {
			DraggableState { deltaPx ->
				if (trackWidth > 0) {
					latestCallback(latestWindow.pannedBy(deltaPx / trackWidth))
				}
			}
		}
	val minimumThumbWidth = with(LocalDensity.current) { SCROLLBAR_MIN_THUMB.toPx() }
	val cornerRadius = with(LocalDensity.current) { SCROLLBAR_CORNER_RADIUS.toPx() }
	Row(modifier = modifier.fillMaxWidth().height(SCROLLBAR_THICKNESS)) {
		Spacer(modifier = Modifier.width(labelColumnWidth + 1.dp))
		Box(
			modifier =
				Modifier
					.weight(1f)
					.fillMaxHeight()
					.onSizeChanged { measured -> trackWidth = measured.width }
					.draggable(state = dragState, orientation = Orientation.Horizontal),
		) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val thumbWidth = maxOf(size.width * latestWindow.span, minimumThumbWidth)
				val thumbLeft = (size.width * latestWindow.start).coerceIn(0f, maxOf(0f, size.width - thumbWidth))
				// Full thickness, matching the panel scrollbars: a bar is a pointer target first, and one
				// inset thinner than its neighbours is harder to hit for no reason the user can see.
				drawRoundRect(
					color = colors.scrollbarThumb,
					topLeft = Offset(thumbLeft, 0f),
					size = Size(thumbWidth, size.height),
					cornerRadius = CornerRadius(cornerRadius),
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
 * @param Function? onTrackScrub Invoked as an empty-track drag moves.
 * @param Function? onTrackScrubEnd Invoked when an empty-track drag is released.
 * @param Function? onMarkDragEnd Invoked when a mark drag is released.
 * @param Function? laneMenuItems Builds the lane's context-menu items.
 * @param Dp markRadius Half-extent of a drawn mark, and the track region's end inset.
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
	onTrackScrub: ((TrackRow, Float) -> Unit)?,
	onTrackScrubEnd: ((TrackRow, Float) -> Unit)?,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)?,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)?,
	markRadius: Dp,
) {
	val colors = LocalUmamoColors.current
	val toneBackground = toneBackgroundOf(line.row.tone)
	// A collapsed GROUP summarizes its whole subtree, so folding a rig away still shows where its keys are.
	// Expanded, it shows only its own marks - the children are on screen carrying theirs.  A leaf is
	// neither: it shows its own marks, which stay editable.  (Routing leaves through the summary too was
	// harmless while the summary only deduplicated; it stopped being harmless once a summary mark started
	// declaring itself inert.)
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
			onMarkDragEnd = onMarkDragEnd,
			laneMenuItems = laneMenuItems,
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
 * @param Function formatTick Renders a tick value.
 * @param Dp markRadius The track region's end inset, so ticks line up with the marks below.
 */
@Composable
private fun TrackRuler(
	axis: TrackAxis,
	playhead: Float?,
	labelColumnWidth: Dp,
	formatTick: (Float) -> String,
	markRadius: Dp,
) {
	val colors = LocalUmamoColors.current
	val typography = LocalUmamoTypography.current
	Row(modifier = Modifier.fillMaxWidth().height(TRACK_RULER_HEIGHT).background(colors.headerBackground)) {
		Box(modifier = Modifier.width(labelColumnWidth))
		Box(modifier = Modifier.width(1.dp).fillMaxHeight().background(colors.divider))
		Box(modifier = Modifier.weight(1f).fillMaxSize().clipToBounds()) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				val inset = markRadius.toPx()
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
 * @param Function? onTrackScrub Invoked as an empty-track drag moves.
 * @param Function? onTrackScrubEnd Invoked when an empty-track drag is released.
 * @param Function? onMarkDragEnd Invoked when a mark drag is released.
 * @param Function? laneMenuItems Builds the lane's context-menu items.
 * @param Dp markRadius Half-extent of a drawn mark, and the track region's end inset.
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
	onTrackScrub: ((TrackRow, Float) -> Unit)?,
	onTrackScrubEnd: ((TrackRow, Float) -> Unit)?,
	onMarkDragEnd: ((TrackRow, TrackKeyMark, Float) -> Unit)? = null,
	laneMenuItems: ((TrackLaneHit) -> List<MenuItem>)? = null,
	markRadius: Dp,
) {
	val colors = LocalUmamoColors.current
	val density = LocalDensity.current
	// The in-flight drag position, so the mark follows the pointer before the model is touched. The move is
	// committed on release: a per-frame commit would push an undo step for every pixel of the drag.
	var draggingMark by remember(row.key) { mutableStateOf<TrackKeyMark?>(null) }
	var dragDomainValue by remember(row.key) { mutableStateOf(0f) }
	// The window the in-flight drag may move within - its neighbours, inside the axis.  Latched when the
	// drag starts so a mark cannot escape by being dragged past a neighbour it has already reached.
	var dragBounds by remember(row.key) { mutableStateOf(0f..0f) }
	// The context menu's items depend on WHERE it was opened (over a key, or over empty track), so the
	// gesture records only the anchor and the hit is resolved below, at composition time.  Resolving it
	// inside the gesture lambda would read whatever `marks` and `axis` were when that lambda was created -
	// contextMenuGesture keys its pointerInput on Unit, so the first lambda is the one that runs forever.
	var menuOpen by remember(row.key) { mutableStateOf(false) }
	var menuAnchor by remember(row.key) { mutableStateOf(IntOffset.Zero) }
	// The lane's measured width, so the pixel->domain mapping is available OUTSIDE a draw or pointer scope
	// (the context-menu gesture reports a raw offset and has neither).
	var laneWidth by remember(row.key) { mutableStateOf(0) }
	val touchSlop = LocalViewConfiguration.current.touchSlop
	val markRadiusPx = with(density) { markRadius.toPx() }
	Box(modifier = modifier.fillMaxHeight().background(background).clipToBounds()) {
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
								menuOpen = true
							}
						},
					)
					// ONE gesture handler for tap AND drag. Two separate pointerInput blocks raced: the drag
					// detector consumed the down before the tap detector saw it, so clicking a mark did nothing
					// and neither selection nor dragging worked. Deciding between them from a single stream is
					// the only way they cannot fight.
					.pointerInput(row.key, marks, axis, markRadiusPx, onMarkClick, onTrackScrub, onMarkDragEnd) {
						if (onMarkClick == null && onTrackScrub == null && onMarkDragEnd == null) {
							return@pointerInput
						}
						awaitEachGesture {
							val down = awaitFirstDown(requireUnconsumed = false)
							// A secondary (right) press belongs to the context menu.  Without this it would
							// also run the tap path, so opening the menu over empty track would clear the
							// selection and opening it over a key would scrub the pose onto it.
							// A secondary press belongs to the context menu and a tertiary one to the sheet's
							// pan; either falling through here would scrub or select on the way past.
							if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
								return@awaitEachGesture
							}
							val pressedValue = domainAt(down.position.x, axis, size.width, markRadiusPx)
							// Summary marks are drawn but not addressable, so a press on one falls through
							// to the scrub path rather than starting a drag that could only guess.
							val hitMark =
								nearestMark(
									marks.filter { mark -> mark.editable },
									pressedValue,
									pickTolerance(axis, size.width, markRadiusPx),
								)
							var dragging = false
							var releaseValue = pressedValue
							// A press on empty track scrubs immediately, so the playhead lands under the
							// pointer on the way down rather than only on release - the affordance a
							// timeline ruler has, applied across the whole track region.
							if (hitMark == null) {
								onTrackScrub?.invoke(row, pressedValue)
							}
							while (true) {
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
								if (!change.pressed) {
									break
								}
								if (change.positionChange().x != 0f || change.positionChange().y != 0f) {
									if (!dragging && abs(change.position.x - down.position.x) > touchSlop) {
										dragging = true
										if (hitMark != null) {
											draggingMark = hitMark
											dragBounds = dragBoundsOf(marks, hitMark.keyIndex, axis)
										}
									}
									if (dragging) {
										val pointerValue = domainAt(change.position.x, axis, size.width, markRadiusPx)
										// A MARK drag is clamped as it moves rather than on release: one that
										// follows the pointer past its neighbour and then snaps back reads as a
										// rejected edit, where stopping at the wall reads as the wall being
										// there.  An empty-track scrub has no walls - it is a pose gesture.
										releaseValue =
											if (hitMark == null) pointerValue else pointerValue.coerceIn(dragBounds)
										if (hitMark == null) {
											onTrackScrub?.invoke(row, releaseValue)
										} else {
											dragDomainValue = releaseValue
										}
										change.consume()
									}
								}
							}
							when {
								hitMark != null && dragging -> onMarkDragEnd?.invoke(row, hitMark, releaseValue)
								hitMark != null -> onMarkClick?.invoke(row, hitMark)
								else -> onTrackScrubEnd?.invoke(row, releaseValue)
							}
							draggingMark = null
						}
					},
		) {
			// A hairline baseline makes an empty track legible as a track rather than as blank panel.
			drawLine(
				colors.panelBorderHover,
				Offset(0f, size.height * 0.5f),
				Offset(size.width, size.height * 0.5f),
				strokeWidth = 1f,
			)
			playhead?.let { value -> drawPlayhead(value, axis, colors.accent, markRadiusPx) }
			for (mark in marks) {
				// A mark being dragged draws at the pointer, not at its stored position, so the gesture reads as
				// direct manipulation rather than as a jump on release.
				val drawnPosition = if (mark.keyIndex == draggingMark?.keyIndex) dragDomainValue else mark.position
				val x = laneX(axis.fractionOf(drawnPosition), size.width, markRadiusPx)
				val fill =
					when {
						!mark.editable -> colors.textDisabled
						mark.selected -> colors.accent
						else -> colors.controlGlyph
					}
				drawMark(mark.shape, Offset(x, size.height * 0.5f), markRadiusPx, fill, colors.panelBackground)
			}
		}
		if (laneMenuItems != null && menuOpen && laneWidth > 0) {
			val menuValue = domainAt(menuAnchor.x.toFloat(), axis, laneWidth, markRadiusPx)
			val menuMark = nearestMark(marks, menuValue, pickTolerance(axis, laneWidth, markRadiusPx))
			Menu(
				items = laneMenuItems(TrackLaneHit(row, menuValue, menuMark)),
				onDismissRequest = { menuOpen = false },
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
