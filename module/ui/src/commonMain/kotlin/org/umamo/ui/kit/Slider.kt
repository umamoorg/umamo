package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors

/** The thumb dot's radius; the thumb travels inset by this from each end so it never spills past the control. */
private val SLIDER_THUMB_RADIUS = 5.dp

/**
 * A flat, draggable slider: a thin track + fill + small thumb on a [Canvas], set by tap or drag anywhere on
 * the control. Replaces Material's Slider (no large thumb, no ripple). A fixed-height control; pass a width
 * via [modifier] (e.g. `Modifier.fillMaxWidth()`).
 *
 * [onValueChange] streams the live value for every frame of a tap or drag; [onValueChangeFinished] fires
 * once at the gesture boundary (the tap, or the drag's release / cancel). This lets a caller treat a whole
 * continuous drag as a single committed gesture (e.g. one undo step) while still previewing each frame.
 *
 * @param Float    value                 The current value.
 * @param Function onValueChange         Called with the new value on each tap / drag frame (the preview).
 * @param Modifier modifier              Layout modifier (supply the width).
 * @param Function onValueChangeFinished Called once when the gesture ends (tap, drag release, or cancel).
 * @param ClosedFloatingPointRange valueRange The min..max range.
 * @param List keyTicks Parameter values to mark on the track (the drawable's keyform keys in Edit mode),
 *   so the user can scrub onto a key. Empty by default.
 */
@Composable
fun Slider(
	value: Float,
	onValueChange: (Float) -> Unit,
	modifier: Modifier = Modifier,
	onValueChangeFinished: () -> Unit = {},
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	keyTicks: List<Float> = emptyList(),
) {
	val colors = LocalUmamoColors.current
	val span = (valueRange.endInclusive - valueRange.start).takeIf { it != 0f } ?: 1f
	val fraction = ((value - valueRange.start) / span).coerceIn(0f, 1f)
	val trackColor = colors.sliderTrack
	val fillColor = colors.accent
	val thumbColor = colors.sliderThumb
	val tickColor = colors.guideLine
	// pointerInput is keyed only on valueRange, so it does not restart when a recomposition hands in a new
	// onValueChange (a reused list slot binding to a different row).  Read the latest through these so a drag
	// always drives the current row, never the one that first occupied the slot.
	val currentOnValueChange by rememberUpdatedState(onValueChange)
	val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
	Canvas(
		modifier =
			modifier
				.height(18.dp)
				.pointerInput(valueRange) {
					val thumbRadius = SLIDER_THUMB_RADIUS.toPx()
					detectTapGestures { offset ->
						currentOnValueChange(valueAt(offset.x, size.width.toFloat(), thumbRadius, valueRange))
						currentOnValueChangeFinished()
					}
				}
				.pointerInput(valueRange) {
					val thumbRadius = SLIDER_THUMB_RADIUS.toPx()
					detectDragGestures(
						onDragEnd = { currentOnValueChangeFinished() },
						onDragCancel = { currentOnValueChangeFinished() },
					) { change, _ ->
						change.consume()
						currentOnValueChange(valueAt(change.position.x, size.width.toFloat(), thumbRadius, valueRange))
					}
				},
	) {
		val trackHeight = 4.dp.toPx()
		val thumbRadius = SLIDER_THUMB_RADIUS.toPx()
		val centerY = size.height / 2f
		val corner = CornerRadius(trackHeight / 2f)
		val top = Offset(0f, centerY - trackHeight / 2f)
		// The thumb centre travels within [thumbRadius, width - thumbRadius] so the whole dot stays inside the
		// control's hit area at both ends; otherwise the outer half of the thumb would hang into the
		// non-interactive padding at the track start, and a click there would miss the slider. The track fills
		// the full width for the end caps.
		val usableWidth = (size.width - 2f * thumbRadius).coerceAtLeast(0f)
		val thumbCenterX = thumbRadius + fraction * usableWidth
		drawRoundRect(color = trackColor, topLeft = top, size = Size(size.width, trackHeight), cornerRadius = corner)
		drawRoundRect(
			color = fillColor,
			topLeft = top,
			size = Size(thumbCenterX, trackHeight),
			cornerRadius = corner,
		)
		// Keyform key markers: a thin vertical tick at each key value, so the user can scrub onto one.
		val tickHalfHeight = 5.dp.toPx()
		val tickWidth = 1.5.dp.toPx()
		for (key in keyTicks) {
			val keyFraction = ((key - valueRange.start) / span).coerceIn(0f, 1f)
			val tickX = (thumbRadius + keyFraction * usableWidth).coerceIn(0f, size.width)
			drawLine(
				color = tickColor,
				start = Offset(tickX, centerY - tickHalfHeight),
				end = Offset(tickX, centerY + tickHalfHeight),
				strokeWidth = tickWidth,
			)
		}
		drawCircle(
			color = thumbColor,
			radius = thumbRadius,
			center = Offset(thumbCenterX, centerY),
		)
	}
}

/**
 * Maps a pointer x onto [range] over the thumb's travel band, which is inset [inset] from each end so the
 * track ends map to the range ends and the whole thumb stays inside the control. Clamped to that band.
 *
 * @param Float x The pointer x within the control.
 * @param Float width The control's full width.
 * @param Float inset The thumb radius the travel band is inset by at each end.
 * @param ClosedFloatingPointRange range The value range to map onto.
 * @return Float The value at [x].
 */
private fun valueAt(x: Float, width: Float, inset: Float, range: ClosedFloatingPointRange<Float>): Float {
	val usableWidth = (width - 2f * inset).takeIf { it > 0f } ?: 1f
	val fraction = ((x - inset) / usableWidth).coerceIn(0f, 1f)
	return range.start + fraction * (range.endInclusive - range.start)
}
