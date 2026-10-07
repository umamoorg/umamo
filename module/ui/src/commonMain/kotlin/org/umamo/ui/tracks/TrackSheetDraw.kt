package org.umamo.ui.tracks

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import org.umamo.ui.theme.UmamoColors

/** Draws the playhead as a full-height vertical line at [value]'s position. */
internal fun DrawScope.drawPlayhead(value: Float, axis: TrackAxis, color: Color, inset: Float) {
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

/**
 * One lane's draw pass: the hairline baseline, the playhead, and the marks at their drawn positions.
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
 * @param Float? groupDelta The in-flight group-drag offset on every selected mark, or null for none.
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
		// direct manipulation rather than as a jump on release.  A GROUP drag shifts every selected mark
		// by the owner's offset instead - including the one under the hand, which is why that case is
		// tested first: the group has already been clamped to its most constrained member, and letting the
		// dragged mark run on past the rest would put back the snap the offset exists to remove - which is
		// also why a group drag clamped to a standstill is a 0f offset and not a null one.
		val drawnPosition =
			when {
				groupDelta != null && mark.selected -> mark.position + groupDelta
				mark.keyIndex == draggedMark?.keyIndex -> draggedValue
				else -> mark.position
			}
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