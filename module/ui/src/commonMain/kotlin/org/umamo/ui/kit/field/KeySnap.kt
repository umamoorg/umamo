package org.umamo.ui.kit.field

import androidx.compose.ui.unit.dp
import kotlin.math.abs

/**
 * How close, on screen, a held-Ctrl scrub has to come to a key before it latches onto it.  One reach for
 * the parameter slider and the keyform sheet, so a key pulls the same way wherever it is drawn.
 */
val KEY_SNAP_RADIUS = 6.dp

/**
 * The target nearest [value] when one lies within [tolerance] of it, else [value] unchanged: the magnetic
 * snap a held-Ctrl scrub applies, so the value latches onto a key only near it and moves freely between.
 *
 * Only a target inside [bounds] may latch.  The caller clamps the value to the same range, so a target past
 * it would pin the value to the wall, which is neither the key nor the pointer.
 *
 * @param Float value The unsnapped value.
 * @param List targets The values a scrub may latch onto, in the same units as [value].
 * @param Float tolerance How far from a target [value] may be and still latch, in those units.
 * @param ClosedFloatingPointRange bounds The range the value is kept inside.
 * @return Float The nearest target within reach, the first in list order on a tie, or [value].
 */
fun snapToNearestWithin(value: Float, targets: List<Float>, tolerance: Float, bounds: ClosedFloatingPointRange<Float>): Float {
	var nearest = value
	var nearestDistance = Float.POSITIVE_INFINITY

	for (target in targets) {
		val distance = abs(target - value)

		// Strictly nearer, so the first of two equally near targets keeps the snap.
		if (target in bounds && distance <= tolerance && distance < nearestDistance) {
			nearest = target
			nearestDistance = distance
		}
	}

	return nearest
}

/**
 * The target nearest [value] on a loop whose two ends meet, when one lies within [tolerance] of it, else [value]
 * unchanged: the held-Ctrl snap of a control that runs round from min back to max, so a key at max pulls a value
 * just past min.
 *
 * Distance is measured around the loop, so [value] may sit laps outside [range] and still latch.  The result is the
 * target itself, never its image a lap away, and only a target inside [range] latches, as with [snapToNearestWithin].
 *
 * @param Float value The unsnapped value, which may lie laps outside [range].
 * @param List targets The values a scrub may latch onto, in the same units as [value].
 * @param Float tolerance How far around the loop from a target [value] may be and still latch, in those units.
 * @param ClosedFloatingPointRange range The range whose ends meet; empty, the snap measures plain distance.
 * @return Float The nearest target within reach, the first in list order on a tie, or [value].
 */
fun snapToNearestWithinPeriodic(value: Float, targets: List<Float>, tolerance: Float, range: ClosedFloatingPointRange<Float>): Float {
	val span = range.endInclusive - range.start

	if (!(span > 0f)) {
		return snapToNearestWithin(value, targets, tolerance, range)
	}

	var nearest = value
	var nearestDistance = Float.POSITIVE_INFINITY

	for (target in targets) {
		val apart = abs(target - value).mod(span)
		val distance = minOf(apart, span - apart)

		// Strictly nearer, so the first of two equally near targets keeps the snap.
		if (target in range && distance <= tolerance && distance < nearestDistance) {
			nearest = target
			nearestDistance = distance
		}
	}

	return nearest
}