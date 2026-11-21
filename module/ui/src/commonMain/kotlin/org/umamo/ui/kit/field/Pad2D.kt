package org.umamo.ui.kit.field

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors

/** The handle's radius. */
private val PAD_HANDLE_RADIUS = 5.dp

/** The width of the frame, the center cross, and the key lines. */
private val PAD_LINE_WIDTH = 1.dp

/**
 * A flat 2D pad that sets two values at once: a recessed 3 : 2 rectangle with one handle, the X axis across and the Y
 * axis up, so the top edge is the Y maximum.  A press jumps the handle to the pointer and a drag follows it, each axis
 * held to its ends past an edge.  Supply the width through [modifier]; the height follows from the shape.
 *
 * @param Float xValue The current X-axis value.
 * @param Float yValue The current Y-axis value.
 * @param Function onChange Called with (newX, newY) on the press and each drag frame (the preview).
 * @param Modifier modifier Layout modifier (supply the width).
 * @param Function onChangeFinished Called once when the gesture ends (release or cancel).
 * @param ClosedFloatingPointRange xRange The X-axis min..max range.
 * @param ClosedFloatingPointRange yRange The Y-axis min..max range.
 * @param List xKeyMarks The X axis's keys, each with its shape: a line on the pad, and the values a held-Ctrl drag
 *   snaps that axis to.  Empty by default.
 * @param List yKeyMarks The Y axis's keys, the same way.  Empty by default.
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
	xKeyMarks: List<SliderKeyMark> = emptyList(),
	yKeyMarks: List<SliderKeyMark> = emptyList(),
) {
	val colors = LocalUmamoColors.current
	val xFraction = padFractionOf(xValue, xRange)
	// Y is inverted: the maximum sits at the top, so a value fraction of 1 maps to screen y = 0.
	val yFractionFromTop = 1f - padFractionOf(yValue, yRange)
	val trackColor = colors.sliderTrack
	val borderColor = colors.controlBorder
	val keyColor = colors.guideLine
	val crossColor = colors.divider
	val handleColor = colors.sliderThumb
	val handleRingColor = colors.accent
	val xKeyValues = remember(xKeyMarks) { padKeyLineValues(xKeyMarks) }
	val yKeyValues = remember(yKeyMarks) { padKeyLineValues(yKeyMarks) }
	val keyPips = remember(xKeyMarks, yKeyMarks) { padKeyPips(xKeyMarks, yKeyMarks) }
	// pointerInput is keyed only on the ranges, so it does not restart when a recomposition hands in a new onChange (a
	// reused list slot binding to a different pair).  Read the latest through these so a drag always drives the current
	// pair, never the one that first occupied the slot.
	val currentOnChange by rememberUpdatedState(onChange)
	val currentOnChangeFinished by rememberUpdatedState(onChangeFinished)
	val currentXKeyValues by rememberUpdatedState(xKeyValues)
	val currentYKeyValues by rememberUpdatedState(yKeyValues)
	Canvas(
		modifier =
			modifier
				.aspectRatio(1.5f)
				.pointerInput(xRange, yRange) {
					val snapRadius = KEY_SNAP_RADIUS.toPx()
					awaitEachGesture {
						val down = awaitFirstDown()

						// A secondary press opens the row's menu and a middle press belongs to the view; neither moves the handle.
						if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
							return@awaitEachGesture
						}

						// Claimed, so the press never reaches a surface under the pad that answers a click.
						down.consume()
						var position = down.position
						var ctrlHeld = currentEvent.keyboardModifiers.isCtrlPressed

						try {
							while (true) {
								val width = size.width.toFloat()
								val height = size.height.toFloat()
								val xSnapTargets = if (ctrlHeld) currentXKeyValues else emptyList()
								val ySnapTargets = if (ctrlHeld) currentYKeyValues else emptyList()
								currentOnChange(
									padAxisSnapped(padValueAt(position.x, width, xRange, invert = false), xSnapTargets, snapRadius, width, xRange),
									padAxisSnapped(padValueAt(position.y, height, yRange, invert = true), ySnapTargets, snapRadius, height, yRange),
								)

								// Ctrl is read off every event, so it can join a drag already under way.
								val event = awaitPointerEvent()
								val change = event.changes.firstOrNull { candidate -> candidate.id == down.id } ?: break
								change.consume()

								if (!change.pressed) {
									break
								}

								position = change.position
								ctrlHeld = event.keyboardModifiers.isCtrlPressed
							}
						} finally {
							// The press has already moved the handle, so every gesture is committed, once: on release, or on
							// the cancellation that ends a pad torn down mid-drag.
							currentOnChangeFinished()
						}
					}
				},
	) {
		val padWidth = size.width
		val padHeight = size.height
		val lineWidth = PAD_LINE_WIDTH.toPx()
		drawRect(color = trackColor, size = Size(padWidth, padHeight))
		drawRect(color = borderColor, size = Size(padWidth, padHeight), style = Stroke(width = lineWidth))
		drawLine(crossColor, Offset(padWidth / 2f, 0f), Offset(padWidth / 2f, padHeight), strokeWidth = lineWidth)
		drawLine(crossColor, Offset(0f, padHeight / 2f), Offset(padWidth, padHeight / 2f), strokeWidth = lineWidth)

		// The key lines go over the cross, so a key at the center still reads.
		for (keyValue in xKeyValues) {
			val lineX = padWidth * padFractionOf(keyValue, xRange)
			drawLine(crossColor, Offset(lineX, 0f), Offset(lineX, padHeight), strokeWidth = lineWidth)
		}

		for (keyValue in yKeyValues) {
			val lineY = padHeight * (1f - padFractionOf(keyValue, yRange))
			drawLine(crossColor, Offset(0f, lineY), Offset(padWidth, lineY), strokeWidth = lineWidth)
		}

		val pipRadius = SLIDER_KEY_MARK_RADIUS.toPx()

		for (pip in keyPips) {
			val pipX = padWidth * padFractionOf(pip.x, xRange)
			val pipY = padHeight * (1f - padFractionOf(pip.y, yRange))
			drawPadKeyPip(pip.shape, keyColor, pipX, pipY, pipRadius)
		}

		val handleCenter = Offset(padWidth * xFraction, padHeight * yFractionFromTop)
		val handleRadius = PAD_HANDLE_RADIUS.toPx()
		drawCircle(color = handleColor, radius = handleRadius, center = handleCenter)
		drawCircle(color = handleRingColor, radius = handleRadius, center = handleCenter, style = Stroke(width = 1.5.dp.toPx()))
	}
}

/**
 * Draws one key pip centered at ([centerX], [centerY]) in the slider's mark shapes, the mixed pip being the rounded
 * square's top half over the circle's bottom half.
 *
 * @param PadKeyPipShape shape The pip's shape.
 * @param Color color The fill color.
 * @param Float centerX The pip's center x.
 * @param Float centerY The pip's center y.
 * @param Float radius The circle's radius, or the square's half-side.
 */
private fun DrawScope.drawPadKeyPip(shape: PadKeyPipShape, color: Color, centerX: Float, centerY: Float, radius: Float) {
	when (shape) {
		PadKeyPipShape.Circle -> {
			drawSliderShape(SliderKeyShape.Circle, color, centerX, centerY, radius)
		}

		PadKeyPipShape.Square -> {
			drawSliderShape(SliderKeyShape.Square, color, centerX, centerY, radius)
		}

		PadKeyPipShape.SquareOverCircle -> {
			// The whole circle, then the square's top half over it.  The square holds the circle, so one clip edge splits
			// the two with no seam between them.
			drawSliderShape(SliderKeyShape.Circle, color, centerX, centerY, radius)
			clipRect(left = centerX - radius, top = centerY - radius, right = centerX + radius, bottom = centerY) {
				drawSliderShape(SliderKeyShape.Square, color, centerX, centerY, radius)
			}
		}
	}
}