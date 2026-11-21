package org.umamo.ui.kit.field

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.hypot

/*
 * Pure, Compose-free dial math: where a pointer sits on the dial's one lap, how many times a drag has crossed the
 * seam, the value the two make, and where a value draws.  Kept apart so the dial's behavior is unit testable without
 * a Compose harness (see DialMathTest).
 *
 * The seam, where min and max meet, sits at 12 o'clock, and values increase clockwise.
 */

/** A full turn in radians. */
private const val FULL_TURN = (2.0 * PI).toFloat()

/**
 * The part of a lap a pointer sits at, measured clockwise from 12 o'clock around the dial's center, in screen space
 * where y grows downward.
 *
 * @param Float x The pointer x.
 * @param Float y The pointer y.
 * @param Float centerX The dial center's x.
 * @param Float centerY The dial center's y.
 * @param Float deadZone How near the center the pointer may come before its angle is too noisy to read, in the same
 *   units as the coordinates.
 * @return Float? The fraction of a lap in [0, 1), or null inside the dead zone.
 */
fun dialFractionAt(x: Float, y: Float, centerX: Float, centerY: Float, deadZone: Float): Float? {
	val offsetX = x - centerX
	val offsetY = y - centerY

	if (hypot(offsetX, offsetY) <= deadZone) {
		return null
	}

	// With y down, atan2(x, -y) is 0 straight up and grows clockwise.
	val fraction = atan2(offsetX, -offsetY) / FULL_TURN
	val oneLap = if (fraction < 0f) fraction + 1f else fraction

	// A hair below zero lifted by a whole lap can round up to exactly 1, which is the seam again.
	return if (oneLap >= 1f) 0f else oneLap
}

/**
 * The laps a drag has made once the pointer steps from [previousFraction] to [fraction].  A step of more than half a
 * lap is read as the short way across the seam: a fall from near 1 to near 0 is a lap forward, and a rise the other
 * way is a lap back.
 *
 * @param Int laps The laps made before the step.
 * @param Float previousFraction The pointer's fraction of a lap before the step.
 * @param Float fraction The pointer's fraction of a lap after it.
 * @param Boolean wraps Whether the dial's owner wraps the value at the seam; false holds the count to -1..1.
 * @return Int The laps made after the step.
 */
fun dialLapsAfter(laps: Int, previousFraction: Float, fraction: Float, wraps: Boolean): Int {
	val step = fraction - previousFraction
	val next =
		when {
			step < -0.5f -> laps + 1
			step > 0.5f -> laps - 1
			else -> laps
		}

	// Held on every step, crossing or not, so a count made while the owner wrapped folds in once it clamps.
	return if (wraps) next else next.coerceIn(-1, 1)
}

/**
 * The value a pointer at [fraction], [laps] laps out, stands for: inside [range] on the first lap, and past its end by
 * one span for every lap.  The dial's owner brings it back in, by wrapping or clamping.
 *
 * @param Float fraction The pointer's fraction of a lap, in [0, 1).
 * @param Int laps The laps the drag has made, negative for laps back.
 * @param ClosedFloatingPointRange range The value range one lap covers.
 * @return Float The value.
 */
fun dialValue(fraction: Float, laps: Int, range: ClosedFloatingPointRange<Float>): Float =
	range.start + (laps + fraction) * (range.endInclusive - range.start)

/**
 * The part of the ring [value] fills, from the seam clockwise.  Exactly max fills the whole ring and min none, so the
 * two read apart at the angle they share.
 *
 * @param Float value The value to draw.
 * @param ClosedFloatingPointRange range The value range one lap covers.
 * @return Float The fraction of the ring, in 0..1; 0 for an empty range.
 */
fun dialFillFraction(value: Float, range: ClosedFloatingPointRange<Float>): Float {
	val span = range.endInclusive - range.start

	if (!(span > 0f)) {
		return 0f
	}

	return ((value - range.start) / span).coerceIn(0f, 1f)
}