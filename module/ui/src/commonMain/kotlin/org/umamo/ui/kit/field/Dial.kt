package org.umamo.ui.kit.field

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.takeOrElse
import androidx.compose.ui.input.pointer.isCtrlPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.isTertiaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import org.umamo.ui.theme.LocalUmamoColors
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/** The ring's stroke width, the slider track's thickness. */
private val DIAL_TRACK_WIDTH = 4.dp

/** The hand's stroke width. */
private val DIAL_HAND_WIDTH = 1.5.dp

/** How near the center a pointer may come before its angle is too noisy to read. */
private val DIAL_DEAD_ZONE = 4.dp

/** How far the seam tick reaches in from the ring's inner edge. */
private val DIAL_SEAM_TICK_LENGTH = 4.dp

/** A full turn in radians. */
private const val DIAL_FULL_TURN = (2.0 * PI).toFloat()

/**
 * A flat circular control for a value whose two ends meet: a ring with a hand from the center out to a thumb, the
 * seam at 12 o'clock and values increasing clockwise.  A press jumps the value to the pointer's angle, and a drag
 * follows the angle round, across the seam as often as it goes.  Size it through [modifier]; it draws inside the
 * smaller side.
 *
 * [onValueChange] is handed where the pointer has carried the value, laps included: inside [valueRange] until the
 * drag crosses the seam, and past an end after.  A wrapping owner is handed one span more for every lap, and a
 * clamping one at most one span past an end, so the first crossing back brings its value in again.  The owner brings
 * that back into range, wrapping or clamping as its value does, and hands the result back as [value].
 * [onValueChangeFinished] fires once for each gesture that moved the value, so a caller can treat a whole drag as one
 * undo step.  While Ctrl is held, a value near a key mark latches onto it.
 *
 * @param Float value The current value, inside [valueRange].
 * @param Function onValueChange Called with the value under the pointer, laps included, on the press and each drag frame.
 * @param Modifier modifier Layout modifier (supply the size).
 * @param Function onValueChangeFinished Called once when a gesture that moved the value ends.
 * @param ClosedFloatingPointRange valueRange The min..max one lap covers.
 * @param List keyMarks Parameter keys to mark on the ring, each with its shape, and the values a held-Ctrl drag
 *   snaps to.  Empty by default.
 * @param SliderKeyShape thumbShape The thumb's shape, as on the slider; defaults to Circle.
 * @param Boolean wraps Whether the owner wraps the value at the seam.  A held-Ctrl snap then reaches a key across
 *   the seam, and the laps count without limit; a value that clamps snaps along the range alone, and its laps stop
 *   one out either side.
 * @param Color accentColor The color of the fill and the hand; the theme's accent when unspecified.
 */
@Composable
fun Dial(
	value: Float,
	onValueChange: (Float) -> Unit,
	modifier: Modifier = Modifier,
	onValueChangeFinished: () -> Unit = {},
	valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
	keyMarks: List<SliderKeyMark> = emptyList(),
	thumbShape: SliderKeyShape = SliderKeyShape.Circle,
	wraps: Boolean = true,
	accentColor: Color = Color.Unspecified,
) {
	val colors = LocalUmamoColors.current
	val fill = dialFillFraction(value, valueRange)
	val trackColor = colors.sliderTrack
	val fillColor = accentColor.takeOrElse { colors.accent }
	val thumbColor = colors.sliderThumb
	val tickColor = colors.guideLine
	// pointerInput is keyed only on valueRange, so it does not restart when a recomposition hands in new callbacks (a
	// reused list slot binding to a different row).  Read the latest through these so a drag always drives the
	// current row, never the one that first occupied the slot.
	val currentOnValueChange by rememberUpdatedState(onValueChange)
	val currentOnValueChangeFinished by rememberUpdatedState(onValueChangeFinished)
	val snapValues = remember(keyMarks) { keyMarks.map { mark -> mark.value } }
	val currentSnapValues by rememberUpdatedState(snapValues)
	val currentWraps by rememberUpdatedState(wraps)
	Canvas(
		modifier =
			modifier.pointerInput(valueRange) {
				val thumbRadius = SLIDER_THUMB_RADIUS.toPx()
				val deadZone = DIAL_DEAD_ZONE.toPx()
				val snapRadius = KEY_SNAP_RADIUS.toPx()
				awaitEachGesture {
					val down = awaitFirstDown()

					// A secondary press opens the row's menu and a middle press belongs to the view; neither moves the value.
					if (currentEvent.buttons.isSecondaryPressed || currentEvent.buttons.isTertiaryPressed) {
						return@awaitEachGesture
					}

					// Claimed, so the press never reaches a surface under the dial that answers a click.
					down.consume()
					val width = size.width.toFloat()
					val height = size.height.toFloat()
					val ringRadius = dialRingRadius(width, height, thumbRadius)
					val tolerance = abs(valueRange.endInclusive - valueRange.start) * snapRadius / (DIAL_FULL_TURN * ringRadius)
					var position = down.position
					var ctrlHeld = currentEvent.keyboardModifiers.isCtrlPressed
					var laps = 0
					var lastFraction: Float? = null
					var moved = false

					try {
						while (true) {
							val fraction = dialFractionAt(position.x, position.y, width / 2f, height / 2f, deadZone)

							if (fraction != null) {
								laps = lastFraction?.let { previous -> dialLapsAfter(laps, previous, fraction, currentWraps) } ?: laps
								lastFraction = fraction
								val raw = dialValue(fraction, laps, valueRange)
								val snapTargets = if (ctrlHeld) currentSnapValues else emptyList()
								currentOnValueChange(dialSnapped(raw, snapTargets, tolerance, valueRange, currentWraps))
								moved = true
							}

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
						// A release ends the gesture, and so does a cancellation, which is how a dial torn down mid-drag
						// ends: either way the drag is committed, once.  A press that never left the dead zone moved nothing.
						if (moved) {
							currentOnValueChangeFinished()
						}
					}
				}
			},
	) {
		val thumbRadius = SLIDER_THUMB_RADIUS.toPx()
		val trackWidth = DIAL_TRACK_WIDTH.toPx()
		val ringRadius = dialRingRadius(size.width, size.height, thumbRadius)
		val center = Offset(size.width / 2f, size.height / 2f)
		val ringTopLeft = Offset(center.x - ringRadius, center.y - ringRadius)
		val ringSize = Size(ringRadius * 2f, ringRadius * 2f)
		drawCircle(color = trackColor, radius = ringRadius, center = center, style = Stroke(width = trackWidth))
		// The fill runs clockwise from the seam, which drawArc measures from 3 o'clock.
		drawArc(
			color = fillColor,
			startAngle = -90f,
			sweepAngle = fill * 360f,
			useCenter = false,
			topLeft = ringTopLeft,
			size = ringSize,
			style = Stroke(width = trackWidth),
		)
		// The seam, inside the ring so it never meets the thumb's reach past the ring.
		val tickOuter = ringRadius - trackWidth / 2f
		drawLine(
			color = tickColor,
			start = Offset(center.x, center.y - tickOuter),
			end = Offset(center.x, center.y - tickOuter + DIAL_SEAM_TICK_LENGTH.toPx()),
			strokeWidth = DIAL_HAND_WIDTH.toPx(),
		)
		val markRadius = SLIDER_KEY_MARK_RADIUS.toPx()

		for (mark in keyMarks) {
			val markCenter = dialPointAt(center, ringRadius, dialFillFraction(mark.value, valueRange))
			drawSliderShape(mark.shape, tickColor, markCenter.x, markCenter.y, markRadius)
		}

		val thumbCenter = dialPointAt(center, ringRadius, fill)
		drawLine(color = fillColor, start = center, end = thumbCenter, strokeWidth = DIAL_HAND_WIDTH.toPx(), cap = StrokeCap.Round)
		drawSliderShape(thumbShape, thumbColor, thumbCenter.x, thumbCenter.y, thumbRadius)
	}
}

/**
 * The radius the ring is drawn at: inside the control's smaller side by the thumb radius, so the whole thumb stays
 * inside the control's hit area wherever it sits.  Floored at one pixel, so a control smaller than its thumb cannot
 * divide by zero.
 *
 * @param Float width The control's width.
 * @param Float height The control's height.
 * @param Float thumbRadius The thumb's radius.
 * @return Float The ring radius, in pixels.
 */
private fun dialRingRadius(width: Float, height: Float, thumbRadius: Float): Float = (min(width, height) / 2f - thumbRadius).coerceAtLeast(1f)

/**
 * The point on the ring [fraction] of a lap clockwise from 12 o'clock.
 *
 * @param Offset center The dial's center.
 * @param Float ringRadius The ring's radius.
 * @param Float fraction The fraction of a lap, 0 at the seam.
 * @return Offset The point, in screen space where y grows downward.
 */
private fun dialPointAt(center: Offset, ringRadius: Float, fraction: Float): Offset {
	val angle = fraction * DIAL_FULL_TURN

	return Offset(center.x + ringRadius * sin(angle), center.y - ringRadius * cos(angle))
}

/**
 * The value a dial press or drag sets: [raw], latched onto the nearest of [snapTargets] within [tolerance] of it.  A
 * value that wraps measures that reach round the seam, and one that clamps along the range alone.
 *
 * @param Float raw The value under the pointer, laps included.
 * @param List snapTargets The values to latch onto, empty when Ctrl is not held.
 * @param Float tolerance How near a target the value must be to latch, in value units.
 * @param ClosedFloatingPointRange range The min..max one lap covers.
 * @param Boolean wraps Whether the owner wraps the value at the seam.
 * @return Float The value, snapped.
 */
private fun dialSnapped(raw: Float, snapTargets: List<Float>, tolerance: Float, range: ClosedFloatingPointRange<Float>, wraps: Boolean): Float {
	if (snapTargets.isEmpty()) {
		return raw
	}

	if (wraps) {
		return snapToNearestWithinPeriodic(raw, snapTargets, tolerance, range)
	}

	// The minOf / maxOf form tolerates a reversed range, where a plain range would hold nothing.
	val bounds = minOf(range.start, range.endInclusive)..maxOf(range.start, range.endInclusive)

	return snapToNearestWithin(raw, snapTargets, tolerance, bounds)
}