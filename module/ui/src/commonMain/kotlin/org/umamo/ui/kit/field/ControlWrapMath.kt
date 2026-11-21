package org.umamo.ui.kit.field

import androidx.compose.ui.geometry.Offset
import org.umamo.ui.kit.CursorWrapState
import kotlin.math.ceil

/*
 * Pure, Compose-free wrap math for a control that sets a value from where the pointer sits along a band, the slider
 * and each axis of the pad: when the pointer has pushed far enough past one end to come in at the other, and where
 * the cursor goes when it does.  Kept apart so the controls' wrap is unit testable without a Compose harness (see
 * ControlWrapMathTest and ControlWrapTrackerTest).
 *
 * A wrapping axis keeps a lap shift.  The control reads the pointer at its virtual place less that shift, so the value
 * comes in at the other end once the pointer is past an end by more than the detent, and it lands with the same slack
 * at that end, so crossing back takes the same push.  Inside the slack the value holds at the end, which keeps max
 * reachable.  The shift is in screen space, so an axis whose maximum is at the top needs nothing of its own.
 */

/**
 * How long after a warp the events may keep arriving from where the cursor was before the landing counts as one
 * that did not hold, as a pen in absolute mode does: its next sample puts the cursor back where the pen is.
 */
const val WARP_LANDING_HOLD_MILLIS = 100L

/**
 * One axis of a control's travel, in the control's own pixels.
 *
 * @property Float bandStart The band's low end along the axis: the nearer edge to the axis's origin, whichever end of
 *   the range sits there.
 * @property Float travel The band's length, from one end of the range to the other.
 * @property Boolean wraps Whether the value wraps past the band's ends instead of stopping at them.
 */
data class ControlWrapAxis(
	val bandStart: Float,
	val travel: Float,
	val wraps: Boolean,
) {
	companion object {
		/** An axis that never wraps, for the slider's cross axis. */
		val CLAMPED = ControlWrapAxis(bandStart = 0f, travel = 1f, wraps = false)
	}
}

/**
 * The lap shift a wrapping axis takes once its virtual pointer reaches [virtual]: unchanged while the pointer, less
 * the shift, stays within [detent] of the band, and moved by whole laps of [travel] plus [detent] once it is past an
 * end by more, so the pointer comes back in at the other end with the same slack.  One step covers any number of laps.
 *
 * @param Float shift The lap shift before this pointer position.
 * @param Float virtual The axis's virtual pointer coordinate.
 * @param Float bandStart The band's low end.
 * @param Float travel The band's length.
 * @param Float detent How far past an end the pointer pushes before it crosses, in the same pixels.
 * @return Float The lap shift after it.
 */
fun controlWrapShiftAfter(shift: Float, virtual: Float, bandStart: Float, travel: Float, detent: Float): Float {
	val lap = travel + detent

	if (!(lap > 0f)) {
		return shift
	}

	val control = virtual - shift
	val high = bandStart + travel + detent
	val low = bandStart - detent

	return when {
		control > high -> shift + ceil((control - high) / lap) * lap
		control < low -> shift - ceil((low - control) / lap) * lap
		else -> shift
	}
}

/**
 * How far from a warp's landing a fresh event may arrive: half the lap of the smallest wrapping axis, the distance a
 * landing jumps along it.
 *
 * @param ControlWrapAxis horizontal The control's horizontal axis.
 * @param ControlWrapAxis vertical The control's vertical axis.
 * @param Float detent The detent, in pixels.
 * @return Float The reach in pixels; unbounded when neither axis wraps, which never warps.
 */
fun controlWrapReach(horizontal: ControlWrapAxis, vertical: ControlWrapAxis, detent: Float): Float {
	val laps = listOf(horizontal, vertical).filter { axis -> axis.wraps }.map { axis -> axis.travel + detent }

	return (laps.minOrNull() ?: Float.POSITIVE_INFINITY) / 2f
}

/**
 * The wrap of one press-and-drag on a control: where the control reads the pointer, each wrapping axis's lap shift, and
 * the warp that brings the cursor to the thumb after a crossing.  The value is right whether or not the warp happens,
 * so a platform with no cursor, or one whose landing does not hold, wraps the value under the pointer.  One tracker
 * lives for one gesture.
 *
 * @param Function warp Warps the OS cursor to an absolute screen position and returns where it landed, or null.
 */
internal class ControlWrapTracker(
	warp: (Float, Float) -> Offset?,
) {
	private val cursorWrap = CursorWrapState(warp)
	private var horizontalShift = 0f
	private var verticalShift = 0f

	// False once a landing in this gesture did not hold: the cursor stays where the pointer puts it from then on.
	private var warpsHold = true
	private var warpedAtMillis = 0L

	/**
	 * Where the control reads the pointer for one event of the gesture, the press included: the virtual pointer less
	 * each axis's lap shift.  An event that is stale against a pending warp reads at the landing, so the value holds
	 * still until the cursor's real events arrive; if they have not come by [WARP_LANDING_HOLD_MILLIS], the warp is
	 * taken back out and the gesture warps no more.  A crossing warps the cursor to where the control reads it.
	 *
	 * @param Offset position The event's pointer position, in the control's pixels.
	 * @param Long timeMillis The event's time.
	 * @param ControlWrapAxis horizontal The control's horizontal axis.
	 * @param ControlWrapAxis vertical The control's vertical axis.
	 * @param Float detent How far past an end the pointer pushes before it crosses, in pixels.
	 * @param Function screenOrigin The control's top-left on screen, asked only when warping; null before layout.
	 * @return Offset The point the control reads, in its own pixels.
	 */
	fun controlPointOf(
		position: Offset,
		timeMillis: Long,
		horizontal: ControlWrapAxis,
		vertical: ControlWrapAxis,
		detent: Float,
		screenOrigin: () -> Offset?,
	): Offset {
		val reach = controlWrapReach(horizontal, vertical, detent)
		val trace = if (horizontal.wraps || vertical.wraps) controlWrapTrace else null
		val staleLanding = cursorWrap.resolveArrival(position, reach)
		var raw = position

		if (staleLanding != null) {
			if (timeMillis - warpedAtMillis > WARP_LANDING_HOLD_MILLIS) {
				cursorWrap.abandonPendingWarp()
				warpsHold = false
				trace?.invoke("abandon t=$timeMillis position=$position")
			} else {
				raw = staleLanding
			}
		}

		val virtual = cursorWrap.virtualPointer(raw)
		val nextHorizontalShift = if (horizontal.wraps) controlWrapShiftAfter(horizontalShift, virtual.x, horizontal.bandStart, horizontal.travel, detent) else 0f
		val nextVerticalShift = if (vertical.wraps) controlWrapShiftAfter(verticalShift, virtual.y, vertical.bandStart, vertical.travel, detent) else 0f
		val horizontalCrossed = nextHorizontalShift != horizontalShift
		val verticalCrossed = nextVerticalShift != verticalShift
		horizontalShift = nextHorizontalShift
		verticalShift = nextVerticalShift
		val control = Offset(virtual.x - horizontalShift, virtual.y - verticalShift)
		trace?.invoke(
			"t=$timeMillis position=$position stale=${staleLanding != null} virtual=$virtual control=$control " +
				"shift=($horizontalShift, $verticalShift)",
		)

		if ((horizontalCrossed || verticalCrossed) && warpsHold) {
			// The cursor goes where the control now reads it on a crossed axis and stays put on the other.
			val target = Offset(if (horizontalCrossed) control.x else raw.x, if (verticalCrossed) control.y else raw.y)
			val origin = screenOrigin()
			val landed = cursorWrap.warpTo(raw, target, origin)
			trace?.invoke("warp t=$timeMillis origin=$origin target=$target landed=$landed")

			if (landed != null) {
				warpedAtMillis = timeMillis
			}
		}

		return control
	}
}

/** Writes each step of a wrapping control's gesture, for measuring the warp by hand; null writes nothing. */
internal var controlWrapTrace: ((String) -> Unit)? = { line -> println("controlWrap $line") }