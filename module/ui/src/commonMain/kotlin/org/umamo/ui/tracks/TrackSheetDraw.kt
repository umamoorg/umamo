package org.umamo.ui.tracks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.DrawStyle
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import org.umamo.ui.theme.UmamoColors

/**
 * Draws the playhead as a full-height vertical line at [value]'s position.
 *
 * @param Float value The playhead's value on the axis.
 * @param TrackAxis axis The axis that maps a value to a position.
 * @param Color color The line color.
 * @param Float inset The lane's horizontal inset, in pixels.
 */
internal fun DrawScope.drawPlayhead(value: Float, axis: TrackAxis, color: Color, inset: Float) {
	val x = laneX(axis.fractionOf(value), size.width, inset)
	drawLine(color, Offset(x, 0f), Offset(x, size.height), strokeWidth = 1.5f)
}

/**
 * Draws one mark centered on [center].
 *
 * Every shape is filled then outlined in the panel color, so marks that land on top of each other (two
 * channels keyed at the same value) stay countable instead of merging into one blob.
 *
 * @param TrackKeyShape shape The mark's shape.
 * @param Offset center The mark's center.
 * @param Float radius The mark's radius, in pixels.
 * @param Color fill The fill color.
 * @param Color outline The outline color.
 * @param Color? splitFill The top-left half's fill, or null for none.
 */
private fun DrawScope.drawMark(shape: TrackKeyShape, center: Offset, radius: Float, fill: Color, outline: Color, splitFill: Color? = null) {
	drawMarkShape(shape, center, radius, fill, Fill)

	if (splitFill != null) {
		// Over the whole fill rather than beside a half of it, so the diagonal has no seam of background
		// where two antialiased edges would meet.
		val topLeftHalf =
			Path().apply {
				moveTo(center.x - radius, center.y - radius)
				lineTo(center.x + radius, center.y - radius)
				lineTo(center.x - radius, center.y + radius)
				close()
			}
		clipPath(topLeftHalf) { drawMarkShape(shape, center, radius, splitFill, Fill) }
	}

	drawMarkShape(shape, center, radius, outline, Stroke(width = 1f))
}

/**
 * Draws [shape] centered on [center] in one color, filled or stroked.
 *
 * @param TrackKeyShape shape The mark's shape.
 * @param Offset center The mark's center.
 * @param Float radius The mark's radius, in pixels.
 * @param Color color The color.
 * @param DrawStyle style The fill or the stroke.
 */
private fun DrawScope.drawMarkShape(shape: TrackKeyShape, center: Offset, radius: Float, color: Color, style: DrawStyle) {
	when (shape) {
		TrackKeyShape.Circle -> {
			drawCircle(color, radius, center, style = style)
		}

		TrackKeyShape.Square -> {
			drawRect(color, Offset(center.x - radius, center.y - radius), Size(radius * 2, radius * 2), style = style)
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
			drawPath(path, color, style = style)
		}
	}
}

/**
 * Draws [mark] at [position] in the colors its state calls for.
 *
 * @param TrackKeyMark mark The mark.
 * @param Float position Where to draw it, in domain units.
 * @param TrackAxis axis The domain.
 * @param Float markRadiusPx Half-extent of a mark and the lane's end inset, in pixels.
 * @param UmamoColors colors The theme's colors.
 */
private fun DrawScope.drawLaneMark(mark: TrackKeyMark, position: Float, axis: TrackAxis, markRadiusPx: Float, colors: UmamoColors) {
	val x = laneX(axis.fractionOf(position), size.width, markRadiusPx)
	val fill =
		when {
			!mark.editable -> colors.textDisabled
			mark.selection == TrackMarkSelection.Selected -> colors.accent
			else -> colors.controlGlyph
		}
	val splitFill = colors.accent.takeIf { mark.editable && mark.selection == TrackMarkSelection.PartlySelected }
	drawMark(mark.shape, Offset(x, size.height * 0.5f), markRadiusPx, fill, colors.panelBackground, splitFill)
}

/**
 * One lane's draw pass: the hairline baseline, the playhead, the marks at their drawn positions, and the pending
 * edit's hollow mark over them.
 *
 * Called inside the lane's Canvas lambda, which is where [draggedMark], [draggedValue], and [groupDelta]
 * were read: all three change every pointer frame of a drag, and a draw-phase read redraws the lane
 * without recomposing the sheet above it.
 *
 * @param List<TrackKeyMark> marks The marks to draw.
 * @param TrackAxis axis The domain.
 * @param Float? playhead The playhead's domain value, or null.
 * @param Float markRadiusPx Half-extent of a mark and the lane's end inset, in pixels.
 * @param UmamoColors colors The theme's colors.
 * @param TrackKeyMark? draggedMark The mark under the hand, or null when no mark drag is in flight.
 * @param Float draggedValue Where the dragged mark is drawn, in domain units.
 * @param Float? groupDelta The in-flight group-drag offset on every selected mark and on the selected part of
 *   every partly-selected one, or null for none.
 * @param Float? pendingAt Where the row holds an edit not yet keyed, or null for none.
 */
internal fun DrawScope.drawTrackLane(
	marks: List<TrackKeyMark>,
	axis: TrackAxis,
	playhead: Float?,
	markRadiusPx: Float,
	colors: UmamoColors,
	draggedMark: TrackKeyMark?,
	draggedValue: Float,
	groupDelta: Float?,
	pendingAt: Float? = null,
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
		// A partly-selected mark comes apart in a group drag: what the drag carries draws selected at the
		// offset, and what it leaves draws unselected in place.  Not at a standstill, where nothing has left.
		if (groupDelta != null && groupDelta != 0f && mark.selection == TrackMarkSelection.PartlySelected) {
			drawLaneMark(mark.copy(selection = TrackMarkSelection.Unselected), mark.position, axis, markRadiusPx, colors)
			drawLaneMark(mark.copy(selection = TrackMarkSelection.Selected), mark.position + groupDelta, axis, markRadiusPx, colors)

			continue
		}

		// A mark being dragged draws at the pointer, not at its stored position, so the gesture reads as
		// direct manipulation rather than as a jump on release.  A GROUP drag shifts every selected mark
		// by the owner's offset instead - including the one under the hand, which is why that case is
		// tested first: the group has already been clamped to its most constrained member, and letting the
		// dragged mark run on past the rest would put back the snap the offset exists to remove - which is
		// also why a group drag clamped to a standstill is a 0f offset and not a null one.
		val drawnPosition =
			when {
				groupDelta != null && mark.selection == TrackMarkSelection.Selected -> mark.position + groupDelta
				mark.keyIndex == draggedMark?.keyIndex -> draggedValue
				else -> mark.position
			}
		drawLaneMark(mark, drawnPosition, axis, markRadiusPx, colors)
	}

	// Over the marks and hollow, so a key already at the same place shows through the ring.
	pendingAt?.let { value ->
		val center = Offset(laneX(axis.fractionOf(value), size.width, markRadiusPx), size.height * 0.5f)
		drawCircle(colors.keyedModified, markRadiusPx + PENDING_RING_GROWTH_PX, center, style = Stroke(width = PENDING_RING_WIDTH_PX))
	}
}

/** How much wider than a mark the pending ring is drawn, in pixels, so it rings a key at the same place. */
private const val PENDING_RING_GROWTH_PX = 1.5f

/** The pending ring's stroke width, in pixels. */
private const val PENDING_RING_WIDTH_PX = 1.5f