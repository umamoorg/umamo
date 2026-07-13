package org.umamo.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors

/**
 * A flat, draggable 2D pad for a LINKED ("combined") parameter pair: a recessed square drawing a
 * nine-point reference grid plus a single handle, set by tap or drag anywhere on the control. The X axis
 * runs left to right over [xRange]; the Y axis runs bottom to top over [yRange] - screen-top is the
 * maximum, matching how Cubism orients its combined-parameter pad. The control is sized entirely by
 * [modifier]: supply both a width and a bounded height (e.g.
 * `Modifier.fillMaxWidth().heightIn(max = 200.dp)`), because a Canvas otherwise expands to fill all the
 * space offered - inside a scrolling column that would overrun the rows beneath it. The grid and handle
 * scale to whatever rectangle results, so a non-square pad is fine (each axis maps independently).
 *
 * 2D パッド（リンクパラメータ対用）。X は左右、Y は下上（上端が最大）。大きさは [modifier] で指定（幅と高さの上限）。
 *
 * [onChange] streams the live (X, Y) for every tap / drag frame; [onChangeFinished] fires once at the
 * gesture boundary (tap, drag release, or cancel), so a whole drag can be committed as one gesture.
 *
 * @param Float    xValue          The current X-axis value.
 * @param Float    yValue          The current Y-axis value.
 * @param Function onChange        Called with (newX, newY) on each tap / drag frame (the preview).
 * @param Modifier modifier        Layout modifier (supply the width and a bounded height).
 * @param Function onChangeFinished Called once when the gesture ends (tap, drag release, or cancel).
 * @param ClosedFloatingPointRange xRange The X-axis min..max range.
 * @param ClosedFloatingPointRange yRange The Y-axis min..max range.
 */
@Composable
fun Pad2D(
	xValue: Float,
	yValue: Float,
	onChange: (Float, Float) -> Unit,
	modifier: Modifier = Modifier,
	onChangeFinished: () -> Unit = {},
	xRange: ClosedFloatingPointRange<Float> = 0f..1f,
	yRange: ClosedFloatingPointRange<Float> = 0f..1f,
) {
	val colors = LocalUmamoColors.current
	val xSpan = (xRange.endInclusive - xRange.start).takeIf { it != 0f } ?: 1f
	val ySpan = (yRange.endInclusive - yRange.start).takeIf { it != 0f } ?: 1f
	val xFraction = ((xValue - xRange.start) / xSpan).coerceIn(0f, 1f)
	// Y is inverted: the maximum sits at the top, so a value fraction of 1 maps to screen y = 0.
	val yFractionFromTop = 1f - ((yValue - yRange.start) / ySpan).coerceIn(0f, 1f)
	val trackColor = colors.sliderTrack
	val borderColor = colors.controlBorder
	val gridColor = colors.guideLine
	val crossColor = colors.divider
	val handleColor = colors.sliderThumb
	val handleRingColor = colors.accent
	// pointerInput is keyed only on the ranges, so it does not restart when a recomposition hands in a new
	// onChange (a reused list slot binding to a different pair).  Read the latest through this so a drag
	// always drives the current pair, never the one that first occupied the slot.
	val currentOnChange by rememberUpdatedState(onChange)
	val currentOnChangeFinished by rememberUpdatedState(onChangeFinished)
	Canvas(
		modifier =
			modifier
				.aspectRatio(1.5f)
				.pointerInput(xRange, yRange) {
					detectTapGestures { offset ->
						currentOnChange(
							valueAt(offset.x, size.width.toFloat(), xRange, invert = false),
							valueAt(offset.y, size.height.toFloat(), yRange, invert = true),
						)
						currentOnChangeFinished()
					}
				}
				.pointerInput(xRange, yRange) {
					detectDragGestures(
						onDragEnd = { currentOnChangeFinished() },
						onDragCancel = { currentOnChangeFinished() },
					) { change, _ ->
						change.consume()
						currentOnChange(
							valueAt(change.position.x, size.width.toFloat(), xRange, invert = false),
							valueAt(change.position.y, size.height.toFloat(), yRange, invert = true),
						)
					}
				},
	) {
		val padWidth = size.width
		val padHeight = size.height
		drawRect(color = trackColor, size = Size(padWidth, padHeight))
		drawRect(color = borderColor, size = Size(padWidth, padHeight), style = Stroke(width = 1.dp.toPx()))
		// A faint centre cross for orientation; the thirds would over-clutter, so the centre is enough.
		drawLine(crossColor, Offset(padWidth / 2f, 0f), Offset(padWidth / 2f, padHeight), strokeWidth = 1.dp.toPx())
		drawLine(crossColor, Offset(0f, padHeight / 2f), Offset(padWidth, padHeight / 2f), strokeWidth = 1.dp.toPx())
		// Nine reference dots at the 0 / 0.5 / 1 grid intersections on each axis.
		val dotRadius = 1.5.dp.toPx()
		for (gridRow in 0..2) {
			for (gridColumn in 0..2) {
				val center = Offset(padWidth * gridColumn / 2f, padHeight * gridRow / 2f)
				drawCircle(color = gridColor, radius = dotRadius, center = center)
			}
		}
		// The handle at the current value (X across, Y measured from the top).
		val handleCenter =
			Offset(
				(padWidth * xFraction).coerceIn(0f, padWidth),
				(padHeight * yFractionFromTop).coerceIn(0f, padHeight),
			)
		drawCircle(color = handleColor, radius = 5.dp.toPx(), center = handleCenter)
		drawCircle(
			color = handleRingColor,
			radius = 5.dp.toPx(),
			center = handleCenter,
			style = Stroke(width = 1.5.dp.toPx()),
		)
	}
}

/**
 * Maps a pointer coordinate within [extent] onto [range], clamped to the ends. When [invert] is set the
 * axis is flipped so coordinate 0 (the top of a Canvas) becomes the range maximum - used for the Y axis,
 * where screen-top should read as the highest value.
 *
 * @param Float   coordinate The pointer position along the axis (x or y).
 * @param Float   extent     The axis length in pixels (width or height).
 * @param ClosedFloatingPointRange range The min..max value range.
 * @param Boolean invert     When true, flip so coordinate 0 maps to the range maximum.
 * @return Float The clamped value for that coordinate.
 */
private fun valueAt(
	coordinate: Float,
	extent: Float,
	range: ClosedFloatingPointRange<Float>,
	invert: Boolean,
): Float {
	val safeExtent = extent.takeIf { it > 0f } ?: 1f
	val rawFraction = (coordinate / safeExtent).coerceIn(0f, 1f)
	val fraction =
		if (invert) {
			1f - rawFraction
		} else {
			rawFraction
		}
	return range.start + fraction * (range.endInclusive - range.start)
}
