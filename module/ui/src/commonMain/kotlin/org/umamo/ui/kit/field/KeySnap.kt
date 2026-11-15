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
 * @param Float value The unsnapped value.
 * @param List targets The values a scrub may latch onto, in the same units as [value].
 * @param Float tolerance How far from a target [value] may be and still latch, in those units.
 * @return Float The nearest target within reach, the first in list order on a tie, or [value].
 */
fun snapToNearestWithin(value: Float, targets: List<Float>, tolerance: Float): Float {
	var nearest = value
	var nearestDistance = Float.POSITIVE_INFINITY

	for (target in targets) {
		val distance = abs(target - value)

		// Strictly nearer, so the first of two equally near targets keeps the snap.
		if (distance <= tolerance && distance < nearestDistance) {
			nearest = target
			nearestDistance = distance
		}
	}

	return nearest
}