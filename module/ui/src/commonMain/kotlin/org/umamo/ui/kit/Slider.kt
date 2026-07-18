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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors

/** The thumb dot's radius; the thumb travels inset by this from each end so it never spills past the control. */
private val SLIDER_THUMB_RADIUS = 5.dp

/** A per-key marker's half-extent; kept under the thumb radius so the current-value thumb stays dominant. */
private val SLIDER_KEY_MARK_RADIUS = 3.5.dp

/**
 * The shape a slider draws for its thumb and for a per-key marker. A circle marks a key-form (grid) point;
 * a square marks a blend-shape point - the parameter-cockpit distinction between the two control-point
 * kinds (see ParameterKind: circle points drive keyform interpolation, square points drive additive
 * blend-shape deltas).
 */
enum class SliderKeyShape {
	Circle,
	Square,
}

/**
 * A parameter key to mark on the slider track: its value and the shape that encodes its kind.
 *
 * @param Float value The parameter value the key sits at.
 * @param SliderKeyShape shape The marker shape (Circle = grid key, Square = blend-shape key).
 */
data class SliderKeyMark(
	val value: Float,
	val shape: SliderKeyShape,
)

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
 * @param List keyMarks Parameter keys to mark on the track, each with its shape (circle grid key / square
 *   blend-shape key), so the user can see and scrub onto a key. Empty by default.
 * @param SliderKeyShape thumbShape The thumb's shape (Circle for a key-form parameter, Square for a
 *   blend-shape parameter); defaults to Circle.
 */
@Composable
fun Slider(
	value: Float,
	onValueChange: (Float) -> Unit,
	modifier: Modifier = Modifier,
	onValueChangeFinished: () -> Unit = {},
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	keyMarks: List<SliderKeyMark> = emptyList(),
	thumbShape: SliderKeyShape = SliderKeyShape.Circle,
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
		// Key markers: a small dot at each key value so the user can see and scrub onto a key. Shape
		// encodes the key's kind - a circle for a key-form (grid) key, a square for a blend-shape key.
		val markRadius = SLIDER_KEY_MARK_RADIUS.toPx()
		for (mark in keyMarks) {
			val keyFraction = ((mark.value - valueRange.start) / span).coerceIn(0f, 1f)
			val markX = (thumbRadius + keyFraction * usableWidth).coerceIn(0f, size.width)
			drawSliderShape(mark.shape, tickColor, markX, centerY, markRadius)
		}
		drawSliderShape(thumbShape, thumbColor, thumbCenterX, centerY, thumbRadius)
	}
}

/**
 * Draws one slider marker (thumb or key mark) centered at ([centerX], [centerY]): a filled circle of
 * [radius], or a square of the same half-extent with slightly rounded corners to match the flat look.
 *
 * @param SliderKeyShape shape   The marker shape.
 * @param Color          color   The fill color.
 * @param Float          centerX The marker center x.
 * @param Float          centerY The marker center y.
 * @param Float          radius  The circle radius, or the square's half-side.
 */
private fun DrawScope.drawSliderShape(shape: SliderKeyShape, color: Color, centerX: Float, centerY: Float, radius: Float) {
	when (shape) {
		SliderKeyShape.Circle -> {
			drawCircle(color = color, radius = radius, center = Offset(centerX, centerY))
		}

		SliderKeyShape.Square -> {
			drawRoundRect(
				color = color,
				topLeft = Offset(centerX - radius, centerY - radius),
				size = Size(radius * 2f, radius * 2f),
				cornerRadius = CornerRadius(radius * 0.35f),
			)
		}
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
