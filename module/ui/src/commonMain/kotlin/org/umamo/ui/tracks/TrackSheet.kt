package org.umamo.ui.tracks

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.layout
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.umamo.ui.kit.Text
import org.umamo.ui.theme.LocalUmamoColors
import org.umamo.ui.theme.LocalUmamoTypography
import kotlin.math.abs

/*
 * The track sheet widget family: a label column beside a domain-mapped track region, with a ruler and a
 * playhead.  Domain-agnostic by construction - everything it knows about the horizontal axis arrives as a
 * TrackAxis, and everything it knows about content arrives as TrackRows.
 *
 * トラックシートのウィジェット群。ラベル列＋領域マップされたトラック領域、ルーラー、再生ヘッド。
 */

/** The label column's width; wide enough for an item name plus a channel name at the panel's type size. */
private val LABEL_COLUMN_WIDTH: Dp = 168.dp

/** Row height, matching the outliner's so the two read as the same density when docked side by side. */
private val ROW_HEIGHT: Dp = 22.dp

/** The ruler strip's height. */
private val RULER_HEIGHT: Dp = 20.dp

/** Indent per nesting level in the label column. */
private val INDENT_PER_DEPTH: Dp = 12.dp

/** Half-extent of a drawn mark, in dp. */
private val MARK_RADIUS: Dp = 4.dp

/**
 * A scrolling sheet of labelled tracks over one horizontal [axis], with a ruler and a playhead.
 *
 * @param List<TrackRow> rows The tracks to show, in display order.
 * @param TrackAxis axis The horizontal domain the marks map onto.
 * @param Float? playhead The domain value to draw the playhead at, or null for none.
 * @param Modifier modifier The layout modifier.
 * @param Function formatTick Renders a ruler tick value; defaults to a trimmed decimal.
 * @param Function? onMarkClick Invoked with the row and the mark nearest a click, when one is in range.
 * @param Function? onTrackClick Invoked with the row and the clicked domain value when no mark is in range.
 */
@Composable
fun TrackSheet(
	rows: List<TrackRow>,
	axis: TrackAxis,
	playhead: Float?,
	modifier: Modifier = Modifier,
	formatTick: (Float) -> String = ::defaultTickLabel,
	onMarkClick: ((TrackRow, TrackKeyMark) -> Unit)? = null,
	onTrackClick: ((TrackRow, Float) -> Unit)? = null,
) {
	val colors = LocalUmamoColors.current
	Column(modifier = modifier.fillMaxSize()) {
		TrackRuler(axis = axis, playhead = playhead, formatTick = formatTick)
		LazyColumn(modifier = Modifier.fillMaxSize()) {
			items(rows, key = { row -> row.key }) { row ->
				Row(modifier = Modifier.fillMaxWidth().height(ROW_HEIGHT), verticalAlignment = Alignment.CenterVertically) {
					Row(
						modifier = Modifier.width(LABEL_COLUMN_WIDTH).padding(start = 6.dp + INDENT_PER_DEPTH * row.depth, end = 6.dp),
						verticalAlignment = Alignment.CenterVertically,
					) {
						Text(
							text = row.label,
							style = LocalUmamoTypography.current.bodyMedium,
							color = colors.text,
							maxLines = 1,
							overflow = TextOverflow.Ellipsis,
						)
						if (row.detail != null) {
							Text(
								text = " ${row.detail}",
								style = LocalUmamoTypography.current.labelSmall,
								color = colors.textMuted,
								maxLines = 1,
								overflow = TextOverflow.Ellipsis,
							)
						}
					}
					TrackLane(
						row = row,
						axis = axis,
						playhead = playhead,
						modifier = Modifier.weight(1f).fillMaxWidth(),
						onMarkClick = onMarkClick,
						onTrackClick = onTrackClick,
					)
				}
			}
		}
	}
}

/**
 * The ruler strip: evenly spaced domain ticks above the track lanes, with the playhead.
 *
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Function formatTick Renders a tick value.
 */
@Composable
private fun TrackRuler(axis: TrackAxis, playhead: Float?, formatTick: (Float) -> String) {
	val colors = LocalUmamoColors.current
	Row(modifier = Modifier.fillMaxWidth().height(RULER_HEIGHT).background(colors.headerBackground)) {
		Box(modifier = Modifier.width(LABEL_COLUMN_WIDTH))
		Box(modifier = Modifier.weight(1f).fillMaxSize()) {
			Canvas(modifier = Modifier.fillMaxSize()) {
				for (tick in axis.ticks()) {
					val x = axis.fractionOf(tick) * size.width
					drawLine(colors.panelBorder, Offset(x, size.height * 0.5f), Offset(x, size.height), strokeWidth = 1f)
				}
				playhead?.let { value -> drawPlayhead(value, axis, colors.accent) }
			}
			// Tick labels ride above the canvas so they are not clipped by the lane below.
			for (tick in axis.ticks()) {
				Text(
					text = formatTick(tick),
					style = LocalUmamoTypography.current.labelSmall,
					color = colors.textMuted,
					maxLines = 1,
					modifier = Modifier.padding(start = 2.dp).offsetByFraction(axis.fractionOf(tick)),
				)
			}
		}
	}
}

/**
 * One row's track lane: its marks positioned along the axis, over the playhead.
 *
 * @param TrackRow row The row to draw.
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Modifier modifier The layout modifier.
 * @param Function? onMarkClick Invoked when a click lands on a mark.
 * @param Function? onTrackClick Invoked when a click lands on empty track.
 */
@Composable
private fun TrackLane(
	row: TrackRow,
	axis: TrackAxis,
	playhead: Float?,
	modifier: Modifier = Modifier,
	onMarkClick: ((TrackRow, TrackKeyMark) -> Unit)?,
	onTrackClick: ((TrackRow, Float) -> Unit)?,
) {
	val colors = LocalUmamoColors.current
	Canvas(
		modifier =
			modifier
				.pointerInput(row.key, axis, onMarkClick, onTrackClick) {
					if (onMarkClick == null && onTrackClick == null) {
						return@pointerInput
					}
					detectTapGestures { offset ->
						val fraction = if (size.width == 0) 0f else offset.x / size.width
						val domainValue = axis.valueAt(fraction)
						// The pick radius is expressed in DOMAIN units so it stays a constant number of
						// pixels regardless of how wide the panel is or how large the domain is.
						val tolerance =
							if (size.width == 0) 0f else abs(axis.span) * (MARK_RADIUS.toPx() * 1.5f / size.width)
						val hit = nearestMark(row.marks, domainValue, tolerance)
						if (hit != null) {
							onMarkClick?.invoke(row, hit)
						} else {
							onTrackClick?.invoke(row, domainValue)
						}
					}
				},
	) {
		// A hairline baseline makes an empty track legible as a track rather than as blank panel.
		drawLine(
			colors.panelBorder,
			Offset(0f, size.height * 0.5f),
			Offset(size.width, size.height * 0.5f),
			strokeWidth = 1f,
		)
		playhead?.let { value -> drawPlayhead(value, axis, colors.accent) }
		val radius = MARK_RADIUS.toPx()
		for (mark in row.marks) {
			val x = axis.fractionOf(mark.position) * size.width
			val fill = if (mark.selected) colors.accent else colors.controlGlyph
			drawMark(mark.shape, Offset(x, size.height * 0.5f), radius, fill, colors.panelBackground)
		}
	}
}

/** Draws the playhead as a full-height vertical line at [value]'s position. */
private fun DrawScope.drawPlayhead(value: Float, axis: TrackAxis, color: Color) {
	val x = axis.fractionOf(value) * size.width
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
			drawCircle(outline, radius, center, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
		}

		TrackKeyShape.Square -> {
			val topLeft = Offset(center.x - radius, center.y - radius)
			val size = Size(radius * 2, radius * 2)
			drawRect(fill, topLeft, size)
			drawRect(outline, topLeft, size, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
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
			drawPath(path, outline, style = androidx.compose.ui.graphics.drawscope.Stroke(width = 1f))
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
